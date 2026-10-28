package dev.abdus.apps.shimmer.p1

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.P1SourceImageLinkEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * P1 persistence boundary for SAF/file sources.
 *
 * The repository is deliberately add/update-only: a scan result never deletes source links or
 * image records. A failed, cancelled, revoked, or empty provider result updates source status and
 * leaves the last committed set available to the rest of the app.
 */
class P1SourceRepository internal constructor(
    context: Context,
    private val databaseOverride: ShimmerDatabase? = null,
    val target: WallpaperTarget = WallpaperTarget.HOME,
) {
    private val appContext = context.applicationContext
    private val database = databaseOverride ?: ShimmerDatabase.getInstance(appContext, target)
    private val dao = database.p1SourceDao()
    private val scanner = P1AndroidSourceScanner(appContext)
    private val boundsReader = P1ImageBoundsReader(appContext.contentResolver)
    val sources: Flow<List<P1SourceEntity>> = dao.sourcesFlow()

    suspend fun importDirectory(uri: Uri, includeSubdirectories: Boolean = true): P1ImportResult =
        importDirectory(uri, includeSubdirectories) { }

    suspend fun importDirectory(
        uri: Uri,
        includeSubdirectories: Boolean,
        onProgress: (P1ScanProgress) -> Unit,
    ): P1ImportResult =
        importSource(
            P1SourceSpec(
                sourceId = stableSourceId(uri, P1SourceKind.DIRECTORY_TREE),
                rootUri = uri.toString(),
                kind = P1SourceKind.DIRECTORY_TREE,
                includeSubdirectories = includeSubdirectories,
            ),
            onProgress = onProgress,
        )

    /** Imports one or more selected documents while preserving their individual source identity. */
    suspend fun importSelectedDocuments(uris: List<Uri>): List<P1ImportResult> =
        uris.distinct().map { uri ->
            importSource(
                P1SourceSpec(
                    sourceId = stableSourceId(uri, P1SourceKind.MULTIPLE_DOCUMENTS),
                    rootUri = uri.toString(),
                    kind = P1SourceKind.MULTIPLE_DOCUMENTS,
                    includeSubdirectories = false,
                ),
                allowAppCopy = true,
            )
        }

    suspend fun importSelectedDocuments(
        uris: List<Uri>,
        onProgress: (P1ScanProgress) -> Unit,
    ): P1ImportResult {
        val results = uris.distinct().map { uri ->
            importSource(
                P1SourceSpec(
                    sourceId = stableSourceId(uri, P1SourceKind.MULTIPLE_DOCUMENTS),
                    rootUri = uri.toString(),
                    kind = P1SourceKind.MULTIPLE_DOCUMENTS,
                    includeSubdirectories = false,
                ),
                allowAppCopy = true,
                onProgress = onProgress,
            )
        }
        val first = results.firstOrNull()
        return P1ImportResult(
            sourceId = first?.sourceId ?: stableSourceId("empty", P1SourceKind.MULTIPLE_DOCUMENTS),
            state = when {
                results.any { it.state == P1ImportState.COMMITTED } -> P1ImportState.COMMITTED
                results.any { it.state == P1ImportState.PERMISSION_REQUIRED } -> P1ImportState.PERMISSION_REQUIRED
                results.any { it.state == P1ImportState.FAILED } -> P1ImportState.FAILED
                results.any { it.state == P1ImportState.CANCELLED } -> P1ImportState.CANCELLED
                else -> results.firstOrNull()?.state ?: P1ImportState.EMPTY
            },
            committedImageCount = results.sumOf { it.committedImageCount },
        )
    }

    /**
     * P9-MULTI-GROUP-01：一次多选添加的所有图片成为一个来源组，像一个目录一样在图库页成组显示；
     * 再次多选添加会生成新的合成根，因此成为另一组。组根只是身份，不是可打开的位置。
     *
     * 每张图片仍走单张导入的授权、应用内副本和元数据校验；不能读取的图片被跳过并计入
     * [P1ImportResult.skippedImageCount]。一张都没能加入或导入被取消时，新组整体删除，
     * 不留下空来源。已有来源和图片记录不受影响。
     */
    suspend fun importImageBatch(
        uris: List<Uri>,
        onProgress: (P1ScanProgress) -> Unit = {},
    ): P1ImportResult {
        val selected = uris.distinct()
        if (selected.isEmpty()) {
            return P1ImportResult(stableSourceId("empty", P1SourceKind.MULTIPLE_DOCUMENTS), P1ImportState.EMPTY, 0)
        }
        val root = P1ImageBatch.newRootUri()
        val spec = P1SourceSpec(
            sourceId = stableSourceId(root, P1SourceKind.MULTIPLE_DOCUMENTS),
            rootUri = root,
            kind = P1SourceKind.MULTIPLE_DOCUMENTS,
            includeSubdirectories = false,
        )
        var keep = false
        try {
            val result = runBatch(spec, selected.map { BatchItem(it) }, onProgress)
            keep = result.state == P1ImportState.COMMITTED
            return if (keep) result else result.copy(committedImageCount = 0)
        } finally {
            if (!keep) withContext(NonCancellable) { deleteSource(spec.sourceId) }
        }
    }

    /** One image of a batch group; [keepNamesOf] is passed through to [importSource]. */
    private class BatchItem(val uri: Uri, val keepNamesOf: P1ImageRecordEntity? = null)

    private fun batchSpec(source: P1SourceEntity) = P1SourceSpec(
        sourceId = source.id,
        rootUri = source.rootUri,
        kind = P1SourceKind.MULTIPLE_DOCUMENTS,
        includeSubdirectories = false,
        enabled = source.enabled,
    )

    /**
     * Imports [items] one by one into the batch source [spec] without changing its root. Each
     * item overwrites the source state, so the state is rewritten once at the end: committed
     * when any item committed, otherwise the most severe item result.
     */
    private suspend fun runBatch(
        spec: P1SourceSpec,
        items: List<BatchItem>,
        onProgress: (P1ScanProgress) -> Unit,
    ): P1ImportResult {
        if (items.isEmpty()) return P1ImportResult(spec.sourceId, P1ImportState.EMPTY, 0)
        val results = mutableListOf<P1ImportResult>()
        var committedPermission: String? = null
        for ((index, item) in items.withIndex()) {
            val result = try {
                importSource(
                    spec,
                    allowAppCopy = true,
                    scanUriOverride = item.uri,
                    keepNamesOf = item.keepNamesOf,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One unreadable selection must not abort the images that were readable.
                P1ImportResult(spec.sourceId, P1ImportState.FAILED, 0)
            }
            results += result
            if (result.state == P1ImportState.COMMITTED) {
                committedPermission = dao.getSource(spec.sourceId)?.permissionState ?: committedPermission
            }
            onProgress(P1ScanProgress(index + 1, results.sumOf { it.committedImageCount }, ""))
            if (result.state == P1ImportState.CANCELLED) break
        }
        if (results.any { it.state == P1ImportState.CANCELLED }) {
            return P1ImportResult(spec.sourceId, P1ImportState.CANCELLED, results.sumOf { it.committedImageCount })
        }
        val committed = results.count { it.state == P1ImportState.COMMITTED }
        val skipped = items.size - committed
        if (committed > 0) {
            markState(spec.sourceId, "COMMITTED", committedPermission ?: "APP_OWNED", isoNow(),
                if (skipped > 0) "BATCH_SKIPPED:$skipped" else null)
            return P1ImportResult(spec.sourceId, P1ImportState.COMMITTED,
                results.sumOf { it.committedImageCount }, skipped)
        }
        val worst = results.minBy { it.state.batchSeverity() }.state
        val current = dao.getSource(spec.sourceId)
        if (current != null) {
            val permission = if (worst == P1ImportState.PERMISSION_REQUIRED) "REVOKED" else current.permissionState
            // Import state names are also the scan state names written by importSource.
            markState(spec.sourceId, worst.name, permission, isoNow(), "BATCH_SKIPPED:$skipped")
        }
        return P1ImportResult(spec.sourceId, worst, 0, skipped)
    }

    private fun P1ImportState.batchSeverity(): Int = when (this) {
        P1ImportState.PERMISSION_REQUIRED -> 0
        P1ImportState.FAILED -> 1
        P1ImportState.INCOMPLETE -> 2
        P1ImportState.METADATA_FAILED -> 3
        P1ImportState.CANCELLED -> 4
        P1ImportState.EMPTY -> 5
        P1ImportState.COMMITTED -> 6
    }

    suspend fun importSingleDocument(uri: Uri): P1ImportResult =
        importSingleDocument(uri) { }

    suspend fun importSingleDocument(
        uri: Uri,
        onProgress: (P1ScanProgress) -> Unit,
    ): P1ImportResult =
        importSource(
            P1SourceSpec(
                sourceId = stableSourceId(uri, P1SourceKind.SINGLE_DOCUMENT),
                rootUri = uri.toString(),
                kind = P1SourceKind.SINGLE_DOCUMENT,
                includeSubdirectories = false,
            ),
            allowAppCopy = true,
            onProgress = onProgress,
        )

    /**
     * [keepNamesOf] is the committed row of an app-owned copy being rescanned. The copy's file
     * name is a content hash, so the scanner's name would replace the name the user picked.
     */
    private suspend fun importSource(
        spec: P1SourceSpec,
        allowAppCopy: Boolean = false,
        onProgress: (P1ScanProgress) -> Unit = {},
        storedRootUri: String = spec.rootUri,
        scanUriOverride: Uri? = null,
        keepNamesOf: P1ImageRecordEntity? = null,
    ): P1ImportResult =
        withContext(Dispatchers.IO) {
            val now = isoNow()
            val registered = database.withTransaction {
                dao.registerSource(
                    P1SourceEntity(
                        id = spec.sourceId,
                        rootUri = storedRootUri,
                        kind = spec.kind.name,
                        includeSubdirectories = spec.includeSubdirectories,
                        enabled = spec.enabled,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            val effectiveSpec = spec.copy(sourceId = registered.id, rootUri = storedRootUri,
                includeSubdirectories = registered.includeSubdirectories)

            val originalUri = Uri.parse(storedRootUri)
            val requestedUri = scanUriOverride ?: originalUri
            var scanUri = requestedUri
            var copiedDisplayName: String? = null
            var permissionState = permissionStateFor(requestedUri)
            if (requestedUri.scheme == "content" && !P1SourceIntents.hasPersistedReadPermission(appContext, requestedUri)) {
                if (allowAppCopy) {
                    scanUri = try {
                        copyIntoAppStorage(requestedUri)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: P1TreeReadException) {
                        // A failed replacement must leave both the committed row and its
                        // previously readable app-owned bytes available for playback.
                        markState(effectiveSpec.sourceId, "METADATA_FAILED", registered.permissionState,
                            isoNow(), PreserveReason.METADATA_FAILURE.name)
                        return@withContext P1ImportResult(effectiveSpec.sourceId, P1ImportState.METADATA_FAILED, 0)
                    } catch (_: Exception) {
                        markState(effectiveSpec.sourceId, "PERMISSION_REQUIRED", "REVOKED", null, "persistable read permission unavailable")
                        return@withContext P1ImportResult(effectiveSpec.sourceId, P1ImportState.PERMISSION_REQUIRED, 0)
                    }
                    permissionState = "APP_COPY"
                    copiedDisplayName = selectedDisplayName(requestedUri)
                } else {
                    markState(effectiveSpec.sourceId, "PERMISSION_REQUIRED", "REVOKED", null, "persistable read permission unavailable")
                    return@withContext P1ImportResult(effectiveSpec.sourceId, P1ImportState.PERMISSION_REQUIRED, 0)
                }
            }

            markState(effectiveSpec.sourceId, "SCANNING", permissionState, null, null)
            val activeContext = currentCoroutineContext()
            val progressReporter = P1ScanProgressReporter(onProgress)
            val scannedOutcome = try {
                scanner.scan(
                    source = effectiveSpec.copy(rootUri = scanUri.toString()),
                    checkActive = { activeContext.ensureActive() },
                    onProgress = { progressReporter.report(it) },
                )
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    markState(effectiveSpec.sourceId, "CANCELLED", permissionState, isoNow(), PreserveReason.CANCELLED.name)
                }
                return@withContext P1ImportResult(effectiveSpec.sourceId, P1ImportState.CANCELLED, 0)
            }
            val outcome = try {
                when (scannedOutcome) {
                    is P1ScanOutcome.Complete -> {
                        val identity = if (allowAppCopy && requestedUri.scheme == "content") {
                            "image:${p1DocumentIdentity(requestedUri)}"
                        } else null
                        validateMetadata(
                            scannedOutcome.copy(
                                images = scannedOutcome.images.map {
                                    it.copy(
                                        stableKey = identity ?: it.stableKey,
                                        displayName = copiedDisplayName ?: keepNamesOf?.displayName ?: it.displayName,
                                        relativePath = copiedDisplayName ?: keepNamesOf?.relativePath ?: it.relativePath,
                                    )
                                },
                            ),
                            progressReporter,
                        )
                    }
                    else -> scannedOutcome
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    markState(effectiveSpec.sourceId, "CANCELLED", permissionState, isoNow(), PreserveReason.CANCELLED.name)
                }
                return@withContext P1ImportResult(effectiveSpec.sourceId, P1ImportState.CANCELLED, 0)
            }
            try {
                when (val decision = P1ScanCommitGate.decide(outcome)) {
                is P1ScanCommitDecision.Commit -> {
                    if (decision.images.isEmpty()) {
                        markState(effectiveSpec.sourceId, "EMPTY", permissionState, isoNow(), null)
                        P1ImportResult(effectiveSpec.sourceId, P1ImportState.EMPTY, 0)
                    } else {
                        progressReporter.report(P1ScanProgress(0, decision.images.size, "",
                            P1ScanPhase.COMMIT, decision.images.size, decision.images.size), force = true)
                        commitImages(effectiveSpec, decision.images, permissionState)
                        progressReporter.report(P1ScanProgress(0, decision.images.size, "",
                            P1ScanPhase.COMMIT, decision.images.size, decision.images.size), force = true)
                        val skippedMetadata = (outcome as? P1ScanOutcome.Complete)?.skippedMetadata ?: 0
                        if (skippedMetadata > 0) {
                            // A partial commit is safe because commitScan is add/update-only. Keep
                            // the source usable while making the skipped rows visible for repair.
                            markState(
                                effectiveSpec.sourceId,
                                "COMMITTED",
                                permissionState,
                                isoNow(),
                                "METADATA_SKIPPED:$skippedMetadata",
                            )
                        }
                        P1ImportResult(effectiveSpec.sourceId, P1ImportState.COMMITTED, decision.images.size)
                    }
                }
                is P1ScanCommitDecision.Preserve -> {
                    val state = when (decision.reason) {
                        PreserveReason.EMPTY_RESULT -> "EMPTY"
                        PreserveReason.CANCELLED -> "CANCELLED"
                        PreserveReason.PERMISSION_FAILURE -> "REVOKED"
                        PreserveReason.READ_FAILURE -> "FAILED"
                        PreserveReason.INCOMPLETE_RESULT -> "INCOMPLETE"
                        PreserveReason.METADATA_FAILURE -> "METADATA_FAILED"
                    }
                    val permission = if (decision.reason == PreserveReason.PERMISSION_FAILURE) "REVOKED" else permissionState
                    val mark = suspend {
                        markState(effectiveSpec.sourceId, state, permission, isoNow(), decision.reason.name)
                    }
                    if (decision.reason == PreserveReason.CANCELLED) {
                        withContext(NonCancellable) { mark() }
                    } else {
                        mark()
                    }
                    P1ImportResult(effectiveSpec.sourceId, P1ImportState.fromPreserve(decision.reason), 0)
                }
                }
            } catch (_: CancellationException) {
                // Cancellation during Room commit must not leave the source stuck in SCANNING.
                // The transaction has rolled back, and this status write is deliberately allowed
                // to finish without the cancelled caller context.
                withContext(NonCancellable) {
                    markState(effectiveSpec.sourceId, "CANCELLED", permissionState, isoNow(), PreserveReason.CANCELLED.name)
                }
                P1ImportResult(effectiveSpec.sourceId, P1ImportState.CANCELLED, 0)
            }
        }

    suspend fun rescan(sourceId: String, onProgress: (P1ScanProgress) -> Unit = {}): P1ImportResult {
        val source = requireNotNull(dao.getSource(sourceId)) { "Unknown P1 source" }
        if (P1ImageBatch.isBatchRoot(source.rootUri)) {
            // 合成根不能打开；按组里已关联的图片逐张复扫。应用内副本保留用户选图时的文件名。
            val items = dao.getSourceImages(source.id).map { image ->
                val uri = Uri.parse(image.uri)
                BatchItem(uri, keepNamesOf = image.takeIf { uri.scheme == "file" })
            }
            return runBatch(batchSpec(source), items, onProgress)
        }
        return importSource(
            P1SourceSpec(source.id, source.rootUri, P1SourceKind.valueOf(source.kind), source.includeSubdirectories, source.enabled),
            onProgress = onProgress,
            storedRootUri = source.rootUri,
        )
    }

    /** Rebinds a source to user-selected access URIs while retaining its source identity and links. */
    suspend fun reauthorize(
        sourceId: String,
        uris: List<Uri>,
        onProgress: (P1ScanProgress) -> Unit = {},
    ): P1ImportResult {
        val source = requireNotNull(dao.getSource(sourceId)) { "Unknown P1 source" }
        val selected = uris.distinct()
        if (selected.isEmpty()) return P1ImportResult(sourceId, P1ImportState.CANCELLED, 0)
        if (P1ImageBatch.isBatchRoot(source.rootUri)) {
            // 一组多选的根是身份而不是位置：重新选的图片并入同一组，根保持不变
            return runBatch(batchSpec(source), selected.map { BatchItem(it) }, onProgress)
        }
        val kind = runCatching { P1SourceKind.valueOf(source.kind) }.getOrDefault(P1SourceKind.SINGLE_DOCUMENT)
        val results = selected.map { uri ->
            importSource(
                P1SourceSpec(sourceId, source.rootUri, kind, source.includeSubdirectories, source.enabled),
                allowAppCopy = kind != P1SourceKind.DIRECTORY_TREE,
                onProgress = onProgress,
                // Each replacement grant is its own access URI. This prevents an APP_COPY
                // fallback from copying the first selection for every item in a multi-select.
                storedRootUri = uri.toString(),
            )
        }
        return P1ImportResult(
            sourceId,
            when {
                results.any { it.state == P1ImportState.COMMITTED } -> P1ImportState.COMMITTED
                else -> results.first().state
            },
            results.sumOf { it.committedImageCount },
        )
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        dao.setSourceEnabled(sourceId, enabled, isoNow())
    }

    /**
     * Directory paths of [sourceId] the user stopped. Each path also covers its own subtree, so a
     * descendant is stopped when any of its ancestors is listed.
     */
    suspend fun excludedDirectories(sourceId: String): Set<String> =
        dao.getExclusions(sourceId).map { it.directoryPath }.toSet()

    /**
     * Stops or resumes one directory of a source without rescanning. Image records and links are
     * untouched, so resuming restores exactly the images that played before.
     */
    suspend fun setDirectoryEnabled(sourceId: String, directoryPath: String, enabled: Boolean) {
        dao.setDirectoryExcluded(sourceId, directoryPath, !enabled, isoNow())
    }

    /**
     * Explicitly removes one user-added source. The source-image links are removed by
     * the Room foreign-key cascade; shared image_records remain intact so another source
     * or a saved diagnostic/playback configuration is never damaged implicitly. Only the
     * source's P4 resume point is dropped, so re-adding the folder starts it from the beginning.
     */
    suspend fun deleteSource(sourceId: String): Boolean {
        val deleted = dao.deleteSource(sourceId) > 0
        if (deleted) withContext(Dispatchers.IO) { PlaybackSnapshotStore(appContext, target).forgetSource(sourceId) }
        return deleted
    }

    suspend fun sourceImageCount(sourceId: String): Int = dao.getSourceImages(sourceId).size

    suspend fun sourceImageNames(sourceId: String): List<String> =
        dao.getSourceImages(sourceId).map { it.displayName.ifBlank { it.relativePath } }

    /**
     * Counts unique subdirectories for a source (for UI display).
     * Extracts directory paths from image relative paths.
     */
    suspend fun sourceSubdirectoryCount(sourceId: String): Int {
        val images = dao.getSourceImages(sourceId)
        return images
            .mapNotNull { image ->
                // Extract directory part from relative path
                val path = image.relativePath
                val lastSlash = path.lastIndexOf('/')
                if (lastSlash > 0) path.substring(0, lastSlash) else null
            }
            .distinct()
            .count()
    }

    /** Read-only image rows used by the P1 source card for previews and collapsed subdirectories. */
    suspend fun sourceImages(sourceId: String): List<P1ImageRecordEntity> =
        dao.getSourceImages(sourceId)

    /** A masked, provider-neutral snapshot suitable for attaching to a bug report. */
    suspend fun diagnosticSnapshot(): String {
        val sources = dao.getSources()
        val images = dao.getAllImages()
        val links = dao.getAllLinks()
        val exclusions = dao.getAllExclusions()
        fun mask(value: String): String = sha256(value).take(12)
        return buildString {
            appendLine("{\"schema\":7,\"sources\":[")
            sources.forEachIndexed { index, source ->
                if (index > 0) appendLine(",")
                val safeError = source.lastError?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val errorJson = safeError?.let { "\"$it\"" } ?: "null"
                append("{\"id\":\"${mask(source.id)}\",\"root\":\"${mask(source.rootUri)}\",\"kind\":\"${source.kind}\",\"recursive\":${source.includeSubdirectories},\"enabled\":${source.enabled},\"scanState\":\"${source.scanState}\",\"permissionState\":\"${source.permissionState}\",\"lastError\":$errorJson}")
            }
            // Folder names are user content, so only the count of stopped directories is reported.
            appendLine(
                "],\"imageCount\":${images.size},\"linkCount\":${links.size}," +
                    "\"excludedDirectoryCount\":${exclusions.size}}",
            )
        }
    }

    private suspend fun validateMetadata(
        outcome: P1ScanOutcome.Complete,
        progress: P1ScanProgressReporter,
    ): P1ScanOutcome {
        progress.report(P1ScanProgress(outcome.visitedNodes, outcome.images.size, "",
            P1ScanPhase.METADATA, 0, outcome.images.size), force = true)
        return P1MetadataValidator.validate(outcome, readBounds = { image ->
            // Bounds-only decoding returns null even on success; positive dimensions are
            // the success signal. Every input still goes through a real decoder; the reader
            // respects provider subranges and uses the same opened resource for fallback.
            boundsReader.read(Uri.parse(image.uri))
        }, onProcessed = { processed, total ->
            progress.report(P1ScanProgress(outcome.visitedNodes, total, "",
                P1ScanPhase.METADATA, processed, total), force = processed == total)
        })
    }

    private suspend fun commitImages(spec: P1SourceSpec, images: List<P1ScannedImage>, permissionState: String) {
        val now = isoNow()
        val entities = images.map { image ->
            P1ImageRecordEntity(
                id = image.stableKey,
                uri = image.uri,
                displayName = image.displayName,
                relativePath = image.relativePath,
                sizeBytes = image.sizeBytes,
                width = image.width,
                height = image.height,
                metadataState = "COMMITTED",
                createdAt = now,
                updatedAt = now,
            )
        }
        dao.commitScan(spec.sourceId, entities, permissionState, now)
    }

    private suspend fun markState(
        sourceId: String,
        scanState: String,
        permissionState: String,
        lastScanAt: String?,
        error: String?,
    ) {
        val now = isoNow()
        database.withTransaction {
            dao.updateSourceState(sourceId, scanState, permissionState, lastScanAt, error, now)
        }
    }

    private fun permissionStateFor(uri: Uri): String = when (uri.scheme) {
        "content" -> if (P1SourceIntents.hasPersistedReadPermission(appContext, uri)) "PERSISTED" else "TRANSIENT"
        else -> "APP_OWNED"
    }

    private fun copyIntoAppStorage(uri: Uri): Uri {
        val sourceName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "image"
        val extension = sourceName.substringAfterLast('.', "bin").lowercase(Locale.US).take(8)
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        // A re-authorised LOCK copy must never replace bytes referenced by HOME's independent
        // committed graph. HOME keeps its original path; only new LOCK copies use a new folder.
        val directory = if (target == WallpaperTarget.HOME) "p1-images" else "p10-lock-images"
        val destination = File(appContext.filesDir, "$directory/$digest.$extension").apply {
            parentFile?.mkdirs()
        }
        // Never write directly to a path that may already be referenced by a committed row.
        // Validate the temporary copy before publishing to a URI a committed row may already
        // reference. A fully read response can still contain invalid or truncated image bytes.
        val temporary = File.createTempFile(".$digest.", ".tmp", destination.parentFile)
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to read selected document")
            validateCopiedImage(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
        return Uri.fromFile(destination)
    }

    private fun validateCopiedImage(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw P1TreeReadException(P1ScanFailure.METADATA)
        }
        // Inspect actual pixels with a bounded allocation: valid headers alone do not make a
        // usable replacement. The ordinary metadata transaction still runs after publication.
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.path, options)
            ?: throw P1TreeReadException(P1ScanFailure.METADATA)
        decoded.recycle()
    }

    private fun selectedDisplayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) it.getString(index)?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "image"

    companion object {
        fun stableSourceId(uri: Uri, kind: P1SourceKind): String =
            stableSourceId(uri.toString(), kind)

        fun stableSourceId(uri: String, kind: P1SourceKind): String =
            "source:${kind.name.lowercase(Locale.US)}:${sha256(uri)}"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

        private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}

enum class P1ImportState {
    COMMITTED,
    EMPTY,
    CANCELLED,
    PERMISSION_REQUIRED,
    FAILED,
    INCOMPLETE,
    METADATA_FAILED;

    companion object {
        fun fromPreserve(reason: PreserveReason): P1ImportState = when (reason) {
            PreserveReason.EMPTY_RESULT -> EMPTY
            PreserveReason.CANCELLED -> CANCELLED
            PreserveReason.PERMISSION_FAILURE -> PERMISSION_REQUIRED
            PreserveReason.READ_FAILURE -> FAILED
            PreserveReason.INCOMPLETE_RESULT -> INCOMPLETE
            PreserveReason.METADATA_FAILURE -> METADATA_FAILED
        }
    }
}

data class P1ImportResult(
    val sourceId: String,
    val state: P1ImportState,
    val committedImageCount: Int,
    /** P9-MULTI-GROUP-01：一组多选里未能加入的张数（撤权、无法读取或不是图片）。 */
    val skippedImageCount: Int = 0,
)
