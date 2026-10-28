package dev.abdus.apps.shimmer.ui.gallery

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abdus.apps.shimmer.ui.gallery.components.SourceCard
import dev.abdus.apps.shimmer.ui.navigation.LegacyPages
import dev.abdus.apps.shimmer.ui.navigation.tabContentPadding
import dev.abdus.apps.shimmer.ui.navigation.RefreshOnResume
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.rememberCoroutineScope
import dev.abdus.apps.shimmer.p1.P1ScanProgress
import dev.abdus.apps.shimmer.ui.settings.rememberFolderPicker
import dev.abdus.apps.shimmer.ui.settings.rememberImagePicker
import kotlinx.coroutines.launch
import dev.abdus.apps.shimmer.ui.theme.Spacing
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.ui.navigation.wallpaperTargetViewModel

/**
 * P8.3 图库页面 - 来源管理
 * 卡片化概览，子目录在卡片上逐级展开并启用/停用；添加、重新扫描和更换预览图都在本页完成。
 *
 * [interactive] 为 false 时只作为返回手势背后的“上一页”预览：不监听回到前台、不消费提示消息，
 * 只在尚未载入时读一次来源。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    modifier: Modifier = Modifier,
    wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME,
    viewModel: GalleryViewModel = wallpaperTargetViewModel(wallpaperTarget, ::GalleryViewModel),
    interactive: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // The source waiting for delete confirmation; nothing is deleted until the dialog confirms.
    var pendingDelete by remember { mutableStateOf<SourceItem?>(null) }
    // P9-GALLERY-04：添加来源的类型选择与系统选取器
    var showAddSheet by remember { mutableStateOf(false) }
    val folderPicker = rememberFolderPicker()
    val imagePicker = rememberImagePicker()
    val scope = rememberCoroutineScope()

    if (interactive) {
        RefreshOnResume { viewModel.refresh() }

        // P9-GALLERY-03：启用、停用、删除的反馈改用 toast。
        // 原来的 Snackbar 悬在列表底部遮挡来源卡片，用户需要等它消失或手动划走才能继续操作。
        LaunchedEffect(uiState.message) {
            val message = uiState.message ?: return@LaunchedEffect
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.messageShown()
        }
    } else {
        LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("图库") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                // P9-GALLERY-04：添加来源在新 UI 完成，不再跳转旧 P1 页
                actions = {
                    IconButton(
                        onClick = { showAddSheet = true },
                        enabled = interactive && !uiState.isImporting,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加来源")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            // P9-GALLERY-04：导入／扫描进度与取消。目录扫描可能耗时很久（用户的 26599 张图库约
            // 1 分钟），没有进度与取消就只剩一个无反馈的等待。
            if (uiState.isImporting) {
                ImportProgressBar(
                    progress = uiState.importProgress,
                    onCancel = { viewModel.cancelImport() },
                )
            }
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.sources.isEmpty() -> {
                    EmptySourcesState(modifier = Modifier.fillMaxSize())
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // P9-FLOAT-INSET-01：悬浮底栏开启时底部多留白，最后一张卡片的操作区才不被药丸挡住
                        contentPadding = tabContentPadding(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.m)
                    ) {
                        items(uiState.sources, key = { it.id }) { source ->
                            SourceCard(
                                source = source,
                                onToggleEnabled = { viewModel.toggleSourceEnabled(source.id) },
                                onRescan = { viewModel.rescanSource(source.id) },
                                // P9-PREVIEW-EDIT-01：点预览图本身换图；选取器返回时已申请持久读取授权
                                onChangePreview = {
                                    scope.launch {
                                        imagePicker.pickSingle().firstOrNull()
                                            ?.let { viewModel.setCustomPreview(source.id, it) }
                                    }
                                },
                                busy = uiState.isImporting || !interactive,
                                onDelete = { pendingDelete = source },
                                onToggleDirectory = { path -> viewModel.toggleDirectoryExpanded(source.id, path) },
                                onSetDirectoryEnabled = { path, enabled ->
                                    viewModel.setDirectoryEnabled(source.id, path, enabled)
                                },
                                onToggleBatch = { viewModel.toggleBatchExpanded(source.id) },
                                onMoveBatchPage = { delta -> viewModel.moveBatchPage(source.id, delta) },
                                onSetBatchImageEnabled = { uri, enabled ->
                                    viewModel.setBatchImageEnabled(source.id, uri, enabled)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddSourceDialog(
            onDismiss = { showAddSheet = false },
            onPickDirectory = { includeSubdirectories ->
                showAddSheet = false
                scope.launch {
                    folderPicker.pick()?.let { viewModel.addDirectorySource(it, includeSubdirectories) }
                }
            },
            onPickSingle = {
                showAddSheet = false
                scope.launch {
                    imagePicker.pickSingle().firstOrNull()?.let { viewModel.addSingleImageSource(it) }
                }
            },
            onPickMultiple = {
                showAddSheet = false
                scope.launch {
                    imagePicker.pickMultiple().takeIf { it.isNotEmpty() }
                        ?.let { viewModel.addMultipleImageSources(it) }
                }
            },
        )
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除此来源？") },
            text = {
                Text(
                    "将移除${source.kindLabel}“${source.location}”及其关联。" +
                        "共享图像记录会保留，应用内副本文件不会立即物理删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteSource(source.id)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * P9-GALLERY-04：导入／扫描进度与取消。
 *
 * 目录扫描在大图库上可能持续很久（用户的 26599 张约 1 分钟），所以进度与取消是必需的，不是装饰。
 * 进度为 null 时（单图／多图导入没有逐节点进度）只显示不确定进度条。
 */
