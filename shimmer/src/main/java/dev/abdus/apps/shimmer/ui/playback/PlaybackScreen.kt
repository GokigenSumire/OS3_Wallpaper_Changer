package dev.abdus.apps.shimmer.ui.playback

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abdus.apps.shimmer.WallpaperUtil
import dev.abdus.apps.shimmer.p2.SourcePreviewOverrides
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.navigation.tabContentPadding
import dev.abdus.apps.shimmer.ui.navigation.RefreshOnResume
import dev.abdus.apps.shimmer.ui.playback.components.CurrentImageCard
import dev.abdus.apps.shimmer.ui.playback.components.MoveToPositionDialog
import dev.abdus.apps.shimmer.ui.playback.components.PlayOrderSection
import dev.abdus.apps.shimmer.ui.playback.components.QueueGroupHeaderRow
import dev.abdus.apps.shimmer.ui.playback.components.QueueImageRow
import dev.abdus.apps.shimmer.ui.playback.components.QueueLevelRow
import dev.abdus.apps.shimmer.ui.playback.components.QueueMoreRow
import dev.abdus.apps.shimmer.ui.theme.Spacing
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.ui.navigation.wallpaperTargetViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P8.4 播放控制页面：当前图片、顺序选择、按来源／目录分组的播放队列、设为壁纸。
 *
 * P9-ORDER-GROUP-01：队列恢复旧 P2 完整播放控制页的组头、逐层目录、分页、整组移动与"刚移动"
 * 高亮，并能选定一张“调整播放位置”；首次进入和点"定位当前"时展开并滚到当前图片。
 * P9-MODE-FOCUS-01：切换播放顺序不再生成定位请求，用户已有浏览位置由列表继续持有。
 * CHG-P9-BROWSE-20261009：切回本页保留浏览位置；顶栏中央的回顶按钮亦支持双击。
 * P9-PLAYBACK-ENTRIES-01：补回旧页的设为壁纸确认、"选择其他入口"（系统选择器／本应用设置）、
 * 系统页面未打开的检测和"清空播放历史"。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaybackScreen(
    modifier: Modifier = Modifier,
    wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME,
    viewModel: PlaybackViewModel = wallpaperTargetViewModel(wallpaperTarget, ::PlaybackViewModel)
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    var showPlaybackSettings by rememberSaveable { mutableStateOf(false) }
    val settingsScrollState = rememberScrollState()

    fun returnToTop() {
        if (showPlaybackSettings) {
            scrollScope.launch { settingsScrollState.scrollTo(0) }
            return
        }
        // 清掉正在处理的定位请求，让旧定位动画不在回顶之后又跳回；后续明确调序仍可正常定位。
        uiState.scrollRequest?.let { viewModel.scrollHandled(it.serial) }
        scrollScope.launch { listState.scrollToItem(0) }
    }

    // P9-PLAYBACK-01：设为壁纸的结果提示；每次回到前台重新判断是否已是当前壁纸
    var wallpaperHint by remember { mutableStateOf<String?>(null) }
    var isActiveWallpaper by remember(wallpaperTarget) { mutableStateOf(WallpaperUtil.isActiveWallpaper(context, wallpaperTarget)) }
    var showWallpaperConfirm by rememberSaveable { mutableStateOf(false) }
    var showRoutes by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showRestoreAllConfirm by rememberSaveable { mutableStateOf(false) }
    var resetManualOrder by rememberSaveable { mutableStateOf(false) }
    var moveTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showMultiPosition by rememberSaveable { mutableStateOf(false) }
    // 与手动调序选择、真实当前图片分离；滚动离屏或切页重建仍按图片 ID 保留操作区。
    var expandedActionItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val actionItemIds = remember(uiState.queue.positions, uiState.disabledPage.disabledIds) {
        uiState.queue.positions.keys + uiState.disabledPage.disabledIds
    }
    val retainedActionItemId = PlaybackRowDisclosure.retain(expandedActionItemId, actionItemIds)
    LaunchedEffect(actionItemIds, uiState.isLoading, uiState.isReloading) {
        // 初次载入前的空模型不是来源失活，不能擦掉 saveable 的浏览状态。
        if (!uiState.isLoading && !uiState.isReloading) {
            expandedActionItemId = PlaybackRowDisclosure.retain(expandedActionItemId, actionItemIds)
        }
    }
    // 直达预览发出的时刻；系统静默拒绝时几乎立刻回到本页，据此判断"系统页面未打开"
    var previewLaunchedAt by rememberSaveable { mutableLongStateOf(0L) }

    fun launchPicker() {
        val result = WallpaperUtil.openWallpaperPicker(context, wallpaperTarget)
        previewLaunchedAt = if (result == WallpaperUtil.PickerLaunchResult.DIRECT_PREVIEW) SystemClock.elapsedRealtime() else 0L
        wallpaperHint = pickerHint(result)
        if (result == WallpaperUtil.PickerLaunchResult.FAILED) showRoutes = true
    }

    // 壁纸在后台提交新图片、P2 页改顺序后，回到本页重新读取
    RefreshOnResume {
        viewModel.screenEntered()
        isActiveWallpaper = WallpaperUtil.isActiveWallpaper(context, wallpaperTarget)
        val launchedAt = previewLaunchedAt
        previewLaunchedAt = 0L
        // 与旧 P2 页相同：发出直达预览后 1.5 秒内就回来且仍不是当前壁纸，多半是系统没有打开预览页
        wallpaperHint = if (launchedAt > 0L && !isActiveWallpaper &&
            SystemClock.elapsedRealtime() - launchedAt < SilentReturnMillis
        ) {
            showRoutes = true
            "系统页面似乎未打开；请从下面的入口中选择其他方式。"
        } else null
    }

    // P9-GALLERY-03：与图库页一致，重新载入与顺序保存的反馈改用 toast，不遮挡页面内容
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.messageShown()
        }
    }

    // P9-ORDER-GROUP-01：滚到当前图片或刚移动的一行；已在屏幕内就不动，免得列表跳来跳去
    val scrollRequest = uiState.scrollRequest
    LaunchedEffect(scrollRequest?.serial, showPlaybackSettings) {
        if (showPlaybackSettings) return@LaunchedEffect
        val request = scrollRequest ?: return@LaunchedEffect
        val rowIndex = uiState.queueRows.indexOfFirst { it.key == request.rowKey }
        try {
            if (rowIndex >= 0) scrollToRow(listState, LeadingItemCount + rowIndex)
        } finally {
            // 用户拖动／回顶／离开页签会取消滚动动画。取消同样算处理过，返回不重放旧定位。
            // 按 serial 消费，不能清掉随后发出的新调序请求。
            viewModel.scrollHandled(request.serial)
        }
    }

    val busy = uiState.isSavingOrder || uiState.isReloadingSources || uiState.isRestoringDisabled

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            Column {
            dev.abdus.apps.shimmer.ui.navigation.WallpaperPageTopAppBar(
                title = "播放", wallpaperTarget = wallpaperTarget,
                actions = {
                    Box(Modifier.size(48.dp).combinedClickable(
                        enabled = !uiState.isLoading, role = Role.Button,
                        onClickLabel = "返回当前播放分区顶部",
                        onDoubleClick = { returnToTop() },
                        onClick = { returnToTop() },
                    ), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.KeyboardDoubleArrowUp, contentDescription = "回顶")
                    }
                    if (!showPlaybackSettings) IconButton(onClick = { viewModel.reloadSources() }, enabled = !busy) {
                        Icon(Icons.Default.Refresh,
                            contentDescription = if (uiState.isReloadingSources) "正在重新载入来源" else "重新载入来源")
                    }
                },
            )
            TabRow(selectedTabIndex = if (showPlaybackSettings) 1 else 0, containerColor = Color.Transparent) {
                Tab(selected = !showPlaybackSettings, onClick = { showPlaybackSettings = false }, text = { Text("播放队列") })
                Tab(selected = showPlaybackSettings, onClick = { showPlaybackSettings = true }, text = { Text("播放设置") })
            }
            if (uiState.multiReorder.enabled && !showPlaybackSettings) {
                MultiReorderToolbar(
                    state = uiState.multiReorder,
                    busy = busy,
                    onUp = { viewModel.moveMultiItems(-1) },
                    onDown = { viewModel.moveMultiItems(1) },
                    onPosition = { showMultiPosition = true },
                    onClear = { viewModel.clearMultiSelection() },
                    onFinish = { viewModel.setMultiReorder(false) },
                )
            }
            }
        }
    ) { paddingValues ->
        if (showPlaybackSettings) {
            Box(Modifier.fillMaxSize().padding(paddingValues)) {
                PlaybackSettingsPage(wallpaperTarget, scrollState = settingsScrollState,
                    onClearHistory = { resetManualOrder = false; showClearConfirm = true }, historyEnabled = !busy)
            }
        } else if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // 队列可能有上千行，改用 LazyColumn；前面 [LeadingItemCount] 项是固定区块
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                // P9-FLOAT-INSET-01：悬浮底栏开启时底部多留白，最后一项才不被药丸挡住
                contentPadding = tabContentPadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                item(key = "current") { CurrentImageSection(uiState, wallpaperTarget) }
                item(key = "wallpaper") {
                    WallpaperSection(
                        isActiveWallpaper = isActiveWallpaper,
                        hint = wallpaperHint,
                        onSetWallpaper = { if (isActiveWallpaper) launchPicker() else showWallpaperConfirm = true },
                        onOtherRoutes = { showRoutes = true },
                    )
                }
                item(key = "order") {
                    // 播放顺序选择：经 P2 协调器保存，壁纸收到快照变化后按新顺序继续
                    PlayOrderSection(
                        selectedOrder = uiState.selectedOrder,
                        onOrderSelected = { viewModel.setPlayOrder(it) },
                        enabled = !busy
                    )
                }
                item(key = "status") {
                    StatusSection(uiState, busy)
                }
                item(key = "queue-title") {
                    QueueTitleSection(uiState, busy, onLocateCurrent = { viewModel.locateCurrent() },
                        onMultiToggle = { viewModel.setMultiReorder(it) })
                }
                queueRows(
                    uiState, busy, viewModel,
                    expandedActionItemId = retainedActionItemId,
                    onToggleActions = { id ->
                        expandedActionItemId = PlaybackRowDisclosure.toggle(expandedActionItemId, id, actionItemIds)
                    },
                    onMoveTo = { moveTargetId = it },
                )
                // 独立跟在全部有效队列行之后，复用同一 LazyColumn，禁止覆盖或叠放在队列上。
                item(key = DisabledPlaybackList.TITLE_KEY, contentType = "disabled-title") {
                    DisabledPlaybackTitle(uiState, busy, onRestoreAll = { showRestoreAllConfirm = true })
                }
                itemsIndexed(uiState.disabledPage.entries,
                    key = { _, entry -> DisabledPlaybackList.rowKey(entry.id) },
                    contentType = { _, _ -> "disabled-image" },
                ) { index, entry ->
                    QueueImageRow(
                        image = DisabledPlaybackList.row(entry, index),
                        enabled = !busy,
                        manual = false,
                        actionsExpanded = entry.id == retainedActionItemId,
                        onToggleActions = {
                            expandedActionItemId = PlaybackRowDisclosure.toggle(expandedActionItemId, entry.id, actionItemIds)
                        },
                        onPlay = {}, onSelect = {}, onMoveUp = {}, onMoveDown = {}, onMoveTo = {},
                        restoreAction = { viewModel.restoreDisabledImage(entry.id) },
                    )
                }
                if (uiState.disabledPage.remainingCount > 0 || uiState.isLoadingDisabled) {
                    item(key = DisabledPlaybackList.MORE_KEY, contentType = "disabled-more") {
                        TextButton(
                            onClick = { viewModel.showMoreDisabledImages() },
                            enabled = !busy && !uiState.isLoadingDisabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (uiState.isLoadingDisabled) "正在载入…" else
                                "显示后续 ${minOf(DisabledPlaybackList.PAGE_SIZE, uiState.disabledPage.remainingCount)} 张（剩余 ${uiState.disabledPage.remainingCount} 张）")
                        }
                    }
                }
            }
        }
    }

    if (showRestoreAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreAllConfirm = false },
            title = { Text("恢复全部已停用图片？") },
            text = { Text("恢复 ${uiState.disabledPage.totalCount} 张图片的逐项启用。不会启用已关闭的来源或子目录；历史和手动位置保留，不主动切图，壁纸按当前播放规则更新。") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreAllConfirm = false
                    viewModel.restoreAllDisabledImages()
                }, enabled = !busy && uiState.disabledPage.totalCount > 0) { Text("全部恢复") }
            },
            dismissButton = { TextButton(onClick = { showRestoreAllConfirm = false }) { Text("取消") } },
        )
    }

    // P9-PLAYBACK-ENTRIES-01：与旧 P2 页相同的设为壁纸确认；已是当前壁纸时直接打开，不再确认
    if (showWallpaperConfirm) {
        AlertDialog(
            onDismissRequest = { showWallpaperConfirm = false },
            title = { Text("需要设置本应用为动态壁纸") },
            text = {
                Text(if (wallpaperTarget == WallpaperTarget.LOCK) {
                    "将打开独立锁屏服务的系统预览，请只选择锁屏。若系统只提供共享桌面与锁屏，本轮锁屏播放不会生效；请勿覆盖现有桌面。"
                } else {
                    "将请求打开系统动态壁纸预览，请在系统页面只选择桌面并应用。若未跳转，可选择其他系统入口。"
                })
            },
            confirmButton = {
                TextButton(onClick = { showWallpaperConfirm = false; launchPicker() }) { Text("打开系统页面") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showWallpaperConfirm = false; showRoutes = true }) { Text("其他入口") }
                    TextButton(onClick = { showWallpaperConfirm = false }) { Text("取消") }
                }
            },
        )
    }

    if (showRoutes) {
        AlertDialog(
            onDismissRequest = { showRoutes = false },
            title = { Text("选择系统入口") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(
                        onClick = {
                            showRoutes = false
                            val result = WallpaperUtil.openSystemWallpaperChooser(context)
                            previewLaunchedAt = 0L
                            wallpaperHint = pickerHint(result)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("系统动态壁纸选择器") }
                    TextButton(
                        onClick = {
                            showRoutes = false
                            previewLaunchedAt = 0L
                            wallpaperHint = if (WallpaperUtil.openAppDetails(context)) {
                                "已请求打开本应用设置；如系统提供“动态壁纸服务”选项，请手动允许后返回重试。"
                            } else {
                                "无法打开本应用设置，请保留系统提示并反馈。"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("本应用设置（查看系统权限）") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showRoutes = false }) { Text("取消") } },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false; resetManualOrder = false },
            title = { Text("清空播放历史？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("清空旧历史并重置随机轮次。默认保留当前顺序、手动排序（含停用图片的位置）与当前画面，不再回退到清空前的图片。图片来源和每张图片的调整不受影响。")
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = resetManualOrder,
                            role = Role.Checkbox,
                            onValueChange = { resetManualOrder = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = resetManualOrder, onCheckedChange = null)
                        Text("同时重置手动排序")
                    }
                    if (resetManualOrder) {
                        Text("将清除保存的手动位置并切换为名称升序；当前画面仍保留，不自动换图。")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearHistory(resetManualOrder)
                    resetManualOrder = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false; resetManualOrder = false }) { Text("取消") } },
        )
    }

    // 选定一张后“调整播放位置”；目标已不在列表里（被停用、顺序已换）时关闭对话框
    moveTargetId?.let { id ->
        val position = uiState.queue.positions[id]
        val item = remember(id, uiState.queue) { uiState.queue.ordered.firstOrNull { it.id == id } }
        if (position == null || item == null || !uiState.queue.manual || uiState.multiReorder.enabled) {
            LaunchedEffect(id) { moveTargetId = null }
        } else {
            MoveToPositionDialog(
                fileName = item.displayName.ifBlank { item.relativePath },
                from = position,
                size = uiState.queue.size,
                onConfirm = { target -> moveTargetId = null; viewModel.moveItemTo(id, target) },
                onDismiss = { moveTargetId = null },
            )
        }
    }
    if (showMultiPosition) {
        if (!uiState.multiReorder.enabled || !uiState.multiReorder.canMoveTo) {
            LaunchedEffect(uiState.multiReorder) { showMultiPosition = false }
        } else {
            MultiMoveToPositionDialog(
                state = uiState.multiReorder,
                size = uiState.queue.size,
                onConfirm = { target -> showMultiPosition = false; viewModel.moveMultiItemsTo(target) },
                onDismiss = { showMultiPosition = false },
            )
        }
    }
}

