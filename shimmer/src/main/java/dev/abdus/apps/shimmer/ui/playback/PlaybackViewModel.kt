package dev.abdus.apps.shimmer.ui.playback

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p1.SourcePathFormatter
import dev.abdus.apps.shimmer.p2.PlaybackGroupKey
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p2.PlaybackSelectionBridge
import dev.abdus.apps.shimmer.p2.SourcePreviewOverrides
import dev.abdus.apps.shimmer.p4.ScheduleStore
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * P8.4 播放页面 ViewModel
 * 读取 P2 已提交的快照显示当前图片和顺序；顺序切换经 [PlaybackOrderUpdate] 走 P2 协调器。
 *
 * P9-ORDER-GROUP-01：队列按来源／目录分组（[buildQueueModel]），各顺序都显示、只有手动顺序可调；
 * 移动在存储锁内基于最新次序计算（[PlaybackOrderUpdate.move]），与旧 P2 完整播放控制页相同。
 */
class PlaybackViewModel @JvmOverloads constructor(
    application: Application,
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME,
) : AndroidViewModel(application) {

    private val database = ShimmerDatabase.getInstance(application, wallpaperTarget)
    private val snapshotStore = PlaybackSnapshotStore(application, wallpaperTarget)
    private val preferences = WallpaperPreferences.create(application, wallpaperTarget)
    private val scheduleStore = ScheduleStore(application, wallpaperTarget)
    private val previewOverrides = SourcePreviewOverrides(application, wallpaperTarget)
    // 与 P2 页相同的生效目录（含 P4 规则）
    private val repository by lazy { ImageFolderRepository(application, viewModelScope, wallpaperTarget) }

    // 协调器在目录不变时保留排序结果，上万张时每次进页不必重排；它不是线程安全的，只在锁内使用
    private val coordinator = PlaybackOrderUpdate.newCoordinator()
    private val coordinatorLock = Mutex()
    // 仍重读生效目录／快照；只复用输入完全相同的分组与目录树，不缓存启用／授权决定。
    private val queueModelCache = PlaybackQueueModelCache(
        sourcePathOf = { SourcePathFormatter.shorten(it.sourceRootUri.ifBlank { it.sourceId }) },
        folderNameOf = { SourcePathFormatter.folderName(it) },
    )
    /** 最近一次读到的生效目录；移动时用它，避免每点一次都重查数据库。 */
    private var catalog: List<PlaybackItem> = emptyList()

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    /** 展开、分页、刚移动、选定：只属于界面，只在主线程读写。 */
    private var queueView = QueueViewState()
    /**
     * 下一次载入完成后要定位的目标。新建时定位当前图片：进程被系统回收后重建，旧的列表位置
     * 对应的是折叠前的行，不再可信。
     */
    private var pendingFocus: FocusTarget? = null
    private var hasEntered = false
    private var scrollSerial = 0

    private var loadJob: Job? = null
    private var disabledReadJob: Job? = null
    private var disabledReadSerial = 0L
    private var disabledVisibleLimit = DisabledPlaybackList.PAGE_SIZE
    private var currentReadJob: Job? = null
    private var currentReadSerial = 0L
    private var loadSerial = 0L
    private var catalogRevision = 0L
    private var lastAppliedSnapshot: PlaybackSnapshot? = null
    private var hasAppliedQueue = false
    private var catalogObservationJob: Job? = null
    // SharedPreferences 只弱持有 listener；ViewModel 强持有并在销毁时解绑。
    private val snapshotListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PlaybackSnapshotStore.SNAPSHOT_KEY) {
            viewModelScope.launch { refreshCurrentFromSnapshot() }
        }
    }
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == WallpaperPreferences.KEY_DISABLED_IMAGES || key == WallpaperPreferences.KEY_P2_STATIC_TEST_MODE) {
            viewModelScope.launch { catalogChanged() }
        }
    }
    private val scheduleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ScheduleStore.KEY_SETTINGS) viewModelScope.launch { catalogChanged() }
    }

    init {
        snapshotStore.registerListener(snapshotListener)
        preferences.registerListener(preferenceListener)
        scheduleStore.registerListener(scheduleListener)
        // 失效只请求下一次真实查询，不把通知或旧页面列表当成访问授权。
        catalogObservationJob = viewModelScope.launch {
            database.invalidationTracker.createFlow(
                "sources", "image_records", "source_image_links", "source_directory_exclusions",
                emitInitialState = false,
            ).collect { catalogChanged() }
        }
    }

    private fun catalogChanged() {
        ++catalogRevision
        // 待播投影仅属于上一次真实目录，失效通知后不得继续显示已停用来源的候选。
        _uiState.value = _uiState.value.copy(pendingSourceImage = null)
        if (hasEntered) refresh()
    }

    /**
     * CHG-P9-BROWSE-20261009：首次进入定位当前，切页／前后台／旋转后只刷新不隐式定位。
     * 页签列表位置由导航的保存容器恢复；显式定位与尚未完成的调序定位仍保留。
     */
    fun screenEntered() {
        pendingFocus = entryFocus(pendingFocus, hasEntered)
        hasEntered = true
        refresh()
    }

    /** Re-reads the committed snapshot; the screen calls this on every resume and on demand. */
    fun refresh() {
        loadJob?.cancel()
        disabledReadJob?.cancel()
        ++disabledReadSerial
        currentReadJob?.cancel()
        ++currentReadSerial
        val serial = ++loadSerial
        val revision = catalogRevision
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isReloading = true)
            try {
                val loaded = loadQueue(revision)
                val currentImage = withContext(Dispatchers.IO) {
                    loaded.pendingSourceImage = currentImageOf(loaded.pendingSourceId)
                    currentImageOf(loaded.currentId)
                }
                if (serial != loadSerial) return@launch
                applyLoaded(loaded, currentImage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                if (serial != loadSerial || revision != catalogRevision) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isReloading = false,
                    isLoadingDisabled = false,
                    message = "读取播放状态失败（${failed.javaClass.simpleName}）；已有顺序和历史保留。",
                )
            }
        }
    }

    private class Loaded(
        val model: QueueModel,
        val currentId: String?,
        val snapshot: PlaybackSnapshot?,
        val enabledCount: Int,
        val activeRuleName: String?,
        val staticTestMode: Boolean,
        val previewOverrides: Map<String, String>,
        val catalogRevision: Long,
    ) {
        // 不属于 PlaybackSnapshot，不参与正式历史／current／持久化。
        var pendingSourceId: String? = null
        var pendingSourceImage: CurrentImageInfo? = null
        var disabledPage: DisabledPlaybackPage = DisabledPlaybackPage()
    }

    /**
     * 只读：取生效目录和快照，在协调器里还原出完整播放次序，再分组。分组、目录树、位置表在上万张时
     * 较重，放在 [Dispatchers.Default]。
     */
    private suspend fun loadQueue(revision: Long): Loaded {
        val playback = repository.getPlaybackCatalog()
        val overrides = withContext(Dispatchers.IO) { previewOverrides.all() }
        val staticMode = withContext(Dispatchers.IO) { preferences.isP2StaticTestMode() }
        val disabledPage = loadDisabledPage(disabledVisibleLimit)
        return coordinatorLock.withLock {
            withContext(Dispatchers.Default) {
                catalog = playback.items
                val snapshot = snapshotStore.load()
                val restored = PlaybackOrderUpdate.restored(snapshot, playback.items, coordinator)
                // 纯配置保存不先查询整库。随机首次展示在这里生成，仍只读、不写正式历史。
                if (snapshot?.order == PlaybackOrder.RANDOM && snapshot.queueFingerprint == 0) {
                    restored.setOrder(PlaybackOrder.RANDOM)
                }
                val currentId = snapshot?.displayedItemId()
                val model = queueModelCache.resolve(
                    ordered = restored.orderedItems(),
                    order = snapshot?.order ?: restored.snapshot().order,
                    currentId = currentId,
                )
                Loaded(model, currentId, snapshot, playback.items.size, playback.activeRule?.displayName, staticMode, overrides, revision)
                    .apply {
                        pendingSourceId = pendingSourceResumeId(restored)
                        this.disabledPage = disabledPage
                    }
            }
        }
    }

    private suspend fun currentImageOf(currentId: String?): CurrentImageInfo? {
        currentId ?: return null
        val record = database.p1SourceDao().getImageByUri(currentId) ?: return null
        return CurrentImageInfo(
            itemId = record.id,
            uri = currentId,
            fileName = record.displayName.ifBlank { record.relativePath },
            resolution = if (record.width != null && record.height != null) {
                "${record.width} × ${record.height}"
            } else "未知",
            fileSize = record.sizeBytes?.let { bytes ->
                when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                    else -> "${bytes / (1024 * 1024)} MB"
                }
            } ?: "未知"
        )
    }

    private fun applyLoaded(loaded: Loaded, currentImage: CurrentImageInfo?) {
        if (loaded.catalogRevision != catalogRevision) {
            refresh()
            return
        }
        // 生效目录与停用元数据读取之间可能刚好发生启停；不提交同一图片同时在两段中的混合快照。
        if (loaded.disabledPage.disabledIds != preferences.getDisabledImages() ||
            loaded.model.positions.keys.any { it in loaded.disabledPage.disabledIds }
        ) {
            refresh()
            return
        }
        val latest = snapshotStore.load()
        // 目录读取／分组／图片查询期间，新模式可能已经落盘；旧读不得把选择改回去。
        if (!samePlaybackQueueConfiguration(loaded.snapshot, latest)) {
            refresh()
            return
        }
        // 待播来源点依赖完整快照：真正提交／清历史／改来源点后，不发布旧候选。
        if (loaded.pendingSourceId != null && latest != loaded.snapshot) {
            refresh()
            return
        }
        val latestCurrent = latest?.displayedItemId()
        val model = loaded.model.copy(currentId = latestCurrent?.takeIf { it in loaded.model.blockOf })
        // 换了顺序后，旧的选定和"刚移动"已不对应任何操作
        if (model.order != _uiState.value.queue.order) {
            queueView = queueView.copy(selectedItemId = null, movedItemId = null, movedGroup = null,
                multiReorderEnabled = false, multiSelectedIds = emptySet(), movedItemIds = emptySet())
        }
        val scroll = pendingFocus?.let { target ->
            pendingFocus = null
            val focused = queueView.focus(model, target)
            queueView = focused.view
            focused.rowKey
        }
        _uiState.value = _uiState.value.copy(
            currentImage = if (latest == loaded.snapshot) currentImage else _uiState.value.currentImage,
            pendingSourceImage = loaded.pendingSourceImage,
            selectedOrder = PlaybackOrderUpdate.fromPlaybackOrder(model.order),
            queue = model,
            queueRows = buildQueueRows(model, queueView),
            disabledPage = loaded.disabledPage,
            isLoadingDisabled = false,
            multiReorder = multiReorderState(model),
            enabledCount = loaded.enabledCount,
            activeRuleName = loaded.activeRuleName,
            staticTestMode = loaded.staticTestMode,
            previewOverrides = loaded.previewOverrides,
            scrollRequest = scroll?.let { ScrollRequest(it, ++scrollSerial) } ?: _uiState.value.scrollRequest,
            isLoading = false,
            isReloading = false,
        )
        hasAppliedQueue = true
        lastAppliedSnapshot = latest
        if (latest != loaded.snapshot) refreshCurrentFromSnapshot()
    }

    /** 每次真实快照提交只更新一条当前图片；模式／手动次序改变才重建整个队列。 */
    private fun refreshCurrentFromSnapshot() {
        if (!hasAppliedQueue) return
        val latest = snapshotStore.load()
        // 新快照可能已正式提交待播图，或改变了该来源恢复点；只读投影需基于新快照重算。
        // 不保留旧“待播”标签等到下一次进入页面，也不在这里伪写 current 或历史。
        if (_uiState.value.pendingSourceImage != null) {
            _uiState.value = _uiState.value.copy(pendingSourceImage = null)
            refresh()
            return
        }
        if (!samePlaybackQueueConfiguration(lastAppliedSnapshot, latest)) {
            if (!_uiState.value.isSavingOrder) refresh()
            return
        }
        currentReadJob?.cancel()
        val serial = ++currentReadSerial
        val revision = catalogRevision
        currentReadJob = viewModelScope.launch {
            try {
                val id = latest?.displayedItemId()
                val image = withContext(Dispatchers.IO) { currentImageOf(id) }
                if (serial != currentReadSerial || revision != catalogRevision) return@launch
                // 即使通知还在主线程排队，也先检查真实最新值，不靠 listener 当权威。
                if (snapshotStore.load() != latest) {
                    refreshCurrentFromSnapshot()
                    return@launch
                }
                val model = _uiState.value.queue.copy(currentId = id?.takeIf { it in _uiState.value.queue.blockOf })
                lastAppliedSnapshot = latest
                _uiState.value = _uiState.value.copy(
                    currentImage = image, queue = model, queueRows = buildQueueRows(model, queueView),
                )
                // 正式 Engine 已恢复到另一来源，但排序配置仍相同：旧列表必须真实重读。
                // 当前框先轻量更新；不在列表的当前不伪标为其它条目，也不每次正常切图重读万图。
                if (id != null && id !in model.blockOf && !_uiState.value.isReloading) refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                if (currentReadMayPublish(serial, currentReadSerial, latest, snapshotStore.load())) _uiState.value = _uiState.value.copy(
                    message = "读取当前图片信息失败（${failed.javaClass.simpleName}）；已保留已有显示和播放配置。",
                )
            }
        }
    }

    override fun onCleared() {
        snapshotStore.unregisterListener(snapshotListener)
        preferences.unregisterListener(preferenceListener)
        scheduleStore.unregisterListener(scheduleListener)
        catalogObservationJob?.cancel()
        loadJob?.cancel()
        disabledReadJob?.cancel()
        currentReadJob?.cancel()
        super.onCleared()
    }

    /** 界面已处理滚动请求；配置变更重建界面时不再重复滚动。 */
    fun scrollHandled(serial: Int) {
        if (_uiState.value.scrollRequest?.serial == serial) {
            _uiState.value = _uiState.value.copy(scrollRequest = null)
        }
    }

    private fun updateView(transform: (QueueViewState) -> QueueViewState) {
        queueView = transform(queueView)
        _uiState.value = _uiState.value.copy(queueRows = buildQueueRows(_uiState.value.queue, queueView),
            multiReorder = multiReorderState(_uiState.value.queue))
    }

    private fun multiReorderState(model: QueueModel) = PlaybackMultiReorderPolicy.state(
        queueView.multiReorderEnabled && model.manual, queueView.multiSelectedIds, model.positions, model.size,
    )

    /** 展开或折叠一层；只重算可见行，不读库也不碰协调器。 */
    fun toggleLevel(levelKey: String) = updateView { it.toggle(levelKey) }

    /** 与旧 P2 页的"显示后续 50 张"相同，避免上千张一次性渲染。 */
    fun showMoreRows(pageKey: String) = updateView { it.showMore(pageKey) }

    /** 停用段独立分页；每次只按用户已展开页数读取元数据，不重新查询或排序万张有效队列。 */
    fun showMoreDisabledImages() {
        if (isBusy() || _uiState.value.isLoadingDisabled || _uiState.value.disabledPage.remainingCount == 0) return
        disabledVisibleLimit = minOf(
            _uiState.value.disabledPage.totalCount.toLong(),
            disabledVisibleLimit.toLong() + DisabledPlaybackList.PAGE_SIZE,
        ).toInt()
        val serial = ++disabledReadSerial
        val revision = catalogRevision
        disabledReadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoadingDisabled = true)
        disabledReadJob = viewModelScope.launch {
            try {
                val page = loadDisabledPage(disabledVisibleLimit)
                if (serial != disabledReadSerial || revision != catalogRevision) return@launch
                if (page.disabledIds != preferences.getDisabledImages() ||
                    _uiState.value.queue.positions.keys.any { it in page.disabledIds }
                ) {
                    refresh()
                    return@launch
                }
                _uiState.value = _uiState.value.copy(disabledPage = page, isLoadingDisabled = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                if (serial == disabledReadSerial && revision == catalogRevision) {
                    _uiState.value = _uiState.value.copy(isLoadingDisabled = false,
                        message = "读取已停用图片失败（${failed.javaClass.simpleName}）；已有显示和停用记录保留。")
                }
            }
        }
    }

    private suspend fun loadDisabledPage(visibleLimit: Int): DisabledPlaybackPage = withContext(Dispatchers.IO) {
        val disabled = preferences.getDisabledImages()
        val ids = DisabledPlaybackList.idsToLoad(disabled, visibleLimit)
        // 每批不超过一页，避免大量停用图片展开后触及 SQLite 绑定参数上限。
        val records = ids.chunked(DisabledPlaybackList.PAGE_SIZE).flatMap { chunk ->
            database.p1SourceDao().getImagesByUris(chunk)
        }.associate { it.uri to (it.displayName to it.relativePath) }
        DisabledPlaybackList.page(disabled, visibleLimit, records)
    }

    /** 只恢复明确点击且仍有逐项停用记录的 URI；不播放、不启用来源或子目录。 */
    fun restoreDisabledImage(itemId: String) {
        if (isBusy() || itemId !in _uiState.value.disabledPage.disabledIds) return
        restoreDisabledImages {
            if (itemId !in preferences.getDisabledImages()) false else {
                preferences.setImageEnabled(itemId, true)
                true
            }
        }
    }

    /** 仅清逐项停用偏好，不改来源、历史和手动位置；Engine仍可按当前播放规则更新。 */
    fun restoreAllDisabledImages() {
        if (isBusy() || _uiState.value.disabledPage.totalCount == 0) return
        restoreDisabledImages {
            preferences.clearDisabledImages()
            true
        }
    }

    private fun restoreDisabledImages(restore: () -> Boolean) {
        _uiState.value = _uiState.value.copy(isRestoringDisabled = true)
        viewModelScope.launch {
            val message = try {
                if (withContext(Dispatchers.IO) { restore() }) {
                    "已恢复图片启用；是否入队仍取决于图库来源、子目录和日期规则。"
                } else "该图片已经恢复，未更改来源、顺序或播放历史。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                "恢复图片失败（${failed.javaClass.simpleName}）；请重新载入后查看，来源和历史未改。"
            } finally {
                _uiState.value = _uiState.value.copy(isRestoringDisabled = false)
            }
            _uiState.value = _uiState.value.copy(message = message)
            refresh()
        }
    }

    /** 手动顺序下点选一张作为“调整播放位置”的对象；再点同一张取消。 */
    fun selectItem(itemId: String?) {
        if (_uiState.value.selectedOrder != PlayOrder.MANUAL) return
        if (isBusy() || queueView.multiReorderEnabled) return
        if (itemId != null && itemId !in _uiState.value.queue.positions) return
        updateView { it.copy(selectedItemId = itemId?.takeIf { id -> id != it.selectedItemId }) }
    }

    /** 独立开关，不复用行点按；进入／结束不写排序，原单选对象始终保留。 */
    fun setMultiReorder(enabled: Boolean) {
        if (isBusy() || _uiState.value.selectedOrder != PlayOrder.MANUAL) return
        if (queueView.multiReorderEnabled == enabled) return
        updateView { it.copy(multiReorderEnabled = enabled, multiSelectedIds = emptySet()) }
    }

    /** 勾选不会展开、定位、播放或保存；失活项不能新增为选择。 */
    fun toggleMultiItem(itemId: String) {
        if (isBusy() || !queueView.multiReorderEnabled || _uiState.value.selectedOrder != PlayOrder.MANUAL) return
        if (itemId !in _uiState.value.queue.positions) return
        updateView { it.copy(multiSelectedIds = if (itemId in it.multiSelectedIds) it.multiSelectedIds - itemId else it.multiSelectedIds + itemId) }
    }

    fun clearMultiSelection() {
        if (isBusy() || !queueView.multiReorderEnabled) return
        updateView { it.copy(multiSelectedIds = emptySet()) }
    }

    fun moveMultiItems(delta: Int) = runMultiMove { ordered, selected ->
        PlaybackMultiReorderPolicy.move(ordered, selected, delta)
    }

    fun moveMultiItemsTo(position: Int) = runMultiMove { ordered, selected ->
        PlaybackMultiReorderPolicy.moveTo(ordered, selected, position)
    }

    private fun runMultiMove(move: (List<String>, Set<String>) -> QueueMove?) {
        if (isBusy() || !queueView.multiReorderEnabled || _uiState.value.selectedOrder != PlayOrder.MANUAL) return
        val selected = queueView.multiSelectedIds.toSet()
        if (selected.isEmpty()) return
        val first = _uiState.value.queue.ordered.firstOrNull { it.id in selected }?.id
        runMove(
            focus = first?.let { FocusTarget.Item(it) } ?: FocusTarget.Current,
            highlight = { it.copy(movedItemIds = selected, movedItemId = null, movedGroup = null) },
            messageOf = { "已将所选 ${selected.size} 张作为整体移动至第 ${it.to}～${it.to + selected.size - 1} 张；组内按原播放顺序，其他图片相对顺序保持。" },
            refreshCatalog = true,
            focusOfResult = { result -> FocusTarget.Item(result.ids[result.to - 1]) },
        ) { ordered ->
            val ids = ordered.map { it.id }
            if (PlaybackMultiReorderPolicy.block(ids, selected) == null) throw UnavailableMultiReorderSelection()
            move(ids, selected)
        }
    }

    /** 展开后的明确播放按钮发请求；行披露和手动单／多选控件都不复用此入口。 */
    fun clickToPlay(itemId: String) {
        if (isBusy()) return
        if (queueView.multiReorderEnabled) return
        if (itemId !in _uiState.value.queue.blockOf) {
            _uiState.value = _uiState.value.copy(message = "这张图片已不在当前列表中，请等待来源更新后重试。")
            return
        }
        val message = when (PlaybackSelectionBridge.submit(itemId, wallpaperTarget)) {
            PlaybackSelectionBridge.Result.ACCEPTED -> "已请求播放这张图片；成功显示后更新当前标记。"
            PlaybackSelectionBridge.Result.QUEUED -> if (wallpaperTarget == WallpaperTarget.HOME) {
                "已选定待播放图片；返回桌面后读取并显示，当前标记随后更新。"
            } else "已选定锁屏待播放图片；锁屏成功显示后更新本域当前标记。"
            PlaybackSelectionBridge.Result.NO_ENGINE -> "请先在系统预览中应用${if (wallpaperTarget == WallpaperTarget.HOME) "桌面" else "锁屏"}动态壁纸，再选择要播放的图片。"
            PlaybackSelectionBridge.Result.BUSY_EDIT -> "请先保存或取消实时编辑，再选择播放图片。"
            PlaybackSelectionBridge.Result.NOOP -> "当前已在播放这张图片。"
        }
        // 不乐观改当前、历史、随机轮次或手动排序；正式成功提交的快照才是页面当前。
        _uiState.value = _uiState.value.copy(message = message)
    }

    /** 展开到当前图片并滚动到它附近。 */
    fun locateCurrent() {
        val focused = queueView.focus(_uiState.value.queue, FocusTarget.Current)
        val rowKey = focused.rowKey
        if (rowKey == null) {
            _uiState.value = _uiState.value.copy(message = "当前图片不在已启用列表中，或还未开始播放。")
            return
        }
        updateView { focused.view }
        _uiState.value = _uiState.value.copy(scrollRequest = ScrollRequest(rowKey, ++scrollSerial))
    }

    /**
     * Re-reads the effective catalog (sources, folder stops, disabled images, P4 rule) and hands it
     * to the coordinator without changing the order. An empty catalog is not written: saving it
     * would empty the queue and drop the manual order of every source.
     */
    fun reloadSources() {
        if (isBusy()) return
        _uiState.value = _uiState.value.copy(isReloadingSources = true)
        viewModelScope.launch {
            val message = try {
                val playback = repository.getPlaybackCatalog()
                if (playback.items.isEmpty()) {
                    "当前没有已启用图片；已保留原有顺序和历史。请先在图库添加并启用来源。"
                } else {
                    coordinatorLock.withLock {
                        withContext(Dispatchers.Default) {
                            catalog = playback.items
                            snapshotStore.update { latest -> PlaybackOrderUpdate.reload(latest, playback.items, coordinator) }
                        }
                    }
                    val source = playback.activeRule?.let { "P4 规则“${it.displayName}”生效" } ?: "普通目录"
                    "已重新载入：${playback.items.size} 张已启用图片（$source）"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                "重新载入来源失败（${failed.javaClass.simpleName}）；已保留已有顺序和历史。"
            }
            _uiState.value = _uiState.value.copy(isReloadingSources = false, message = message)
            refresh()
        }
    }

    /** One save at a time: [PlaybackUiState.isSavingOrder] ignores taps until the write finishes. */
    fun setPlayOrder(order: PlayOrder) {
        val previous = _uiState.value.selectedOrder
        if (order == previous || isBusy()) return
        val manualSeed = if (order == PlayOrder.MANUAL) _uiState.value.queue.ordered.map { it.id } else emptyList()
        _uiState.value = _uiState.value.copy(isSavingOrder = true)
        viewModelScope.launch {
            val (shownOrder, message) = try {
                // 配置先写最新快照；不先等万张权威目录／排序／分组才让桌面读到选择。
                val saved = withContext(Dispatchers.Default) {
                    snapshotStore.update { latest -> PlaybackOrderUpdate.select(latest, order, manualSeed) }
                }
                // P9-MODE-FOCUS-01：换序只更新播放配置和队列，不生成或替换定位请求。
                // 首次进入及用户尚未完成的调序定位仍由各自入口持有。
                PlaybackOrderUpdate.fromPlaybackOrder(saved.order) to "已保存顺序：${order.displayName}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                previous to "保存顺序失败（${failed.javaClass.simpleName}）；已保留已有顺序和历史。"
            }
            _uiState.value = _uiState.value.copy(selectedOrder = shownOrder, isSavingOrder = false, message = message)
            if (shownOrder != PlayOrder.MANUAL) updateView {
                it.copy(multiReorderEnabled = false, multiSelectedIds = emptySet(), movedItemIds = emptySet())
            }
            refresh()
        }
    }

    /**
     * P9-GALLERY-02：手动顺序下把一张图片上移或下移一位。
     *
     * 契约与旧 P2 完整播放控制页的 `moveItem` 相同：取当前完整顺序、交换相邻两项、整份交给
     * [PlaybackOrderUpdate]。排序在数千张时较重，与 [setPlayOrder] 一样放到后台线程。
     *
     * 只在手动顺序下有意义——其他顺序由协调器自己决定次序，手工移动会在下次重排时丢失。
     */
    fun moveItem(itemId: String, delta: Int) {
        if (_uiState.value.selectedOrder != PlayOrder.MANUAL) return
        if (queueView.multiReorderEnabled) return
        runMove(
            focus = FocusTarget.Item(itemId),
            highlight = { it.copy(movedItemId = itemId, movedGroup = null, movedItemIds = emptySet()) },
            messageOf = { "已将第 ${it.from} 张移到第 ${it.to} 张" },
        ) { ordered -> moveItemIds(ordered.map { it.id }, itemId, delta) }
    }

    /** 选定一张后“调整播放位置”：取出再插入，其他图片相对顺序不变。 */
    fun moveItemTo(itemId: String, position: Int) {
        if (_uiState.value.selectedOrder != PlayOrder.MANUAL) return
        if (queueView.multiReorderEnabled) return
        runMove(
            focus = FocusTarget.Item(itemId),
            highlight = { it.copy(movedItemId = itemId, movedGroup = null, selectedItemId = null, movedItemIds = emptySet()) },
            messageOf = { "已将第 ${it.from} 张移到第 ${it.to} 张" },
        ) { ordered -> moveItemToPosition(ordered.map { it.id }, itemId, position) }
    }

    /** 与旧页 `moveGroup` 相同：整组聚合后移到相邻来源全部图片之前或之后。 */
    fun moveGroup(group: PlaybackGroupKey, delta: Int) {
        if (_uiState.value.selectedOrder != PlayOrder.MANUAL) return
        if (queueView.multiReorderEnabled) return
        runMove(
            focus = FocusTarget.Group(group),
            highlight = { it.copy(movedGroup = group, movedItemId = null, movedItemIds = emptySet()) },
            messageOf = { "已将第 ${it.from} 组移到第 ${it.to} 组；组内顺序及其他图片相对顺序保持。" },
        ) { ordered -> moveGroupIds(ordered, group, delta) }
    }

    /**
     * 移动都在存储锁内基于最新次序计算，界面手里的列表只用来决定"移哪一张"。
     * 一次只做一个写入：[isBusy] 期间的点击被忽略，与切换顺序相同。
     */
    private fun runMove(
        focus: FocusTarget,
        highlight: (QueueViewState) -> QueueViewState,
        messageOf: (QueueMove) -> String,
        refreshCatalog: Boolean = false,
        focusOfResult: ((QueueMove) -> FocusTarget)? = null,
        move: (List<PlaybackItem>) -> QueueMove?,
    ) {
        if (isBusy()) return
        _uiState.value = _uiState.value.copy(isSavingOrder = true)
        viewModelScope.launch {
            val message = try {
                // 多选执行前重新读取真实来源／逐图启停／规则，不以页面旧列表授权移动部分成员。
                val moveCatalog = if (refreshCatalog) repository.getPlaybackCatalog().items else null
                val result = coordinatorLock.withLock {
                    withContext(Dispatchers.Default) {
                        var moved: QueueMove? = null
                        snapshotStore.update { latest ->
                            PlaybackOrderUpdate.move(latest, moveCatalog ?: catalog, coordinator, move)
                                .also { moved = it.second }.first
                        }
                        moved
                    }
                }
                if (result != null) {
                    pendingFocus = focus
                    focusOfResult?.let { pendingFocus = it(result) }
                    queueView = highlight(queueView)
                    messageOf(result)
                } else {
                    "已在该方向的尽头，或目标位置与当前相同；顺序未改。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unavailable: UnavailableMultiReorderSelection) {
                "部分所选图片已停用或不在生效目录中，整次移动已取消；原顺序保留，请清空多选后重新选择。"
            } catch (failed: Exception) {
                "调整顺序失败（${failed.javaClass.simpleName}）；已保留原有顺序。"
            } finally {
                // 取消／销毁也释放页面的忙标志，失败不生成“刚移动”或保存部分结果。
                _uiState.value = _uiState.value.copy(isSavingOrder = false)
            }
            _uiState.value = _uiState.value.copy(isSavingOrder = false, message = message)
            // 顺序变了要重读队列，否则界面还显示旧次序
            refresh()
        }
    }

    /** 清空旧历史，默认保留模式、手动次序和当前画面；只有明确勾选才重置排序。 */
    fun clearHistory(resetManualOrder: Boolean = false) {
        if (isBusy()) return
        _uiState.value = _uiState.value.copy(isSavingOrder = true)
        viewModelScope.launch {
            val message = try {
                coordinatorLock.withLock {
                    withContext(Dispatchers.Default) {
                        coordinator.restore(snapshotStore.clearHistory(resetManualOrder))
                    }
                }
                queueView = queueView.copy(selectedItemId = null, movedItemId = null, movedGroup = null,
                    multiSelectedIds = emptySet(), movedItemIds = emptySet())
                if (resetManualOrder) {
                    "已清空播放历史并重置为名称升序；当前画面与图片来源保留。"
                } else {
                    "已清空播放历史；顺序、手动位置、当前画面与图片来源保留。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                "清空播放历史失败（${failed.javaClass.simpleName}）；已有顺序和历史保留。"
            }
            _uiState.value = _uiState.value.copy(isSavingOrder = false, message = message)
            refresh()
        }
    }

    private fun isBusy(): Boolean = _uiState.value.isSavingOrder || _uiState.value.isReloadingSources ||
        _uiState.value.isRestoringDisabled

    fun messageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

/**
 * 播放页面 UI 状态
 */
data class PlaybackUiState(
    val currentImage: CurrentImageInfo? = null,
    /** 最新生效单根的只读恢复候选；显示“待返回桌面播放”，不是已提交的当前图片。 */
    val pendingSourceImage: CurrentImageInfo? = null,
    val selectedOrder: PlayOrder = PlayOrder.NATURAL_ASC,
    val isLoading: Boolean = true,
    val isReloading: Boolean = false,
    val isSavingOrder: Boolean = false,
    val isReloadingSources: Boolean = false,
    /** P9-ORDER-GROUP-01：按来源／目录分组的完整队列；各顺序都有，只有手动顺序可调。 */
    val queue: QueueModel = QueueModel.EMPTY,
    /** 当前展开状态下的可见行；折叠的层不产生图片行。 */
    val queueRows: List<QueueRow> = emptyList(),
    /** 有效播放队列之后的独立逐图停用列表；不是可播放队列，也不接收手动调序。 */
    val disabledPage: DisabledPlaybackPage = DisabledPlaybackPage(),
    val isLoadingDisabled: Boolean = false,
    val isRestoringDisabled: Boolean = false,
    val multiReorder: MultiReorderUiState = MultiReorderUiState(),
    /** 生效目录的图片数；随机轮次尚未生成时可能大于 [queue] 的长度。 */
    val enabledCount: Int = 0,
    /** 生效的 P4 规则名；没有规则时为 null，即普通目录。 */
    val activeRuleName: String? = null,
    val staticTestMode: Boolean = false,
    /** 用户为来源指定的预览图，键见 [SourcePreviewOverrides]。 */
    val previewOverrides: Map<String, String> = emptyMap(),
    val scrollRequest: ScrollRequest? = null,
    val message: String? = null,
)

/** 请界面把 [rowKey] 那一行滚到可见处；[serial] 每次递增，同一行可以重复定位。 */
data class ScrollRequest(val rowKey: String, val serial: Int)

/**
 * 当前图片信息
 */
data class CurrentImageInfo(
    val itemId: String,
    val uri: String,
    val fileName: String,
    val resolution: String,
    val fileSize: String
)

/** 队列每页条数；与旧 P2 页的 ROW_PAGE_SIZE 一致。 */
const val QueuePageSize = 50
