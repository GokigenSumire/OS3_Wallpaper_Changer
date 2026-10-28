package dev.abdus.apps.shimmer.p2

import android.os.Bundle
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperUtil
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p0.P0DiagnosticsActivity
import dev.abdus.apps.shimmer.p1.DIRECTORY_TREE_ROOT_NAME
import dev.abdus.apps.shimmer.p1.DirectoryTreeNode
import dev.abdus.apps.shimmer.p1.P1SourcesActivity
import dev.abdus.apps.shimmer.p1.SourcePathFormatter
import dev.abdus.apps.shimmer.p1.PreviewThumbnailLoader
import dev.abdus.apps.shimmer.p1.buildDirectoryTree
import dev.abdus.apps.shimmer.p1.directorySegments
import dev.abdus.apps.shimmer.p3.DefaultFitChoice
import dev.abdus.apps.shimmer.p3.P3TestImages
import dev.abdus.apps.shimmer.p4.P4ScheduleActivity
import dev.abdus.apps.shimmer.p5.P5TransitionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * P2 control surface. It makes the queue and its test setup reachable from the installed APK;
 * playback itself still runs in ShimmerWallpaperService after the user sets the wallpaper.
 */
class P2PlaybackActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var repository: ImageFolderRepository
    private lateinit var coordinator: PlaybackCoordinator
    private lateinit var snapshotStore: PlaybackSnapshotStore
    private lateinit var preferences: WallpaperPreferences
    private var items: List<PlaybackItem> = emptyList()
    private var refreshGeneration = 0L
    /** Explicit user choice per directory level; absent keys stay collapsed. */
    private val subgroupExpanded = mutableMapOf<String, Boolean>()
    /** Rows created per expanded directory level; thousands of rows froze the page. */
    private val subgroupVisibleRows = mutableMapOf<String, Int>()
    private var movedItemId: String? = null
    private var pendingPreviewGroup: GroupKey? = null
    private var pickerLaunchedAt = 0L
    /** Coordinator and snapshot work run off the main thread, one change at a time. */
    private val playbackMutex = Mutex()
    private var viewState = ViewState()

    /** Main-thread copy of the coordinator state rendered by the list. */
    private data class ViewState(
        val ordered: List<PlaybackItem> = emptyList(),
        val order: PlaybackOrder = PlaybackOrder.NATURAL_ASC,
        val currentName: String? = null,
        /**
         * Play position per image, 1-based. The list groups by folder, which is not the play
         * order under a random round, so every row carries its own position.
         */
        val positions: Map<String, Int> = emptyMap(),
        /** Grouping and the directory trees, prepared off the main thread. */
        val blocks: List<RenderBlock> = emptyList(),
        val groupCount: Int = 0,
    ) {
        /** Everything else is derived from these three, so it stays out of the comparison. */
        fun rendersSameAs(other: ViewState): Boolean =
            order == other.order && currentName == other.currentName && ordered == other.ordered
    }

    /** One contiguous run of the play order, or one whole source when runs are not meaningful. */
    private class Segment(val group: GroupKey, val items: List<PlaybackItem>)

    /**
     * One header and the rows below it.
     *
     * Building these (grouping, per-level directory trees) for tens of thousands of images costs
     * far too much for the UI thread, and an expand or collapse re-renders the list, so they are
     * built once per state change on a worker thread.
     */
    private class RenderBlock(
        val group: GroupKey,
        /** 1-based position of the source, shared by every block of a split source. */
        val groupIndex: Int,
        val items: List<PlaybackItem>,
        /** Images of the whole source, which a split source spreads over several blocks. */
        val groupSize: Int,
        val continued: Boolean,
        val previewItem: PlaybackItem,
        /** Per-level directory tree, or null when the source has no directory structure. */
        val tree: DirectoryTreeNode<PlaybackItem>?,
    )

    // P9-PREVIEW-EDIT-01：与 P1 页、新 UI 共用同一份自选预览，任一入口最后一次选择生效
    private val previewOverrides by lazy { SourcePreviewOverrides(this) }

    companion object {
        private const val REQUEST_PREVIEW_IMAGE = 1202
        private const val SILENT_RETURN_MILLIS = 1_500L
        private const val ROW_PAGE_SIZE = 50
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = WallpaperPreferences.create(this)
        // P9-STATIC-01：本页过去每次 onCreate 都无条件写入 true，会冲掉用户在新 UI 设置页
        // 的选择（版本 16 起即如此，版本 20 测试卡曾作为已知边界记录）。改为只在该偏好从未
        // 被写过时落一次默认值 true，保留“首次进入 P2 验收页为确定性画面”的原意；此后由用户
        // 在任一侧的开关决定，新旧界面共读同一个键，不再互相覆盖。
        if (!preferences.hasP2StaticTestModeChoice()) {
            preferences.setP2StaticTestMode(true)
        }
        repository = ImageFolderRepository(this, lifecycleScope)
        coordinator = PlaybackCoordinator(randomSeed = 0x504C41594241434BL)
        snapshotStore = PlaybackSnapshotStore(this)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        // onResume follows onCreate, so this is the single load on open and the refresh after
        // returning from P1 or the system picker.
        if (::list.isInitialized) refreshItems()
        val launchedAgo = System.currentTimeMillis() - pickerLaunchedAt
        if (pickerLaunchedAt > 0 && launchedAgo < SILENT_RETURN_MILLIS && !WallpaperUtil.isActiveWallpaper(this)) {
            // Some vendor pages accept the intent and return immediately without a picker.
            status.text = "系统页面似乎未打开；请从下面的入口中选择其他方式。"
            showWallpaperRoutes()
        }
        pickerLaunchedAt = 0
    }

    private fun buildScreen() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)

        column.addView(label("P2 播放控制", 24f))
        column.addView(label(
            "这里是 P2 的唯一操作入口：先准备图片来源，再选择顺序并保存。" +
                "点击设置 P2 壁纸后，桌面上的双击切下一张、三击回上一张才会使用 P2 播放状态。",
        ))
        column.addView(button("打开 P1 来源与数据") {
            startActivity(android.content.Intent(this, P1SourcesActivity::class.java))
        })
        column.addView(button("打开 P0 诊断（仅探针，不是 P2 播放）") {
            startActivity(android.content.Intent(this, P0DiagnosticsActivity::class.java))
        })
        column.addView(button("打开 P4 调度（定时、锁屏切换与日期规则）") {
            startActivity(Intent(this, P4ScheduleActivity::class.java))
        })
        column.addView(button("打开 P5 过渡效果（效果、时长与方向）") {
            startActivity(Intent(this, P5TransitionActivity::class.java))
        })
        // Single toggle button for static acceptance mode with visual state
        val staticModeButton = button("") {}
        column.addView(staticModeButton)
        updateStaticModeButton(staticModeButton)
        column.addView(button("设置为 P2 动态壁纸") { openP2Wallpaper() })
        column.addView(button("系统页面未打开？选择其他入口") { showWallpaperRoutes() })
        addP3Section(column)
        column.addView(button("重新载入已添加且启用的来源") { refreshItems() })
        column.addView(button("清空当前播放历史（不删除来源）") { confirmClearHistory() })

        column.addView(label("选择播放顺序", 18f))
        column.addView(button("自然升序（2.jpg 在 10.jpg 前）") { setOrder(PlaybackOrder.NATURAL_ASC) })
        column.addView(button("自然降序") { setOrder(PlaybackOrder.NATURAL_DESC) })
        column.addView(button("随机轮次（本轮不重复）") { setOrder(PlaybackOrder.RANDOM) })
        column.addView(button("手动顺序（使用下方上移/下移）") { setOrder(PlaybackOrder.MANUAL) })

        status = label("正在读取已启用图片…")
        column.addView(status)
        column.addView(label("当前顺序与手动调整", 18f))
        column.addView(label("单项可跨组移动；同来源拆开后标为续段。整组移动会聚合该来源，移到相邻来源全部图片之前或之后，其他图片相对顺序保持。"))
        column.addView(label("列表按文件夹逐层折叠：点击“子目录”按钮展开一层，再点击其中的子文件夹继续展开。行首序号是该图片的播放位置，随机顺序下与列表先后不同。"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)

    }

    private fun openP2Wallpaper() {
        val open = {
            val result = WallpaperUtil.openWallpaperPicker(this)
            // HyperOS was seen accepting the direct preview intent and returning at once.
            // A return within a moment without P2 active offers the other routes directly.
            if (result == WallpaperUtil.PickerLaunchResult.DIRECT_PREVIEW) {
                pickerLaunchedAt = System.currentTimeMillis()
            }
            showPickerResult(result)
        }
        if (WallpaperUtil.isActiveWallpaper(this)) {
            open()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("需要设置 P2 动态壁纸")
            .setMessage("将请求打开系统动态壁纸预览，请在系统页面选择 P2 并应用。若未跳转或系统另要求授权，可点击“其他入口”进入系统选择器或本应用权限设置。")
            .setNegativeButton("取消", null)
            .setNeutralButton("其他入口") { _, _ -> showWallpaperRoutes() }
            .setPositiveButton("打开系统页面") { _, _ -> open() }
            .show()
    }

    /** 与新播放页共用清历史逻辑；危险的重置排序选项每次打开都默认不勾选。 */
    private fun confirmClearHistory() {
        var resetManualOrder = false
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label("清空旧历史并重置随机轮次。默认保留当前顺序、手动排序（含停用图片的位置）与当前画面，不再回退到清空前的图片。图片来源和每张图片的调整不受影响。"))
            addView(android.widget.CheckBox(this@P2PlaybackActivity).apply {
                text = "同时重置手动排序"
                isChecked = false
                setOnCheckedChangeListener { _, checked -> resetManualOrder = checked }
            })
            addView(label("勾选后清除保存的手动位置并切换为名称升序；当前画面仍保留，不自动换图。"))
        }
        AlertDialog.Builder(this)
            .setTitle("清空播放历史？")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                runPlayback(
                    work = { coordinator.restore(snapshotStore.clearHistory(resetManualOrder)) },
                    done = {
                        movedItemId = null
                        renderItems(scrollToFocus = false)
                        status.text = if (resetManualOrder) {
                            "已清空播放历史并重置为名称升序；当前画面与图片来源保留。"
                        } else {
                            "已清空播放历史；顺序、手动位置、当前画面与图片来源保留。"
                        }
                    },
                )
            }
            .show()
    }

    private fun showWallpaperRoutes() {
        AlertDialog.Builder(this)
            .setTitle("选择系统入口")
            .setItems(arrayOf("系统动态壁纸选择器", "本应用设置（查看系统权限）")) { _, which ->
                if (which == 0) showPickerResult(WallpaperUtil.openSystemWallpaperChooser(this))
                else status.text = if (WallpaperUtil.openAppDetails(this)) {
                    "已请求打开本应用设置；如系统提供“动态壁纸服务”选项，请手动允许后返回重试。"
                } else "无法打开本应用设置，请保留系统提示并反馈。"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPickerResult(result: WallpaperUtil.PickerLaunchResult) {
        status.text = when (result) {
            WallpaperUtil.PickerLaunchResult.DIRECT_PREVIEW -> "已请求打开 P2 预览；若未跳转，请使用“选择其他入口”。"
            WallpaperUtil.PickerLaunchResult.LIVE_WALLPAPER_CHOOSER -> "已请求打开系统动态壁纸选择器，请选择 P2。"
            WallpaperUtil.PickerLaunchResult.WALLPAPER_SETTINGS -> "已请求打开系统通用壁纸入口，请选择动态壁纸。"
            WallpaperUtil.PickerLaunchResult.FAILED -> "系统拒绝或未提供这些入口，请使用“选择其他入口”查看本应用权限设置并反馈系统提示。"
        }
        if (result == WallpaperUtil.PickerLaunchResult.FAILED) showWallpaperRoutes()
    }

    private fun refreshItems() {
        val generation = ++refreshGeneration
        lifecycleScope.launch {
            // Keep the rendered list while re-reading on resume; only the first load is blank.
            if (viewState.ordered.isEmpty()) status.text = "正在读取图片来源…"
            val result = runCatching {
                // The same effective catalog the wallpaper plays: a matching P4 rule replaces it.
                val catalog = repository.getPlaybackCatalog()
                if (generation != refreshGeneration) return@launch
                Triple(computeState { updatePlayback(catalog.items) }, catalog.items, catalog.activeRule)
            }
            result.exceptionOrNull()?.let { failed ->
                if (failed is CancellationException) throw failed
                status.text = "读取图片来源失败（${failed.javaClass.simpleName}）；请保留已有数据并重试。"
                return@launch
            }
            val (state, refreshed, rule) = result.getOrThrow()
            items = refreshed
            val unchanged = state.rendersSameAs(viewState) && list.childCount > 0
            viewState = state
            if (!unchanged) renderItems()
            val current = state.currentName ?: "尚未提交图片"
            val mode = if (preferences.isP2StaticTestMode()) "静态验收模式" else "原有特效"
            val source = rule?.let { "P4 规则“${it.displayName}”生效" } ?: "普通目录"
            status.text = "已启用图片：${refreshed.size}（$source）；顺序：${state.order.name}；当前：$current；$mode"
        }
    }

    private fun setOrder(order: PlaybackOrder) {
        if (items.isEmpty()) {
            status.text = "当前没有已启用图片；先从 P1 来源与数据添加图片。"
            return
        }
        runPlayback(
            busy = "正在保存顺序…",
            work = { updatePlayback(items) { setOrder(order) } },
        ) {
            renderItems()
            status.text = "已保存顺序：${order.name}；请点击设置为 P2 动态壁纸后执行桌面测试。"
        }
    }

    private fun moveItem(id: String, delta: Int) {
        var movement: String? = null
        runPlayback(
            work = {
                updatePlayback(items) {
                    val ids = orderedItems().map { it.id }.toMutableList()
                    val from = ids.indexOf(id)
                    val to = from + delta
                    if (from >= 0 && to in ids.indices) {
                        ids[from] = ids[to]
                        ids[to] = id
                        reorder(ids)
                        movement = "已将第 ${from + 1} 张移到第 ${to + 1} 张"
                    }
                }
            },
        ) {
            if (movement != null) movedItemId = id
            renderItems(focusItemId = id)
            movement?.let { status.text = it; Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Runs a coordinator change off the main thread. Sorting, reordering and encoding a
     * snapshot of thousands of IDs froze the page when they ran on the UI thread.
     */
    private fun runPlayback(busy: String? = null, work: () -> Unit, done: () -> Unit) {
        lifecycleScope.launch {
            busy?.let { status.text = it }
            val state = try {
                computeState(work)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                status.text = "操作失败（${failed.javaClass.simpleName}）；已保留已有顺序和历史。"
                return@launch
            }
            viewState = state
            done()
        }
    }

    private suspend fun computeState(work: () -> Unit): ViewState = playbackMutex.withLock {
        withContext(Dispatchers.Default) {
            work()
            val ordered = coordinator.orderedItems()
            val order = coordinator.snapshot().order
            val blocks = renderBlocks(ordered, order)
            ViewState(
                ordered = ordered,
                order = order,
                currentName = coordinator.current()?.displayName,
                // Built here, off the main thread: a large library would otherwise index
                // thousands of IDs on the UI thread for every expand and collapse.
                positions = ordered.withIndex().associate { (index, item) -> item.id to index + 1 },
                blocks = blocks,
                groupCount = blocks.maxOfOrNull { it.groupIndex } ?: 0,
            )
        }
    }

    /** Groups the play order into headers and directory levels. Runs off the main thread. */
    private fun renderBlocks(ordered: List<PlaybackItem>, order: PlaybackOrder): List<RenderBlock> {
        val segments = playbackSegments(ordered, groupsBySegment(order))
        val groupPositions = segments.map { it.group }.distinct()
            .withIndex().associate { (position, key) -> key to position + 1 }
        val groupSizes = ordered.groupingBy { GroupKey(it.sourceKind, it.sourceId) }.eachCount()
        val groupModels = buildPlaybackGroups(ordered).associateBy { GroupKey(it.key.kind, it.key.sourceId) }
        val seenGroups = mutableSetOf<GroupKey>()
        return segments.map { segment ->
            val group = segment.group
            val model = groupModels[group]
            val groupSize = groupSizes[group] ?: segment.items.size
            RenderBlock(
                group = group,
                groupIndex = groupPositions.getValue(group),
                items = segment.items,
                groupSize = groupSize,
                continued = !seenGroups.add(group),
                previewItem = model?.previewItem ?: segment.items.first(),
                tree = when {
                    group.kind != "DIRECTORY_TREE" -> null
                    // The whole source sits in this segment, so the group model's tree fits.
                    segment.items.size == groupSize -> model?.tree
                    else -> buildDirectoryTree(segment.items) { it.relativePath }
                },
            )
        }
    }

    private fun updatePlayback(catalog: List<PlaybackItem>, change: PlaybackCoordinator.() -> Unit = {}) {
        // The wallpaper can commit while this page is open. Apply only the requested catalog /
        // ordering change to the latest history, and persist newly discovered manual tail items.
        snapshotStore.update { latest ->
            coordinator.setItems(catalog)
            coordinator.restore(latest ?: PlaybackSnapshot())
            coordinator.change()
            coordinator.snapshot()
        }
    }

    private fun renderItems(focusGroup: GroupKey? = null, focusItemId: String? = null, scrollToFocus: Boolean = true) {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val manual = viewState.order == PlaybackOrder.MANUAL
        if (viewState.blocks.isEmpty()) {
            list.addView(label("暂无可显示的图片；请先在 P1 添加并启用图片来源。"))
            return
        }
        val positions = viewState.positions
        val groupCount = viewState.groupCount
        var focusView: android.view.View? = null
        viewState.blocks.forEachIndexed { blockIndex, block ->
            val group = block.group
            val header = groupHeader(
                group = group,
                groupIndex = block.groupIndex,
                groupCount = groupCount,
                itemCount = block.groupSize,
                sourcePath = SourcePathFormatter.shorten(
                    block.items.first().sourceRootUri.ifBlank { block.items.first().sourceId },
                ),
                continued = block.continued,
                highlighted = group == focusGroup,
                previewItem = block.previewItem,
                previewOverride = previewOverride(group),
            )
            if (manual) {
                header.addView(LinearLayout(this).apply {
                    addView(button("组上移") { moveGroup(group, -1) }.apply { isEnabled = block.groupIndex > 1 })
                    addView(button("组下移") { moveGroup(group, 1) }.apply { isEnabled = block.groupIndex < groupCount })
                })
            }
            list.addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(if (blockIndex == 0) 8 else 18) })
            if (group == focusGroup && focusView == null) focusView = header

            val tree = block.tree
            if (tree == null) {
                // Multi-document sources have no directory structure, but can still hold
                // thousands of images; page them the same way.
                val key = subgroupKey(group, "#$blockIndex")
                renderRows(block.items, key, group, manual, positions, focusItemId, focusGroup)
                    ?.let { row -> if (focusView == null) focusView = row }
            } else {
                focusItemId?.let { expandToItem(group, block.items, it) }
                val rootName = SourcePathFormatter.folderName(block.items.first().sourceRootUri)
                    ?: DIRECTORY_TREE_ROOT_NAME
                renderDirectoryLevel(tree, rootName, group, manual, positions, focusItemId, focusGroup)
                    ?.let { row -> if (focusView == null) focusView = row }
            }
        }
        if (scrollToFocus) focusView?.let { target -> list.post { scroll.smoothScrollTo(0, list.top + target.top) } }
    }

    /**
     * Manual order is the only order in which a source is deliberately split, so only there does
     * the list follow contiguous runs. A random round interleaves sources on nearly every
     * position; following runs there produced one group header per image.
     */
    private fun playbackSegments(ordered: List<PlaybackItem>, bySegment: Boolean): List<Segment> {
        if (!bySegment) {
            val grouped = LinkedHashMap<GroupKey, MutableList<PlaybackItem>>()
            ordered.forEach { item ->
                grouped.getOrPut(GroupKey(item.sourceKind, item.sourceId)) { mutableListOf() }.add(item)
            }
            return grouped.map { (group, items) -> Segment(group, items) }
        }
        val segments = mutableListOf<Segment>()
        var index = 0
        while (index < ordered.size) {
            val group = GroupKey(ordered[index].sourceKind, ordered[index].sourceId)
            val start = index
            while (index < ordered.size && GroupKey(ordered[index].sourceKind, ordered[index].sourceId) == group) index++
            segments += Segment(group, ordered.subList(start, index))
        }
        return segments
    }

    /**
     * Renders one directory level as a collapsed header; once opened, its own images come first,
     * then one collapsed header per child folder.
     *
     * The source's own folder is a level too: adding `1/` shows only `1/`, and its children appear
     * after tapping it. Returns the row of [focusItemId] when it was created.
     */
    private fun renderDirectoryLevel(
        node: DirectoryTreeNode<PlaybackItem>,
        rootName: String,
        group: GroupKey,
        manual: Boolean,
        positions: Map<String, Int>,
        focusItemId: String?,
        focusGroup: GroupKey?,
    ): android.view.View? {
        val key = subgroupKey(group, node.path)
        val expanded = isSubgroupExpanded(key)
        list.addView(directoryHeader(node, rootName, expanded) {
            subgroupExpanded[key] = !expanded
            renderItems(focusGroup = focusGroup, scrollToFocus = false)
        })
        // A collapsed level creates no rows at all; thousands of hidden rows froze the page.
        if (!expanded) return null
        var focusView = renderRows(node.directItems, key, group, manual, positions, focusItemId, focusGroup)
        node.children.forEach { child ->
            renderDirectoryLevel(child, rootName, group, manual, positions, focusItemId, focusGroup)
                ?.let { row -> if (focusView == null) focusView = row }
        }
        return focusView
    }

    /** Adds up to one page of image rows plus the button that reveals the next page. */
    private fun renderRows(
        rows: List<PlaybackItem>,
        key: String,
        group: GroupKey,
        manual: Boolean,
        positions: Map<String, Int>,
        focusItemId: String?,
        focusGroup: GroupKey?,
    ): android.view.View? {
        if (rows.isEmpty()) return null
        var focusView: android.view.View? = null
        val shown = minOf(rows.size, visibleRows(key))
        for (offset in 0 until shown) {
            val item = rows[offset]
            val row = itemRow(item, positions[item.id] ?: (offset + 1), group, manual)
            list.addView(row)
            if (item.id == focusItemId) focusView = row
        }
        if (shown < rows.size) {
            list.addView(moreRowsButton(rows.size - shown) {
                subgroupVisibleRows[key] = visibleRows(key) + ROW_PAGE_SIZE
                renderItems(focusGroup = focusGroup, scrollToFocus = false)
            })
        }
        return focusView
    }

    /** Opens every level above a moved image and pages its own level until the image is shown. */
    private fun expandToItem(group: GroupKey, items: List<PlaybackItem>, itemId: String) {
        val item = items.firstOrNull { it.id == itemId } ?: return
        // The source's own folder is the outermost level.
        subgroupExpanded[subgroupKey(group, "")] = true
        var path = ""
        directorySegments(item.relativePath).forEach { segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            subgroupExpanded[subgroupKey(group, path)] = true
        }
        val key = subgroupKey(group, path)
        val offset = items.filter { directoryPathOf(it) == path }.indexOfFirst { it.id == itemId }
        if (offset >= 0) subgroupVisibleRows[key] = maxOf(visibleRows(key), offset + 1)
    }

    private fun directoryPathOf(item: PlaybackItem): String =
        directorySegments(item.relativePath).joinToString("/")

    /** Every directory level starts collapsed; only an explicit tap opens one. */
    private fun isSubgroupExpanded(key: String): Boolean = subgroupExpanded[key] ?: false

    private fun visibleRows(key: String): Int = subgroupVisibleRows[key] ?: ROW_PAGE_SIZE

    private fun moreRowsButton(remaining: Int, onClick: () -> Unit): Button =
        button("显示后续 ${minOf(remaining, ROW_PAGE_SIZE)} 张（剩余 $remaining 张）") { onClick() }

    private fun itemRow(item: PlaybackItem, index: Int, group: GroupKey, manual: Boolean): LinearLayout {
        val highlighted = shouldHighlightMovedRow(item.id, movedItemId)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(if (highlighted) Color.rgb(255, 232, 170) else groupColor(group))
                setStroke(dp(if (highlighted) 3 else 0), if (highlighted) Color.rgb(180, 85, 0) else Color.TRANSPARENT)
                cornerRadius = dp(4).toFloat()
            }
            val location = listOf(item.relativePath, SourcePathFormatter.shorten(item.sourceRootUri))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            val name = label("$index. ${item.displayName}\n$location", selectable = false).apply {
                setTextColor(Color.rgb(25, 35, 50))
            }
            addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (highlighted) addView(label("刚移动", 12f, selectable = false).apply {
                setTextColor(Color.rgb(150, 75, 0))
            })
            if (manual) {
                addView(button("上移") { moveItem(item.id, -1) })
                addView(button("下移") { moveItem(item.id, 1) })
            }
        }
    }

    private fun groupHeader(
        group: GroupKey,
        groupIndex: Int,
        groupCount: Int,
        itemCount: Int,
        sourcePath: String,
        continued: Boolean,
        highlighted: Boolean,
        previewItem: PlaybackItem,
        previewOverride: String?,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(4), dp(6), dp(4))
        background = GradientDrawable().apply {
            setColor(groupColor(group))
            setStroke(dp(if (highlighted) 3 else 1), if (highlighted) Color.rgb(180, 85, 0) else Color.rgb(120, 145, 190))
            cornerRadius = dp(6).toFloat()
        }
        addView(ImageView(this@P2PlaybackActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "来源预览图"
            PreviewThumbnailLoader.load(lifecycleScope, this, previewOverride ?: previewItem.id, dp(72))
        })
        addView(label(
            "第 $groupIndex 组/共 $groupCount 组 · ${sourceKindLabel(group.kind)} · " +
                "$itemCount 张${if (continued) "（续段，同一来源）" else ""}${if (highlighted) " · 刚移动" else ""}\n$sourcePath",
            16f,
        ).apply { setTextColor(Color.rgb(25, 35, 50)) })
        addView(button(if (previewOverride == null) "选择预览图" else "更换预览图") {
            choosePreview(GroupKey(group.kind, group.sourceId))
        })
    }

    /**
     * A folder row is a real button.
     *
     * The previous header was a selectable TextView: the first tap only moved text selection into
     * it, so opening a folder took two taps and the tappable area was the text itself. A button
     * keeps a single tap, a full-width target and a visible control.
     */
    private fun directoryHeader(
        node: DirectoryTreeNode<PlaybackItem>,
        rootName: String,
        expanded: Boolean,
        onClick: () -> Unit,
    ): Button = button(
        "${if (expanded) "▼" else "▶"} " +
            (if (node.depth == 0) "来源目录：$rootName/" else "子目录：${node.name}/") +
            " · 共 ${node.totalCount} 张 · " +
            if (expanded) "点击折叠" else "点击展开",
    ) { onClick() }.apply {
        textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        // The source folder sits flush; each level below is indented one step further.
        setPadding(dp(12 + 14 * node.depth), dp(8), dp(12), dp(8))
    }

    private fun subgroupKey(group: GroupKey, path: String): String = "${group.kind}|${group.sourceId}|$path"

    private fun previewOverride(group: GroupKey): String? = previewOverrides.get(group.kind, group.sourceId)

    private fun choosePreview(group: GroupKey) {
        pendingPreviewGroup = group
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            REQUEST_PREVIEW_IMAGE,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PREVIEW_IMAGE || resultCode != RESULT_OK) return
        val group = pendingPreviewGroup ?: return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        previewOverrides.set(group.kind, group.sourceId, uri.toString())
        pendingPreviewGroup = null
        renderItems(focusGroup = group)
    }

    private fun groupColor(group: GroupKey): Int {
        val colors = intArrayOf(Color.rgb(232, 240, 254), Color.rgb(231, 244, 234), Color.rgb(255, 242, 220))
        return colors[Math.floorMod(group.hashCode(), colors.size)]
    }

    private fun moveGroup(group: GroupKey, delta: Int) {
        if (viewState.order != PlaybackOrder.MANUAL) return
        var message: String? = null
        runPlayback(
            work = {
                updatePlayback(items) {
                    // Move within the latest saved order, never a stale page copy.
                    val ordered = orderedItems()
                    val groupOrder = ordered.map { GroupKey(it.sourceKind, it.sourceId) }.distinct()
                    val from = groupOrder.indexOf(group)
                    val target = from + delta
                    if (from < 0 || target !in groupOrder.indices) return@updatePlayback
                    val other = groupOrder[target]
                    val block = ordered.filter { GroupKey(it.sourceKind, it.sourceId) == group }
                    val remaining = ordered.filterNot { GroupKey(it.sourceKind, it.sourceId) == group }.toMutableList()
                    val insertAt = if (delta < 0) remaining.indexOfFirst { GroupKey(it.sourceKind, it.sourceId) == other }
                        else remaining.indexOfLast { GroupKey(it.sourceKind, it.sourceId) == other } + 1
                    remaining.addAll(insertAt, block)
                    reorder(remaining.map { it.id })
                    val movedTo = remaining.map { GroupKey(it.sourceKind, it.sourceId) }.distinct().indexOf(group) + 1
                    message = "已将第 ${from + 1} 组移到第 $movedTo 组；组内顺序及其他图片相对顺序保持。"
                }
            },
        ) {
            movedItemId = null
            renderItems(focusGroup = group)
            message?.let {
                status.text = it
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class GroupKey(val kind: String, val sourceId: String)

    private fun sourceKindLabel(kind: String): String = when (kind) {
        "SINGLE_DOCUMENT" -> "单张图片"
        "MULTIPLE_DOCUMENTS" -> "多张图片"
        "DIRECTORY_TREE" -> "文件夹目录"
        else -> "其他来源"
    }

    private fun updateStaticModeButton(button: android.widget.Button) {
        val isStatic = preferences.isP2StaticTestMode()
        button.text = if (isStatic) {
            "✓ 静态验收模式：开启中（点击恢复）"
        } else {
            "静态验收模式：已关闭（点击开启）"
        }
        button.setBackgroundColor(if (isStatic) 0xFFFFCC80.toInt() else 0xFFC8E6C9.toInt())
        // P8 纠正：显式设置文本颜色为深色，避免深色模式下白色文本在浅色背景上不可读（~1.5:1）
        button.setTextColor(0xFF202124.toInt())
        button.setOnClickListener {
            if (preferences.isP2StaticTestMode()) {
                // P9-STATIC-SHIMMER-01：与新 UI 设置页共用同一份清零，两侧关闭后画面一致
                preferences.exitStaticTestMode()
                updateStaticModeButton(button)
                status.text = "已关闭静态验收模式；P5 图片过渡保留，所有 Shimmer 特效和模糊已关闭。"
            } else {
                // Enter static mode
                preferences.setP2StaticTestMode(true)
                updateStaticModeButton(button)
                status.text = "已开启静态验收模式：所有切换无动画。"
            }
        }
    }

    /** P3 global defaults, the backup editor entry and the test image generator. */
    private fun addP3Section(column: LinearLayout) {
        column.addView(label("P3 实时编辑", 18f))
        column.addView(label(
            "正式入口：在桌面壁纸上两指同时轻点两次，打开编辑页后直接拖动、两指缩放和旋转。" +
                "下面的按钮只是备用入口，用它打开不计入两指双击直达的验收。",
        ))
        column.addView(button("打开编辑页（备用入口）") {
            dev.abdus.apps.shimmer.p3.P3EditActivity.launch(this, targetId = null, gestureUptime = null)
        })
        lateinit var fit: Button
        lateinit var blur: Button
        lateinit var inherit: Button
        val refresh = {
            val defaults = preferences.getP3Defaults()
            fit.text = "全局默认适配：${DefaultFitChoice.of(defaults).label}（点击切换）"
            blur.text = "全局默认模糊填充空白：${if (defaults.blurFill) "开" else "关"}"
            inherit.text = "新图继承上一张的调整：${if (defaults.inheritPrevious) "开" else "关"}"
        }
        fit = button("") {
            // 智能也在循环里：只按 FitMode 循环时智能会显示成“铺满”，点一下就悄悄退出智能
            DefaultFitChoice.of(preferences.getP3Defaults()).next().save(preferences)
            refresh()
        }
        blur = button("") {
            preferences.setP3DefaultBlurFill(!preferences.getP3Defaults().blurFill)
            refresh()
        }
        inherit = button("") {
            preferences.setP3InheritPrevious(!preferences.getP3Defaults().inheritPrevious)
            refresh()
        }
        refresh()
        column.addView(fit)
        column.addView(blur)
        column.addView(inherit)
        column.addView(label("全局默认只影响没有单独保存过的图片；继承开启后，向后切到的新图沿用上一张实际显示的调整，只记在该次播放历史里。"))
        column.addView(button("生成 P3 标定与压力测试图") { generateP3TestImages() })
    }

    private fun generateP3TestImages() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            status.text = "生成测试图需要 Android 10 及以上。"
            return
        }
        status.text = "正在生成 P3 测试图（约数秒）…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { P3TestImages.writeAll(applicationContext) }
            }
            status.text = result.fold(
                onSuccess = {
                    buildString {
                        append("测试图位于 ${P3TestImages.RELATIVE_DIR}。")
                        if (it.written.isNotEmpty()) append("新生成：${it.written.joinToString("、")}。")
                        if (it.skipped.isNotEmpty()) append("已存在未覆盖：${it.skipped.joinToString("、")}。")
                        if (it.failed.isNotEmpty()) append("失败：${it.failed.joinToString("、")}。")
                        append("请在 P1 中把该文件夹添加为来源。")
                    }
                },
                onFailure = {
                    if (it is CancellationException) throw it
                    "生成失败：${it.javaClass.simpleName}，请反馈此提示。"
                },
            )
        }
    }

    /**
     * Status and description text stays selectable so the user can copy it into a report. List
     * rows do not: a selectable TextView takes the touch for its own cursor, which competes with
     * scrolling when a page holds many rows.
     */
    private fun label(value: String, size: Float = 14f, selectable: Boolean = true): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextIsSelectable(selectable)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