@Composable
private fun ImportProgressBar(
    progress: P1ScanProgress?,
    onCancel: () -> Unit,
) {
    val display = GalleryImportProgress.from(progress)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = display.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onCancel) { Text("取消") }
        }
        display.elapsedLabel?.let { label ->
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        display.currentPath?.let { path ->
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/**
 * P9-GALLERY-04：来源类型选择。
 *
 * 三种类型与旧 P1 页一致：目录、单张图片、多张图片。目录递归选项默认开启并传到实际导入。
 */
@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onPickDirectory: (includeSubdirectories: Boolean) -> Unit,
    onPickSingle: () -> Unit,
    onPickMultiple: () -> Unit,
) {
    var includeSubdirectories by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加图片来源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { includeSubdirectories = !includeSubdirectories },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeSubdirectories, onCheckedChange = { includeSubdirectories = it })
                    Text("目录包含下级子目录")
                }
                TextButton(onClick = { onPickDirectory(includeSubdirectories) }, modifier = Modifier.fillMaxWidth()) {
                    Text("文件夹目录")
                }
                TextButton(onClick = onPickSingle, modifier = Modifier.fillMaxWidth()) {
                    Text("单张图片")
                }
                TextButton(onClick = onPickMultiple, modifier = Modifier.fillMaxWidth()) {
                    Text("多张图片")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 空状态占位
 */
@Composable
private fun EmptySourcesState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            Text(
                text = "暂无图片来源",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "点击右上角 + 按钮添加图片来源",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 来源卡片数据
 */
data class SourceItem(
    val id: String,
    /** 来源表中的种类名（[dev.abdus.apps.shimmer.p1.P1SourceKind]），自选预览按它和 ID 存取。 */
    val kind: String = "",
    val name: String,
    val kindLabel: String,
    val location: String,
    val type: SourceType,
    val imageCount: Int,
    val isEnabled: Boolean,
    val subdirectoryCount: Int = 0,
    /** P9-GALLERY-01：卡片预览图的 URI；来源没有图片时为 null。 */
    val previewUri: String? = null,
    /** Folder rows shown on the card; empty for sources without subdirectories. */
    val directoryRows: DirectoryRows = DirectoryRows(emptyList(), truncated = false),
    /** 多选组默认折叠，展开后固定分页显示每张图片自己的启用状态。 */
    val batchPage: GalleryBatchPage = GalleryBatchPage(),
)

enum class SourceType {
    FOLDER,
    SINGLE_IMAGE,
    MULTI_IMAGES
}
