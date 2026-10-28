package dev.abdus.apps.shimmer.ui.gallery

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p1.DirectoryTreeNode
import dev.abdus.apps.shimmer.p1.P1ImportResult
import dev.abdus.apps.shimmer.p1.P1ImportState
import dev.abdus.apps.shimmer.p1.P1ScanProgress
import dev.abdus.apps.shimmer.p1.P1SourceKind
import dev.abdus.apps.shimmer.p1.P1SourceRepository
import dev.abdus.apps.shimmer.p1.chooseSourcePreview
import dev.abdus.apps.shimmer.p1.buildDirectoryTree
import dev.abdus.apps.shimmer.p2.SourcePreviewOverrides
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P8.3 图库页面 ViewModel
 * 连接 P1SourceRepository 获取来源数据；子目录在卡片上逐级展开并启用/停用，规则与 P1 来源页相同。
 * P9-GALLERY-04：添加来源、扫描进度与取消已迁入本 ViewModel，新 UI 不再跳转旧 P1 页。
 */
class GalleryViewModel @JvmOverloads constructor(
    application: Application,
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME,
) : AndroidViewModel(application) {

    private val database = ShimmerDatabase.getInstance(application, wallpaperTarget)
    private val repository = P1SourceRepository(application, database, target = wallpaperTarget)
    private val previewOverrides = SourcePreviewOverrides(application, wallpaperTarget)
    private val preferences = WallpaperPreferences.create(application, wallpaperTarget)

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var importJob: Job? = null

    // 以下只在主线程读写：子目录树（只含相对路径）、停用记录和已展开的层级，均按来源 ID 保存
    private val directoryTrees = HashMap<String, DirectoryTreeNode<String>>()
    private val directoryExclusions = HashMap<String, MutableSet<String>>()
    private val expandedDirectories = HashMap<String, MutableSet<String>>()
    private var directoryToggleInFlight = false
    private val batchImages = HashMap<String, List<GalleryBatchImage>>()
    private val batchViews = HashMap<String, GalleryBatchViewState>()
    private val disabledImageReads = GalleryDisabledImageReads()
    private val disabledImages: Set<String> get() = disabledImageReads.images
    private var imageToggleInFlight = false

    // 编辑页或设置页亦写同一偏好；保留强引用并在清理时解绑，避免失去通知或泄漏 ViewModel。
    private val disabledImageListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == WallpaperPreferences.KEY_DISABLED_IMAGES) {
            viewModelScope.launch {
                try {
                    refreshDisabledImages()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    _uiState.value = _uiState.value.copy(
                        message = "读取图片停用状态失败（${failed.javaClass.simpleName}）；已保留原有记录。",
                    )
                }
            }
        }
    }

    init {
        preferences.registerListener(disabledImageListener)
    }

    override fun onCleared() {
        preferences.unregisterListener(disabledImageListener)
        super.onCleared()
    }

    /** (Re)starts observing the sources; the screen calls this on every resume. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                repository.sources.collect { entities ->
                    // 自选预览一次读出；偏好文件首次读取会碰磁盘，不放在主线程
                    val overrides = withContext(Dispatchers.IO) { previewOverrides.all() }
                    readDisabledImages()
                    val sourceItems = entities.map { entity ->
                        // One read per source gives both the image count and its folder levels.
                        val images = repository.sourceImages(entity.id)
                        val isTree = entity.kind == P1SourceKind.DIRECTORY_TREE.name
                        // 上万张图片时建树和计数放到后台线程，主线程只接收结果
                        val (tree, subdirectoryCount) = if (isTree) {
                            withContext(Dispatchers.Default) {
                                val paths = images.map { it.relativePath }
                                buildDirectoryTree(paths) { it } to GallerySourceSummary.subdirectoryCount(paths)
                            }
                        } else null to 0
                        if (tree != null && subdirectoryCount > 0) {
                            directoryTrees[entity.id] = tree
                            // 停用子目录不改来源表，不会触发这次重读；这里每次都重新读取
                            directoryExclusions[entity.id] = repository.excludedDirectories(entity.id).toMutableSet()
                        } else {
                            directoryTrees.remove(entity.id)
                            directoryExclusions.remove(entity.id)
                        }
                        if (entity.kind == P1SourceKind.MULTIPLE_DOCUMENTS.name) {
                            batchImages[entity.id] = images.map { image ->
                                GalleryBatchImage(image.uri, image.displayName.ifBlank { image.relativePath })
                            }
                        } else {
                            batchImages.remove(entity.id)
                            batchViews.remove(entity.id)
                        }
                        val item = SourceItem(
                            id = entity.id,
                            kind = entity.kind,
                            name = GallerySourceSummary.title(entity.kind, entity.rootUri),
                            kindLabel = GallerySourceSummary.kindLabel(entity.kind),
                            location = GallerySourceSummary.location(entity.rootUri),
                            type = GallerySourceSummary.type(entity.kind),
                            imageCount = images.size,
                            isEnabled = entity.enabled,
                            subdirectoryCount = subdirectoryCount,
                            // P9-GALLERY-01：卡片预览图。chooseSourcePreview 的选法与旧 P1 页一致
                            // （目录取最后一张、单图/多图取第一张），所以两侧看到的是同一张。
                            // P9-PREVIEW-EDIT-01：用户自选的预览优先，与旧 P1/P2 页读同一份存储。
                            previewUri = SourcePreviewOverrides.lookup(overrides, entity.kind, entity.id)
                                ?: images.takeIf { it.isNotEmpty() }
                                    ?.let { chooseSourcePreview(entity.kind, it).uri },
                        )
                        item.copy(directoryRows = directoryRowsFor(item), batchPage = batchPageFor(item))
                    }
                    val liveIds = entities.mapTo(HashSet()) { it.id }
                    directoryTrees.keys.retainAll(liveIds)
                    directoryExclusions.keys.retainAll(liveIds)
                    expandedDirectories.keys.retainAll(liveIds)
                    batchImages.keys.retainAll(liveIds)
                    batchViews.keys.retainAll(liveIds)
                    // 逐个来源读取期间可能收到外部停用通知；提交时用当前停用集合重算，避免旧页覆写新状态。
                    _uiState.value = _uiState.value.copy(
                        sources = sourceItems.map { it.copy(batchPage = batchPageFor(it)) },
                        isLoading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "读取图片来源失败（${failed.javaClass.simpleName}）；已有配置保留。",
                )
            }
        }
    }

    fun toggleSourceEnabled(sourceId: String) {
        viewModelScope.launch {
            val currentState = _uiState.value.sources.find { it.id == sourceId }?.isEnabled ?: return@launch
            try {
                repository.setEnabled(sourceId, !currentState)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                _uiState.value = _uiState.value.copy(
                    message = "修改启用状态失败（${failed.javaClass.simpleName}）；已有配置保留。",
                )
            }
        }
    }

    /** Loads once for the back-gesture preview, which does not observe resume itself. */
    fun ensureLoaded() {
        if (loadJob == null) refresh()
    }

    /** One tap opens or closes a level; only that source's rows are rebuilt. */
    fun toggleDirectoryExpanded(sourceId: String, path: String) {
        val expanded = expandedDirectories.getOrPut(sourceId) { HashSet() }
        if (!expanded.remove(path)) expanded.add(path)
        updateDirectoryRows(sourceId)
    }

    /** Stops or resumes one folder and its subtree; image records stay, like on the P1 page. */
    fun setDirectoryEnabled(sourceId: String, path: String, enabled: Boolean) {
        if (directoryToggleInFlight) return
        directoryToggleInFlight = true
        viewModelScope.launch {
            val message = try {
                repository.setDirectoryEnabled(sourceId, path, enabled)
                val excluded = directoryExclusions.getOrPut(sourceId) { HashSet() }
                if (enabled) excluded.remove(path) else excluded.add(path)
                updateDirectoryRows(sourceId)
                if (enabled) {
                    "已启用子目录“$path”；到“播放”页点“重新载入来源”后按新目录播放。"
                } else {
                    "已停用子目录“$path”及其下级；图像记录保留，到“播放”页点“重新载入来源”后生效。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                "修改子目录启用状态失败（${failed.javaClass.simpleName}）；已有配置保留。"
            } finally {
                directoryToggleInFlight = false
            }
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    private fun directoryRowsFor(item: SourceItem): DirectoryRows {
        val tree = directoryTrees[item.id] ?: return DirectoryRows(emptyList(), truncated = false)
        return GalleryDirectoryRows.rows(
            tree = tree,
            rootName = item.name,
            sourceEnabled = item.isEnabled,
            excluded = directoryExclusions[item.id].orEmpty(),
            expanded = expandedDirectories[item.id].orEmpty(),
        )
    }

    private fun updateDirectoryRows(sourceId: String) {
        _uiState.value = _uiState.value.copy(
            sources = _uiState.value.sources.map { item ->
                if (item.id == sourceId) item.copy(directoryRows = directoryRowsFor(item)) else item
            },
        )
    }

    fun toggleBatchExpanded(sourceId: String) {
        if (sourceId !in batchImages) return
        batchViews[sourceId] = (batchViews[sourceId] ?: GalleryBatchViewState()).toggle()
        updateBatchRows(sourceId)
    }

    fun moveBatchPage(sourceId: String, delta: Int) {
        val images = batchImages[sourceId] ?: return
        batchViews[sourceId] = (batchViews[sourceId] ?: GalleryBatchViewState()).movePage(delta, images.size)
        updateBatchRows(sourceId)
    }

    /** 只改全局图片停用偏好，壁纸已有监听会重读生效目录；来源关联与手动位置均不改。 */
    fun setBatchImageEnabled(sourceId: String, uri: String, enabled: Boolean) {
        val source = _uiState.value.sources.find { it.id == sourceId } ?: return
        if (imageToggleInFlight || _uiState.value.isImporting || !source.isEnabled ||
            source.kind != P1SourceKind.MULTIPLE_DOCUMENTS.name ||
            batchImages[sourceId].orEmpty().none { it.uri == uri }
        ) return
        imageToggleInFlight = true
        viewModelScope.launch {
            val message = try {
                val written = withContext(Dispatchers.IO) {
                    // 来源开关的异步写入可能已完成而页面仍旧，写图片前以数据库中的父状态为准。
                    val currentSource = database.p1SourceDao().getSource(sourceId)
                    if (currentSource?.enabled != true || currentSource.kind != P1SourceKind.MULTIPLE_DOCUMENTS.name ||
                        repository.sourceImages(sourceId).none { it.uri == uri }
                    ) false else {
                        preferences.setImageEnabled(uri, enabled)
                        true
                    }
                }
                refreshDisabledImages()
                if (written) {
                    "已${if (enabled) "启用" else "停用"}这张图片；共享来源同步生效，关联和手动位置保留。"
                } else {
                    "来源已停用或图片关联已变化，未修改图片状态；请重新载入图库。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                "修改图片启用状态失败（${failed.javaClass.simpleName}）；来源关联和顺序保留。"
            } finally {
                imageToggleInFlight = false
            }
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    private suspend fun refreshDisabledImages() {
        if (readDisabledImages()) updateBatchRows()
    }

    /** 所有读取在主线程取序号，IO 返回后只允许最新读取提交；刷新与监听共用同一门。 */
    private suspend fun readDisabledImages(): Boolean {
        val read = disabledImageReads.beginRead()
        val images = withContext(Dispatchers.IO) { preferences.getDisabledImages() }
        return disabledImageReads.publish(read, images)
    }

    private fun batchPageFor(item: SourceItem): GalleryBatchPage = GalleryBatchRows.page(
        images = batchImages[item.id].orEmpty(),
        sourceEnabled = item.isEnabled,
        disabled = disabledImages,
        view = batchViews[item.id] ?: GalleryBatchViewState(),
    )

    private fun updateBatchRows(sourceId: String? = null) {
        _uiState.value = _uiState.value.copy(
            sources = _uiState.value.sources.map { item ->
                if (sourceId == null || item.id == sourceId) item.copy(batchPage = batchPageFor(item)) else item
            },
        )
    }

    /**
     * P9-PREVIEW-EDIT-01：用户在卡片预览图上选了一张新图。
     *
     * 只改预览存储，不动来源和已提交的图像；写入同时清掉旧 P1 页的键，旧页再打开也显示这一张。
     * 持久读取授权已由选取器在返回时申请。
     */
    fun setCustomPreview(sourceId: String, uri: Uri) {
        val source = _uiState.value.sources.find { it.id == sourceId } ?: return
        val value = uri.toString()
        previewOverrides.set(source.kind, sourceId, value)
        _uiState.value = _uiState.value.copy(
            sources = _uiState.value.sources.map { item ->
                if (item.id == sourceId) item.copy(previewUri = value) else item
            },
            message = "已更换“${source.name}”的预览图；来源与图片未改动。",
        )
    }

    /** Called only after the user confirms the delete dialog. */
    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            val message = try {
                if (repository.deleteSource(sourceId)) "来源已删除；共享图像记录保留。"
                else "来源已不存在，未修改其他图像记录。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                "删除来源失败（${failed.javaClass.simpleName}）；已有配置保留。"
            }
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    /**
     * P9-GALLERY-04：在新 UI 添加来源。
     *
     * 三种类型都经 [P1SourceRepository]，与旧 P1 页用同一条导入路径。扫描进度经 [GalleryUiState]
     * 回传界面，取消由 [cancelImport] 处理。
     *
     * 七种导入结果都要给出可分辨的反馈——只报成功会让用户在撤权、空目录、扫描中断时无从判断。
     */
    fun addDirectorySource(uri: Uri, includeSubdirectories: Boolean = true) = importSource {
        repository.importDirectory(uri, includeSubdirectories = includeSubdirectories) { progress ->
            publishProgress(progress)
        }
    }

    fun addSingleImageSource(uri: Uri) = importSource { repository.importSingleDocument(uri) }

    /**
     * P9-MULTI-GROUP-01：一次多选添加成为一个来源组，像目录一样成组显示；再次添加另成一组。
     * 部分图片不能读取时仍建组并说明跳过张数；一张都没加入时不留下空组。
     */
    fun addMultipleImageSources(uris: List<Uri>) = importSource(::batchMessageFor) {
        repository.importImageBatch(uris) { progress -> publishProgress(progress) }
    }

    /**
     * P9-GALLERY-04：重新扫描单个来源。
     *
     * 取代原先跳转旧 P1 页的"编辑"。与导入共用 [importSource] 的状态与反馈，因此扫描进度、取消与
     * 七种结果的处理完全一致。
     */
    fun rescanSource(sourceId: String) = importSource {
        repository.rescan(sourceId) { progress -> publishProgress(progress) }
    }

    private fun importSource(
        messageFor: (P1ImportResult) -> String = ::importMessageFor,
        work: suspend () -> P1ImportResult,
    ) {
        if (_uiState.value.isImporting) return
        importJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, importProgress = null)
            val message = try {
                val result = withContext(Dispatchers.IO) { work() }
                messageFor(result)
            } catch (e: CancellationException) {
                // 用户主动取消：仓库已按 PreserveReason.CANCELLED 保留原有记录，不删数据
                "已取消添加；已有来源未改动"
            } catch (e: Exception) {
                "添加失败：${e.message ?: e.javaClass.simpleName}"
            }
            _uiState.value = _uiState.value.copy(isImporting = false, importProgress = null)
            refresh()
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    /** 扫描进度只在主线程更新界面状态。 */
    private fun publishProgress(progress: P1ScanProgress) {
        viewModelScope.launch {
            if (_uiState.value.isImporting) {
                _uiState.value = _uiState.value.copy(importProgress = progress)
            }
        }
    }

    /** 取消进行中的导入；与旧 P1 页的"取消当前扫描"等效。 */
    fun cancelImport() {
        importJob?.cancel()
        importJob = null
    }

    /** 七种导入状态逐一给出可分辨的中文反馈，与旧 P1 页的措辞一致。 */
    private fun importMessageFor(result: P1ImportResult): String = when (result.state) {
        P1ImportState.COMMITTED -> "已添加，共 ${result.committedImageCount} 张"
        P1ImportState.EMPTY -> "未找到图片；已有来源未改动"
        P1ImportState.CANCELLED -> "扫描已取消；已有来源未改动"
        P1ImportState.PERMISSION_REQUIRED -> "需要重新授权该位置，请重新选择"
        P1ImportState.FAILED -> "读取失败；已有来源未改动"
        P1ImportState.INCOMPLETE -> "扫描未完成，已保留原有记录以免误删"
        P1ImportState.METADATA_FAILED -> "部分图片信息读取失败，已保留原有记录"
    }

    /** 一组多选的反馈：说明成为一组，并报出未能加入的张数；失败时说明没有新建分组。 */
    private fun batchMessageFor(result: P1ImportResult): String = when (result.state) {
        P1ImportState.COMMITTED -> buildString {
            append("已添加为一组，共 ${result.committedImageCount} 张")
            if (result.skippedImageCount > 0) append("（${result.skippedImageCount} 张未能加入）")
        }
        else -> "${importMessageFor(result)}；未新建分组"
    }

    fun messageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

/**
 * 图库页面 UI 状态
 */
data class GalleryUiState(
    val sources: List<SourceItem> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null,
    /** P9-GALLERY-04：导入进行中。界面据此禁用添加入口并显示进度与取消。 */
    val isImporting: Boolean = false,
    /** 扫描进度；目录来源和一组多选有（多选按已处理张数），单张导入为 null。 */
    val importProgress: P1ScanProgress? = null,
)
