package dev.abdus.apps.shimmer.p1

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.view.Gravity
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.p2.SourcePreviewOverrides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * P1 source/data probe. This screen intentionally uses a small platform view hierarchy so the
 * source model, scan state, cancellation and masked diagnostic export remain observable without
 * coupling the P1 work to the wallpaper, gesture or GL screens.
 */
class P1SourcesActivity : ComponentActivity() {
    private lateinit var sourceList: LinearLayout
    private lateinit var progress: TextView
    private lateinit var output: TextView
    private var recursive = true
    private var operation: Job? = null
    private var operationGeneration = 0L
    private var pendingReauthorization: PendingReauthorization? = null
    private var pendingPreviewSource: Pair<String, String>? = null
    private val previewViews = mutableMapOf<String, ImageView>()
    /** Explicit expand choice per directory level, keyed by source and relative path. */
    private val directoryExpanded = mutableMapOf<String, Boolean>()
    /** Per-source view of `source_directory_exclusions`, kept so a toggle redraws without a query. */
    private val directoryExclusions = mutableMapOf<String, MutableSet<String>>()
    private val directoryTrees = mutableMapOf<String, DirectoryTreeNode<P1ImageRecordEntity>>()
    private val directoryContainers = mutableMapOf<String, LinearLayout>()
    /** Latest source row per directory source; its root level follows the source enabled flag. */
    private val directorySources = mutableMapOf<String, P1SourceEntity>()
    private val previewOverrides by lazy {
        // P1 and P2 use the same preference namespace so a custom source preview is visible
        // from either control surface without changing the playback/database contract.
        // P9-PREVIEW-EDIT-01：读写统一经共享存储，新 UI 图库页与播放页也读同一份
        SourcePreviewOverrides(this)
    }

    companion object {
        private const val REQUEST_SOURCE_PREVIEW = 1201
    }

