package dev.abdus.apps.shimmer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import dev.abdus.apps.shimmer.database.FolderEntity
import dev.abdus.apps.shimmer.database.ImageEntity
import dev.abdus.apps.shimmer.database.ImageEntry
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.database.P1PlaybackCursorReader
import dev.abdus.apps.shimmer.database.P1RevisionCatalogReader
import dev.abdus.apps.shimmer.database.P1PlaybackItemMappingMemo
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p2.PlaybackCatalogTiming
import dev.abdus.apps.shimmer.p2.PlaybackCatalogTimingStage
import dev.abdus.apps.shimmer.p4.EffectiveCatalog
import dev.abdus.apps.shimmer.p4.PlaybackCatalog
import dev.abdus.apps.shimmer.p4.ScheduleStore
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ImageFolderRepository(
    context: Context,
    private val scope: CoroutineScope,
    val target: WallpaperTarget = WallpaperTarget.HOME,
) {
    private val appContext = context.applicationContext
    private val prefs = WallpaperPreferences.create(appContext, target)
    private val db = ShimmerDatabase.getInstance(appContext, target)
    private val dao = db.imageDao()
    private val playbackCursorReader = P1RevisionCatalogReader(db)
    private val playbackItemMappingMemo = P1PlaybackItemMappingMemo()
    private val scheduleStore = ScheduleStore(appContext, target)
    private val playbackSnapshotStore = PlaybackSnapshotStore(appContext, target)
    private val scanner = FolderScanner(appContext)

    private val _activeScans = MutableStateFlow<Set<Long>>(emptySet())
    val activeScans: StateFlow<Set<Long>> = _activeScans.asStateFlow()

    companion object {
        private const val TAG = "ImageFolderRepository"
        // Repositories can be created by multiple Engines/Activities. Serialize scans across them.
        private val scanCommitMutex = Mutex()

        data class FolderMetadata(
            val folderId: Long,
            val imageCount: Int,
            val thumbnailUri: Uri?,
            val isEnabled: Boolean,
            val isLocal: Boolean,
            val isScanning: Boolean,
        )

        private fun isoNow(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }

    init {
        Log.d(TAG, "Initializing Repository")
        // Legacy special folders belong only to HOME. LOCK starts empty, even when the app has
        // global favorites/shared destinations or when a caller explicitly asks to sync them.
        if (target == WallpaperTarget.HOME) {
            scope.launch { registerSpecialFolders() }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == WallpaperPreferences.KEY_FAVORITES_FOLDER_URI || key == WallpaperPreferences.KEY_SHARED_FOLDER_URI) {
                    Log.d(TAG, "Special folder configuration changed ($key), syncing")
                    scope.launch { registerSpecialFolders() }
                }
            }
            prefs.registerListener(listener)
        }
    }

    /**
     * Observes library changes and notifies if the current image is no longer valid.
     */
    fun observeImageInvalidation(
        uriProvider: () -> Uri?,
        onInvalidated: () -> Unit,
    ) {
        scope.launch {
            dao.getEnabledFoldersCountFlow()
                .distinctUntilChanged()
                .collect {
                    val currentUri = uriProvider() ?: return@collect
                    if (!isImageManagedAndEnabled(currentUri)) {
                        Log.d(TAG, "Current image invalidated by library change")
                        withContext(Dispatchers.Main) {
                            onInvalidated()
                        }
                    }
                }
        }
    }

    /**
     * Flow of folder metadata (URI, count, thumbnail) for the UI.
     */
    val foldersMetadataFlow: Flow<Map<Uri, FolderMetadata>> = combine(
        dao.getFoldersMetadataFlow(),
        activeScans,
    ) { list, scanning ->
        list.associate {
            it.folderUri to FolderMetadata(
                folderId = it.folderId,
                imageCount = it.imageCount,
                thumbnailUri = it.thumbnailUri,
                isEnabled = it.isEnabled,
                isLocal = it.folderUri.isLocalFolder(),
                isScanning = scanning.contains(it.folderId),
            )
        }
    }

    suspend fun registerSpecialFolders() = withContext(Dispatchers.IO) {
        if (target != WallpaperTarget.HOME) return@withContext
        try {
            val favoritesUri = FavoritesFolderResolver.getEffectiveFavoritesUri(prefs)
            val sharedUri = SharedFolderResolver.getEffectiveSharedUri(prefs)
            val timestamp = isoNow()

            db.withTransaction {
                // P0 retains previous special folders. Selecting another destination is not
                // permission to cascade-delete their image configuration.
                // Ensure folders exist. OnConflictStrategy.IGNORE handles existing ones.
                dao.insertFolder(FolderEntity(uri = favoritesUri, isEnabled = true, lastScannedAt = null, createdAt = timestamp))
                dao.insertFolder(FolderEntity(uri = sharedUri, isEnabled = true, lastScannedAt = null, createdAt = timestamp))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Log.e(TAG, "Failed to sync special folders: ${failed.javaClass.simpleName}")
        }
    }

    suspend fun addFolder(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val timestamp = isoNow()
            val folderId = db.withTransaction {
                val insertedId = dao.insertFolder(FolderEntity(uri = uri, isEnabled = true, lastScannedAt = null, createdAt = timestamp))
                if (insertedId != -1L) insertedId else dao.getFolderId(uri)
            }
            if (folderId != null) {
                scanAndIndex(folderId, uri)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Log.e(TAG, "Failed to add folder: ${failed.javaClass.simpleName}")
        }
    }

    suspend fun refreshFolder(uri: Uri) = withContext(Dispatchers.IO) {
        val folderId = dao.getFolderId(uri)
        if (folderId != null) {
            scanAndIndex(folderId, uri)
        }
    }

    suspend fun removeFolder(uri: Uri) = withContext(Dispatchers.IO) {
        dao.deleteFolderByUri(uri)
    }

    suspend fun toggleFolderEnabled(uri: Uri, enabled: Boolean) = withContext(Dispatchers.IO) {
        val folderId = dao.getFolderId(uri)
        if (folderId != null) {
            dao.updateFolderEnabled(folderId, enabled)
        }
    }

    suspend fun scanAndIndex(folderId: Long, folderUri: Uri) = withContext(Dispatchers.IO) {
        scanCommitMutex.withLock {
            _activeScans.update { it + folderId }
            try {
                val scanContext = currentCoroutineContext()
                val existingFiles = dao.getImageUrisAndSizesForFolder(folderId)
                    .associate { it.uri to it.fileSize }
                val result = scanner.scan(folderUri, existingFiles) { scanContext.ensureActive() }
                val committed = commitCompletedScan(result, { scanContext.ensureActive() }) { images ->
                    val timestamp = isoNow()
                    val entities = images.map {
                        ImageEntity(
                            folderId = folderId,
                            uri = it.uri,
                            createdAt = timestamp,
                            width = it.width,
                            height = it.height,
                            fileSize = it.fileSize,
                        )
                    }
                    db.withTransaction {
                        scanContext.ensureActive()
                        // P0 is deliberately add-only. An empty/filtered provider view is not proof
                        // that the user deleted a source. Explicit removal remains a user action.
                        dao.insertImages(entities)
                        dao.updateFolderLastScanned(folderId, timestamp)
                        scanContext.ensureActive()
                    }
                }
                if (committed) {
                    Log.i(TAG, "Scan committed; existing image configuration retained")
                } else {
                    val reason = (result as ScanResult.Failed).reason
                    Log.w(TAG, "Scan not committed: $reason; existing image configuration retained")
                }
            } catch (cancelled: CancellationException) {
                Log.i(TAG, "Scan cancelled; cancellation propagated")
                throw cancelled
            } finally {
                _activeScans.update { it - folderId }
            }
        }
    }


    suspend fun nextImageUri(): Uri? = withContext(Dispatchers.IO) {
        if (target != WallpaperTarget.HOME) return@withContext null
        // Fast, side-effect free query (except for updating timestamps)
        val image = dao.findNextCycleImage() ?: return@withContext null
        
        val now = isoNow()
        db.withTransaction {
            dao.updateFolderLastPicked(image.folderId, now)
            dao.updateLastShown(image.id, now)
        }
        
        image.uri
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun markImageAsInvalid(uri: Uri) = withContext(Dispatchers.IO) {
        // Decode/permission/provider failure does not establish that an image was removed.
        // The caller can skip this attempt; source reconciliation is a separate P1 concern.
        Log.w(TAG, "Image read failed; committed image configuration retained")
    }

    suspend fun incrementFavoriteRank(uri: Uri) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Incrementing favorite rank")
        dao.incrementFavoriteRank(uri)
    }

    suspend fun getImageByUri(uri: Uri): ImageEntity? = withContext(Dispatchers.IO) {
        dao.getImageByUri(uri)
    }

    suspend fun addSingleImageToFolder(folderUri: Uri, imageUri: Uri, width: Int? = null, height: Int? = null, fileSize: Long? = null) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Adding single image to folder")
        val folderId = dao.getFolderId(folderUri)

        if (folderId == null) {
            Log.e(TAG, "Folder not found in DB")
            return@withContext
        }

        val timestamp = isoNow()
        val entity = ImageEntity(
            folderId = folderId,
            uri = imageUri,
            createdAt = timestamp,
            width = width,
            height = height,
            fileSize = fileSize,
        )

        try {
            db.withTransaction {
                dao.insertImages(listOf(entity))
                dao.updateFolderLastScanned(folderId, timestamp)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Log.e(TAG, "Failed to add single image to folder: ${failed.javaClass.simpleName}")
        }
    }

    suspend fun getCurrentImageUri(): Uri? = withContext(Dispatchers.IO) {
        if (target == WallpaperTarget.HOME) dao.getLatestShownImage()?.uri else committedSnapshotUri()
    }

    /** Returns stable playback identities without advancing or marking any image as shown. */
    suspend fun getPlaybackItems(): List<PlaybackItem> = getPlaybackCatalog().items

    /** The effective catalog: a matching P4 rule's sources, else the P1 enabled catalog. */
    suspend fun getPlaybackCatalog(timing: PlaybackCatalogTiming? = null): PlaybackCatalog = withContext(Dispatchers.IO) {
        suspend fun <T> measured(stage: PlaybackCatalogTimingStage, block: suspend () -> T): T =
            if (timing == null) block() else timing.measure(stage, block)
        // P1 is the authoritative source model after migration. The old folders/images query is
        // retained only for installations that have not created any P1 source rows yet.
        val p1 = db.p1SourceDao()
        val decisions = measured(PlaybackCatalogTimingStage.DECISIONS) {
            val disabled = prefs.getDisabledImages()
            val hasSources = p1.hasSources()
            Triple(disabled, hasSources, if (hasSources) scheduleStore.loadSettings() else null)
        }
        val disabledIds = decisions.first
        if (decisions.second) {
            val resolved = measured(PlaybackCatalogTimingStage.DATABASE) {
                EffectiveCatalog.resolve(playbackCursorReader, checkNotNull(decisions.third), Instant.now(), ZoneId.systemDefault())
            }
            val items = measured(PlaybackCatalogTimingStage.MAPPING) {
                playbackItemMappingMemo.map(resolved.images, disabledIds)
            }
            return@withContext PlaybackCatalog(items, resolved.rule)
        }

        // A fresh/empty LOCK graph must not revive HOME's legacy folders or any legacy rows
        // accidentally present in its own file. Only explicit P1 members are playable in LOCK.
        if (target != WallpaperTarget.HOME) return@withContext PlaybackCatalog(emptyList(), null)

        val records = measured(PlaybackCatalogTimingStage.DATABASE) { dao.getEnabledImagesForPlayback() }
        val legacy = measured(PlaybackCatalogTimingStage.MAPPING) { records
            .filterNot { it.uri.toString() in disabledIds }
            .map { image ->
                val uri = image.uri.toString()
                val displayName = image.uri.lastPathSegment?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: uri
                PlaybackItem(
                    id = uri,
                    displayName = displayName,
                    relativePath = uri,
                    sourceId = image.folderId.toString(),
                )
            } }
        PlaybackCatalog(legacy, null)
    }

    /** Commits last-shown only after the decoded image has passed the playback request gate. */
    suspend fun commitImageShown(uri: Uri) = withContext(Dispatchers.IO) {
        dao.updateLastShownByUri(uri, isoNow())
    }

    val currentImageUriFlow: Flow<Uri?> = if (target == WallpaperTarget.HOME) {
        dao.getLatestShownImageFlow().map { it?.uri }
    } else {
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == PlaybackSnapshotStore.SNAPSHOT_KEY) trySend(Unit)
            }
            playbackSnapshotStore.registerListener(listener)
            trySend(Unit)
            awaitClose { playbackSnapshotStore.unregisterListener(listener) }
        }.map { committedSnapshotUri() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    private fun committedSnapshotUri(): Uri? {
        val snapshot = playbackSnapshotStore.load() ?: return null
        return (snapshot.currentId ?: snapshot.historyIds.getOrNull(snapshot.historyCursor))
            ?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    suspend fun getFolderId(uri: Uri): Long? = withContext(Dispatchers.IO) {
        dao.getFolderId(uri)
    }

    fun getEnabledFoldersCountFlow(): Flow<Int> =
        dao.getEnabledFoldersCountFlow()

    fun getImagesForFolderFlow(folderId: Long): Flow<List<ImageEntry>> =
        dao.getImagesForFolderFlow(folderId)

    suspend fun updateImageLastShown(uri: Uri) = withContext(Dispatchers.IO) {
        dao.updateLastShownByUri(uri, isoNow())
    }

    suspend fun getCurrentImageName(uri: Uri?): String? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        try {
            currentCoroutineContext().ensureActive()
            appContext.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    suspend fun refreshEnabledFolders() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Refreshing all enabled folders")
        val allFolders = dao.getAllFoldersFlow().first()
        allFolders
            .filter { it.isEnabled }
            .forEach { folder ->
                scanAndIndex(folder.id, folder.uri)
            }
    }

    fun getFolderDisplayName(uri: Uri): String {
        if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
            return "Favorites"
        }
        if (SharedFolderResolver.isDefaultSharedUri(uri)) {
            return "Shared"
        }

        return runCatching {
            DocumentFile.fromTreeUri(appContext, uri)?.name
        }.getOrNull() ?: uri.toString()
    }

    fun formatTreeUriPath(uri: Uri): String {
        return try {
            if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
                return FavoritesFolderResolver.getDefaultDisplayPath()
            }
            if (SharedFolderResolver.isDefaultSharedUri(uri)) {
                return SharedFolderResolver.getDefaultDisplayPath()
            }
            if (uri.scheme == "file") {
                return uri.path ?: uri.toString()
            }

            // For custom schemes or invalid tree URIs, getTreeDocumentId might throw
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return uri.toString()

            val colonIndex = documentId.indexOf(':')
            val storage = if (colonIndex < 0) documentId else documentId.substring(0, colonIndex)
            val rawPath = if (colonIndex < 0) "" else documentId.substring(colonIndex + 1)
            val storageLabel = when (storage.lowercase()) {
                "primary" -> "sdcard"
                else -> storage
            }
            if (rawPath.isBlank()) storageLabel else {
                val decodedPath = Uri.decode(rawPath).trimStart('/')
                "$storageLabel/$decodedPath"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to format display path: ${e.javaClass.simpleName}")
            uri.toString()
        }
    }

    fun isImageUriValid(uri: Uri): Boolean = uri.isValidImage(appContext)

    suspend fun isImageManagedAndEnabled(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        dao.isImageManagedAndEnabled(uri)
    }
}