@Composable
private fun CurrentImageSection(uiState: PlaybackUiState, wallpaperTarget: WallpaperTarget) {
    val pending = uiState.pendingSourceImage
    (pending ?: uiState.currentImage)?.let { image ->
        CurrentImageCard(
            imageUri = image.uri,
            imageName = image.fileName,
            resolution = image.resolution,
            fileSize = image.fileSize,
            title = if (pending != null) "新目录待播预览" else "当前图片",
            hint = if (pending != null) {
                if (wallpaperTarget == WallpaperTarget.HOME) "返回桌面成功显示后，才更新当前图片与历史。"
                else "锁屏成功显示后，才更新本域当前图片与历史。"
            } else null,
        )
    } ?: BlurredCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.l),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无图片",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 与旧 P2 页的状态行相同：生效图片数、来源（P4 规则或普通目录）、静态验收模式。 */
@Composable
private fun StatusSection(uiState: PlaybackUiState, busy: Boolean) {
    BlurredCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            val source = uiState.activeRuleName?.let { "日期规则“$it”" } ?: "普通目录"
            Text(
                text = "已启用图片：${uiState.enabledCount}（$source）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (uiState.staticTestMode) {
                Text("静态验收已开启：过渡与特效暂停（设置 → 高级）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * P9-PLAYBACK-01：设为壁纸在新 UI 完成，不再跳转旧 P2 页。
 * WallpaperUtil 已封装"直达预览／回落到系统选择器"的差异，与旧页用同一条路径。
 */
@Composable
private fun WallpaperSection(
    isActiveWallpaper: Boolean,
    hint: String?,
    onSetWallpaper: () -> Unit,
    onOtherRoutes: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        OutlinedButton(onClick = onSetWallpaper, modifier = Modifier.fillMaxWidth()) {
            Text(if (isActiveWallpaper) "重新设为壁纸" else "设为壁纸")
        }
        TextButton(onClick = onOtherRoutes, modifier = Modifier.fillMaxWidth()) {
            Text("系统页面未打开？选择其他入口")
        }
        if (hint != null) Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QueueTitleSection(uiState: PlaybackUiState, busy: Boolean, onLocateCurrent: () -> Unit, onMultiToggle: (Boolean) -> Unit) {
    val queue = uiState.queue
    BlurredCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "播放队列（共 ${queue.size} 张，${queue.groupCount} 组）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onLocateCurrent, enabled = queue.currentId != null) { Text("定位当前") }
            }
            if (queue.manual) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("多选调序", style = MaterialTheme.typography.titleSmall)
                    }
                    Switch(checked = uiState.multiReorder.enabled, onCheckedChange = onMultiToggle, enabled = !busy)
                }
            }
            Text(
                text = if (queue.manual) {
                    "点媒体展开操作，调序自动保存。"
                } else {
                    "点媒体展开操作。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (queue.size == 0) {
                Text(
                    text = if (uiState.enabledCount == 0) {
                        "队列为空。先在图库启用来源，再回到这里点右上角“重新载入”。"
                    } else {
                        "本轮播放次序尚未生成；点右上角“重新载入”或等壁纸切换下一张后再看。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DisabledPlaybackTitle(uiState: PlaybackUiState, busy: Boolean, onRestoreAll: () -> Unit) {
    BlurredCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已停用图片（${uiState.disabledPage.totalCount} 张）",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRestoreAll, enabled = !busy && uiState.disabledPage.totalCount > 0) {
                    Text("全部恢复")
                }
            }
            Text(if (uiState.disabledPage.totalCount == 0) "暂无逐张停用的图片。" else
                "点图片展开恢复；来源或子目录仍停用时不会加入播放队列。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.queueRows(
    uiState: PlaybackUiState,
    busy: Boolean,
    viewModel: PlaybackViewModel,
    expandedActionItemId: String?,
    onToggleActions: (String) -> Unit,
    onMoveTo: (String) -> Unit,
) {
    items(uiState.queueRows, key = { it.key }, contentType = { it::class }) { row ->
        when (row) {
            is QueueRow.GroupHeader -> QueueGroupHeaderRow(
                header = row,
                previewUri = SourcePreviewOverrides.lookup(uiState.previewOverrides, row.group.kind, row.group.sourceId)
                    ?: row.previewItem.id,
                enabled = !busy && !uiState.multiReorder.enabled,
                onMoveUp = { viewModel.moveGroup(row.group, -1) },
                onMoveDown = { viewModel.moveGroup(row.group, 1) },
            )
            is QueueRow.Level -> QueueLevelRow(row, onToggle = { viewModel.toggleLevel(row.levelKey) })
            is QueueRow.Image -> QueueImageRow(
                image = row,
                enabled = !busy,
                manual = uiState.queue.manual,
                multiMode = uiState.multiReorder.enabled,
                onMultiSelect = { viewModel.toggleMultiItem(row.item.id) },
                actionsExpanded = row.item.id == expandedActionItemId,
                onToggleActions = { onToggleActions(row.item.id) },
                onPlay = { viewModel.clickToPlay(row.item.id) },
                onSelect = { viewModel.selectItem(row.item.id) },
                onMoveUp = { viewModel.moveItem(row.item.id, -1) },
                onMoveDown = { viewModel.moveItem(row.item.id, 1) },
                onMoveTo = { onMoveTo(row.item.id) },
            )
            is QueueRow.More -> QueueMoreRow(row, onShowMore = { viewModel.showMoreRows(row.pageKey) })
        }
    }
}

/** 仅多选期间固定在顶栏下方；大图库跨目录勾选时无需滚回列表开头才能移动。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiReorderToolbar(
    state: MultiReorderUiState,
    busy: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onPosition: () -> Unit,
    onClear: () -> Unit,
    onFinish: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
            Text(
                if (state.unavailableCount > 0) "已选 ${state.selectedIds.size} 张，另 ${state.unavailableCount} 张已失活；请清空重选。"
                else "多选调序：已选 ${state.selectedIds.size} 张，块内保持原播放顺序。",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(onClick = onUp, enabled = !busy && state.canMoveUp) { Text("整体上移") }
                TextButton(onClick = onDown, enabled = !busy && state.canMoveDown) { Text("整体下移") }
                TextButton(onClick = onPosition, enabled = !busy && state.canMoveTo) { Text("调整整体位置") }
                TextButton(onClick = onClear, enabled = !busy && (state.selectedIds.isNotEmpty() || state.unavailableCount > 0)) { Text("清空选择") }
                TextButton(onClick = onFinish, enabled = !busy) { Text("结束多选") }
            }
        }
    }
}

@Composable
private fun MultiMoveToPositionDialog(state: MultiReorderUiState, size: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val target = parseQueuePosition(input, state.maximumStart)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整整体播放位置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("所选 ${state.selectedIds.size} 张按原播放顺序组成临时整体，共 $size 张。目标填写移动后第一张的播放序号；其他图片相对顺序保持。")
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit) },
                    label = { Text("块首图播放序号（1～${state.maximumStart}）") },
                    singleLine = true,
                    isError = input.isNotBlank() && target == null,
                )
                if (target != null) Text("移动后占第 $target～${target + state.selectedIds.size - 1} 张；仅此次移动聚合，不永久建组。")
            }
        },
        confirmButton = { TextButton(onClick = { target?.let(onConfirm) }, enabled = target != null) { Text("整体调整") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 目标已完整显示（且不在悬浮底栏留白里）就不滚；否则滚到它上方一行，能看到所在目录或上一张。 */
private suspend fun scrollToRow(listState: LazyListState, index: Int) {
    // 新行数量要等下一帧布局后才可见；最多等 1 秒，避免目标已消失时一直挂起
    withTimeoutOrNull(1_000) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > index } } ?: return
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
    val visibleEnd = info.viewportEndOffset - info.afterContentPadding
    if (visible != null && visible.offset >= info.viewportStartOffset && visible.offset + visible.size <= visibleEnd) return
    listState.animateScrollToItem((index - 1).coerceAtLeast(0))
}

private fun pickerHint(result: WallpaperUtil.PickerLaunchResult): String = when (result) {
    WallpaperUtil.PickerLaunchResult.DIRECT_PREVIEW ->
        "已请求打开本应用的壁纸预览；若未跳转，请点“系统页面未打开？选择其他入口”。"
    WallpaperUtil.PickerLaunchResult.LIVE_WALLPAPER_CHOOSER ->
        "已请求打开系统动态壁纸选择器，请选择本应用。"
    WallpaperUtil.PickerLaunchResult.WALLPAPER_SETTINGS ->
        "已请求打开系统通用壁纸入口，请选择动态壁纸。"
    WallpaperUtil.PickerLaunchResult.FAILED ->
        "系统拒绝或未提供这些入口，请使用“选择其他入口”查看本应用权限设置并反馈系统提示。"
}

/** 队列行之前的固定区块数：当前图片、设为壁纸、顺序、状态、队列标题。 */
private const val LeadingItemCount = 5

/** 与旧 P2 页相同：直达预览发出后这么快就回到本页，视为系统没有打开预览页。 */
private const val SilentReturnMillis = 1_500L

/**
 * 播放顺序枚举
 */
enum class PlayOrder(val displayName: String) {
    NATURAL_ASC("名称升序"),
    NATURAL_DESC("名称降序"),
    RANDOM("随机"),
    MANUAL("手动排序")
}