    private val directoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val pending = pendingReauthorization
        pendingReauthorization = null
        if (uri == null) {
            if (pending != null) output.text = "已取消重新授权；原来源和已提交图像保留。"
            return@registerForActivityResult
        }
        persistReadPermission(uri)
        if (pending != null) {
            reauthorize(pending.sourceId, listOf(uri))
        } else {
            importDirectory(uri)
        }
    }

    private val singlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val pending = pendingReauthorization
        pendingReauthorization = null
        if (uri == null) {
            if (pending != null) output.text = "已取消重新授权；原来源和已提交图像保留。"
            return@registerForActivityResult
        }
        persistReadPermission(uri)
        if (pending != null) {
            reauthorize(pending.sourceId, listOf(uri))
        } else {
            runImport("单张图片导入") { onProgress ->
                repository.importSingleDocument(uri, onProgress = onProgress)
            }
        }
    }

    private val multiplePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val pending = pendingReauthorization
        pendingReauthorization = null
        val selected = uris.distinct()
        if (selected.isEmpty()) {
            if (pending != null) output.text = "已取消重新授权；原来源和已提交图像保留。"
            return@registerForActivityResult
        }
        selected.forEach(::persistReadPermission)
        if (pending != null) {
            reauthorize(pending.sourceId, selected)
        } else {
            // P9-MULTI-GROUP-01：一次多选成为一组，与新 UI 图库页同一条导入路径
            runImport("多张图片导入") { onProgress ->
                repository.importImageBatch(selected, onProgress)
            }
        }
    }

    private val exportSnapshot = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // Export observes the current state; it must never replace or cancel an active scan.
        // Keep its lifecycle-bound job independent from operation and operationGeneration.
        lifecycleScope.launch {
            try {
                val snapshot = repository.diagnosticSnapshot()
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openOutputStream(uri, "w")
                        ?: error("无法写入导出文件")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                }
                output.text = "P1 数据快照已导出；URI 已在快照中脱敏。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                output.text = "导出 P1 数据快照失败（${failed.javaClass.simpleName}）；当前扫描继续，已提交配置保留。"
            }
        }
    }

    private lateinit var repository: P1SourceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recursive = savedInstanceState?.getBoolean("recursive", true) ?: true
        pendingReauthorization = savedInstanceState?.getString("reauthorizationSourceId")
            ?.let(::PendingReauthorization)
        repository = P1SourceRepository(this)
        buildScreen()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.sources.catch { failed ->
                    output.text = "来源列表读取失败（${failed.javaClass.simpleName}），请保留现有数据。"
                }.collectLatest { renderSources(it) }
            }
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply { addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)

        root.addView(label("P1 来源与数据", 23f))
        root.addView(label(
            "此页管理来源、扫描和数据提交。来源路径会自动缩略，并显示文件名；" +
                "取消、撤权或临时读取失败不会删除已提交图像。",
        ))
        root.addView(label(
            "单图/多图无法取得持久读权限时会尝试应用内副本并显示 APP_COPY；" +
                "目录来源需要重新授权，不会静默复制整个目录。",
        ))

        val recursiveToggle = CheckBox(this).apply {
            text = "目录包含下级子目录（默认开启）"
            isChecked = recursive
            setOnCheckedChangeListener { _, checked -> recursive = checked }
        }
        root.addView(recursiveToggle)

        root.addView(button("选择单张图片") { singlePicker.launch(arrayOf("image/*")) })
        root.addView(button("选择多张图片") { multiplePicker.launch(arrayOf("image/*")) })
        root.addView(button("选择目录并扫描") { directoryPicker.launch(null) })
        root.addView(button("导出 P1 数据快照（URI 脱敏）") {
            exportSnapshot.launch("P1-source-snapshot.json")
        })
        root.addView(button("取消当前扫描") {
            if (operation?.isActive == true) {
                operation?.cancel()
                output.text = "已请求取消；已提交来源和图像配置保留。"
            } else output.text = "当前没有运行中的操作。"
        })
        progress = label("当前没有运行中的扫描")
        root.addView(progress)
        output = label("选择来源后，下面显示扫描状态和关联图像数量。")
        root.addView(output)
        root.addView(label("来源列表", 18f))
        sourceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sourceList)
    }

    private fun renderSources(sources: List<P1SourceEntity>) {
        sourceList.removeAllViews()
        previewViews.clear()
        // Views are rebuilt on every emission; the expand choices deliberately survive so a
        // status change elsewhere does not collapse the folder the user is working in.
        directoryTrees.clear()
        directoryContainers.clear()
        directorySources.clear()
        if (sources.isEmpty()) {
            sourceList.addView(label("暂无 P1 来源。"))
            return
        }
        sourceList.addView(label("来源总数：${sources.size}；下列数量为各来源保留的图像关联。"))
        sources.forEach { source ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            card.addView(label("${kindLabel(source.kind)} · 来源 ${maskedId(source.id)}"))
            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "来源预览图"
            }
            previewViews[source.id] = preview
            card.addView(preview)
            card.addView(label("来源位置（可读映射）：${SourcePathFormatter.shorten(source.rootUri)}"))
            card.addView(label("状态：${source.scanState}；权限：${source.permissionState}"))
            if (source.permissionState == "APP_COPY") {
                card.addView(label("此来源使用应用内副本，未修改原图；原文档的持久权限不可用。"))
            }
            card.addView(label("递归：${if (source.includeSubdirectories) "开启" else "关闭"}；启用：${source.enabled}"))
            val count = label("关联图像数量：读取中…")
            card.addView(count)
            val directorySummary = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(2), 0, dp(2))
            }
            card.addView(directorySummary)
            if (source.kind == P1SourceKind.DIRECTORY_TREE.name) {
                directoryContainers[source.id] = directorySummary
                directorySources[source.id] = source
                card.addView(label(
                    "目录逐层折叠：点击文件夹按钮展开一层。来源文件夹本身由下方“启用”开关控制；" +
                        "子目录右侧停用/启用只改这一层及其下级，不会删除图像；上级停用时下级显示“由上级控制”。" +
                        "停用后需在 P2 重新载入来源。",
                    12f,
                ))
            }
            if (!source.lastError.isNullOrBlank()) {
                card.addView(label("最近错误：${source.lastError}"))
            }

            // Five buttons plus a weighted switch squeezed the switch to a sliver on a phone
            // width. Give the switch its own full-width row and wrap the actions in two rows.
            val enabled = Switch(this).apply {
                text = "启用"
                isChecked = source.enabled
                setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch {
                        try {
                            repository.setEnabled(source.id, checked)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failed: Exception) {
                            output.text = "更新启用状态失败（${failed.javaClass.simpleName}），请刷新并核对。"
                        }
                    }
                }
            }
            card.addView(enabled, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            card.addView(actionRow(
                button("重扫") { rescan(source.id) },
                button("重新授权") { startReauthorization(source) },
            ))
            card.addView(actionRow(
                button("选择预览图") { choosePreview(source.kind, source.id) },
                button("删除来源") { confirmDeleteSource(source) },
            ))
            card.addView(label(
                "重新授权会沿用同一来源 ID；若选择不同根目录，旧图像仍保留，" +
                    "请在快照中核对新旧 root 的脱敏 ID。",
            ))
            sourceList.addView(card)

            lifecycleScope.launch {
                try {
                    val imageRecords = repository.sourceImages(source.id)
                    val imageNames = imageRecords.map { it.displayName.ifBlank { it.relativePath } }
                    val imageCount = imageRecords.size
                    val customPreview = previewOverrides.get(source.kind, source.id)
                    val previewUri = customPreview ?: imageRecords.firstOrNull()?.let {
                        chooseSourcePreview(source.kind, imageRecords).uri
                    }
                    previewUri?.let { PreviewThumbnailLoader.load(lifecycleScope, preview, it, dp(72)) }
                    count.text = buildString {
                        append("关联图像数量：$imageCount")
                        if (imageNames.isNotEmpty()) {
                            append("\n文件：")
                            append(imageNames.take(3).joinToString("、"))
                            if (imageNames.size > 3) append(" 等")
                        }
                    }
                    if (source.kind == P1SourceKind.DIRECTORY_TREE.name) {
                        // A large library makes both the tree and the exclusion read too heavy for
                        // the UI thread; only the finished row set is added here.
                        val tree = withContext(Dispatchers.Default) {
                            buildDirectoryTree(imageRecords) { it.relativePath }
                        }
                        directoryTrees[source.id] = tree
                        directoryExclusions[source.id] = repository.excludedDirectories(source.id).toMutableSet()
                        refreshDirectoryRows(source.id)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    count.text = "关联图像数量：暂时不可读"
                }
            }
        }
    }

    /** Redraws one source's folder rows. Stopping a folder does not change the sources table. */
    private fun refreshDirectoryRows(sourceId: String) {
        val container = directoryContainers[sourceId] ?: return
        val tree = directoryTrees[sourceId] ?: return
        val source = directorySources[sourceId] ?: return
        val excluded = directoryExclusions[sourceId].orEmpty()
        container.removeAllViews()
        // The source's own folder is the outermost level, so adding `1/` lists only `1/`.
        addDirectoryRow(container, source, tree, excluded, inheritedStop = false)
    }

    /**
     * One row per folder: a full-width expand button plus its own stop/resume control.
     *
     * A collapsed level adds no descendant rows at all, which is what keeps a library of tens of
     * thousands of images from building every row on open. The root level is the source itself:
     * its state follows the source enabled switch and it has no control of its own.
     */
    private fun addDirectoryRow(
        container: LinearLayout,
        source: P1SourceEntity,
        node: DirectoryTreeNode<P1ImageRecordEntity>,
        excluded: Set<String>,
        inheritedStop: Boolean,
    ) {
        val isRoot = node.depth == 0
        val key = directoryKey(source.id, node.path)
        val expanded = directoryExpanded[key] ?: false
        val stopped = if (isRoot) !source.enabled else node.path in excluded
        val name = if (isRoot) SourcePathFormatter.folderName(source.rootUri) ?: DIRECTORY_TREE_ROOT_NAME else node.name
        val indent = dp(4 + 14 * node.depth)
        val state = when {
            // A child's own stop is kept, and applies again once the parent is resumed.
            inheritedStop && stopped -> "受上级停用影响（本层也已停用）"
            inheritedStop -> "受上级停用影响"
            isRoot && stopped -> "来源已停用"
            stopped -> "已停用"
            else -> "播放中"
        }
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(indent, 0, 0, 0)
            addView(
                // A real button, not a tappable label: a selectable TextView consumes the first
                // tap for its own cursor, which made opening a folder take two taps.
                button("${if (expanded) "▼" else "▶"} $name/ · 共 ${node.totalCount} 张 · $state") {
                    directoryExpanded[key] = !expanded
                    refreshDirectoryRows(source.id)
                }.apply {
                    textSize = 13f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    minimumHeight = dp(44)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            // A disabled button still looked tappable on the device, so a level that cannot be
            // toggled here shows who controls it instead of a greyed-out button.
            when {
                inheritedStop -> addView(controlNote("由上级控制"))
                isRoot -> addView(controlNote("由来源开关控制"))
                else -> addView(button(if (stopped) "启用" else "停用") {
                    setDirectoryStopped(source.id, node.path, !stopped)
                }.apply { textSize = 13f })
            }
        })
        if (!expanded) return
        if (node.directItems.isNotEmpty()) {
            container.addView(label(
                "本层 ${node.directItems.size} 张：${directoryFileSummary(node.directItems)}",
                12f,
            ).apply { setPadding(indent + dp(14), 0, 0, 0) })
        }
        if (isRoot && node.children.isEmpty() && node.directItems.isEmpty()) {
            container.addView(label("暂无可显示的图片。", 12f).apply { setPadding(indent + dp(14), 0, 0, 0) })
        }
        node.children.forEach { child ->
            addDirectoryRow(container, source, child, excluded, inheritedStop || stopped)
        }
    }

    /** Non-interactive text in the place of a folder's stop button. */
    private fun controlNote(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun setDirectoryStopped(sourceId: String, path: String, stop: Boolean) {
        lifecycleScope.launch {
            try {
                repository.setDirectoryEnabled(sourceId, path, enabled = !stop)
                val excluded = directoryExclusions.getOrPut(sourceId) { mutableSetOf() }
                if (stop) excluded.add(path) else excluded.remove(path)
                refreshDirectoryRows(sourceId)
                output.text = if (stop) {
                    "已停用子目录“$path”及其下级；图像记录保留，请在 P2 重新载入来源后生效。"
                } else {
                    "已启用子目录“$path”；请在 P2 重新载入来源后生效。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                output.text = "更新子目录状态失败（${failed.javaClass.simpleName}）；已有来源和图像保留。"
            }
        }
    }

    private fun directoryKey(sourceId: String, path: String): String = "$sourceId|$path"

    private fun choosePreview(kind: String, sourceId: String) {
        pendingPreviewSource = kind to sourceId
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            REQUEST_SOURCE_PREVIEW,
        )
    }

    @Deprecated("Activity Result API is used for source import; this legacy callback handles the optional preview picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SOURCE_PREVIEW || resultCode != RESULT_OK) return
        val (kind, sourceId) = pendingPreviewSource ?: return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        previewOverrides.set(kind, sourceId, uri.toString())
        previewViews[sourceId]?.let { PreviewThumbnailLoader.load(lifecycleScope, it, uri.toString(), dp(72)) }
        output.text = "已更新来源预览图；不改变来源及已提交图像配置。"
        pendingPreviewSource = null
    }

    private fun confirmDeleteSource(source: P1SourceEntity) {
        AlertDialog.Builder(this)
            .setTitle("删除此来源？")
            .setMessage(
                "将移除${kindLabel(source.kind)}“${SourcePathFormatter.shorten(source.rootUri)}”及其关联。" +
                    "共享图像记录会保留，应用内副本文件不会立即物理删除。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        if (repository.deleteSource(source.id)) {
                            output.text = "来源已删除；共享图像记录保留。"
                        } else {
                            output.text = "来源已不存在，未修改其他图像记录。"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failed: Exception) {
                        output.text = "删除来源失败（${failed.javaClass.simpleName}）；已有配置保留。"
                    }
                }
            }
            .show()
    }

    private fun importDirectory(uri: Uri) {
        runImport("目录扫描") { onProgress ->
            repository.importDirectory(uri, recursive, onProgress)
        }
    }

    private fun rescan(sourceId: String) {
        runImport("来源重扫") { onProgress ->
            repository.rescan(sourceId, onProgress)
        }
    }

    private fun reauthorize(sourceId: String, uris: List<Uri>) {
        runImport("重新授权") { onProgress ->
            repository.reauthorize(sourceId, uris, onProgress)
        }
    }

    private fun startReauthorization(source: P1SourceEntity) {
        pendingReauthorization = PendingReauthorization(source.id)
        when (source.kind) {
            P1SourceKind.DIRECTORY_TREE.name -> directoryPicker.launch(null)
            P1SourceKind.SINGLE_DOCUMENT.name -> singlePicker.launch(arrayOf("image/*"))
            else -> multiplePicker.launch(arrayOf("image/*"))
        }
    }

    private fun persistReadPermission(uri: Uri) {
        P1SourceIntents.persistReadPermission(this, uri)
    }

    private fun runImport(label: String, work: suspend ((P1ScanProgress) -> Unit) -> P1ImportResult) {
        runOperation(label) {
            val generation = operationGeneration
            val result = work { value -> showProgress(value, generation) }
            if (generation == operationGeneration) {
                val skipped = if (result.skippedImageCount > 0) "；未能加入 ${result.skippedImageCount} 张" else ""
                output.text = "$label：${result.state}\n来源 ${maskedId(result.sourceId)}；" +
                    "本次提交图像数 ${result.committedImageCount}$skipped。已有总数见来源卡片。"
            }
        }
    }

    private fun runOperation(label: String, work: suspend () -> Unit) {
        operation?.cancel()
        val generation = ++operationGeneration
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            progress.text = "$label：准备中…"
            try {
                work()
                if (generation == operationGeneration) progress.text = "$label：操作结束，请核对来源状态"
            } catch (_: CancellationException) {
                if (generation == operationGeneration) progress.text = "$label：已取消；已提交配置保留"
            } catch (failed: Exception) {
                if (generation == operationGeneration) {
                    progress.text = "$label：失败（${failed.javaClass.simpleName}）；已有配置保留"
                }
            } finally {
                if (operation === coroutineContext[Job]) operation = null
            }
        }
        operation = job
        job.start()
    }

    private fun showProgress(value: P1ScanProgress, generation: Long) {
        runOnUiThread {
            if (generation == operationGeneration && !isFinishing && !isDestroyed) {
                val stage = when (value.phase) {
                    P1ScanPhase.ENUMERATION -> "枚举目录：访问 ${value.visitedNodes} 项，发现 ${value.discoveredImages} 张"
                    P1ScanPhase.METADATA -> "校验图片：${value.processedImages}/${value.totalImages} 张"
                    P1ScanPhase.COMMIT -> "提交关联：${value.totalImages} 张"
                }
                progress.text = "$stage；已用 ${value.elapsedMillis / 1000} 秒"
            }
        }
    }

    private fun maskedId(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8))
        .take(6).joinToString("") { "%02x".format(it) }

    private fun kindLabel(kind: String): String = when (kind) {
        P1SourceKind.SINGLE_DOCUMENT.name -> "单张图片"
        P1SourceKind.MULTIPLE_DOCUMENTS.name -> "多张图片"
        P1SourceKind.DIRECTORY_TREE.name -> "文件夹目录"
        else -> kind
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("recursive", recursive)
        pendingReauthorization?.let { outState.putString("reauthorizationSourceId", it.sourceId) }
        super.onSaveInstanceState(outState)
    }

    private fun label(value: String, size: Float = 14f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextIsSelectable(true)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun actionRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PendingReauthorization(val sourceId: String)
}
