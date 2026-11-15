package dev.abdus.apps.shimmer.ui.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.gallery.DirectoryControl
import dev.abdus.apps.shimmer.ui.gallery.DirectoryRow
import dev.abdus.apps.shimmer.ui.gallery.GalleryBatchImageRow
import dev.abdus.apps.shimmer.ui.gallery.GalleryBatchPage
import dev.abdus.apps.shimmer.ui.gallery.SourceItem
import dev.abdus.apps.shimmer.ui.gallery.SourceType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.abdus.apps.shimmer.ui.theme.Spacing

/** 来源总开关、媒体类别与内容分层；次要操作收纳到菜单。 */
@Composable
fun SourceCard(
    source: SourceItem,
    modifier: Modifier = Modifier,
    onToggleEnabled: () -> Unit = {},
    onSetMediaEnabled: (video: Boolean, enabled: Boolean) -> Unit = { _, _ -> },
    onRescan: () -> Unit = {},
    onChangePreview: () -> Unit = {},
    busy: Boolean = false,
    onDelete: () -> Unit = {},
    onToggleDirectory: (path: String) -> Unit = {},
    onSetDirectoryEnabled: (path: String, enabled: Boolean) -> Unit = { _, _ -> },
    onToggleBatch: () -> Unit = {},
    onMoveBatchPage: (delta: Int) -> Unit = {},
    onSetBatchImageEnabled: (uri: String, enabled: Boolean) -> Unit = { _, _ -> },
) {
    var menu by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    BlurredCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(PreviewTouchSize).clip(MaterialTheme.shapes.small)
                    .clickable(enabled = !busy, role = Role.Button, onClickLabel = "更换预览图", onClick = onChangePreview)
                    .semantics { contentDescription = "更换预览图" }, contentAlignment = Alignment.Center) {
                    if (source.previewUri != null) AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(source.previewUri).crossfade(true).build(),
                        contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(PreviewSize).clip(MaterialTheme.shapes.small))
                    else Icon(if (source.type == SourceType.FOLDER) Icons.Default.Folder else Icons.Default.Image,
                        null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${source.kindLabel}  ${source.imageCount} 项" + if (source.isEnabled) "" else "  已停用",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = source.isEnabled, enabled = !busy, onCheckedChange = { onToggleEnabled() },
                    modifier = Modifier.semantics { contentDescription = "${source.name}来源开关" })
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "来源操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("重新扫描") }, enabled = !busy,
                            onClick = { menu = false; onRescan() })
                        DropdownMenuItem(text = { Text("更换预览图") }, enabled = !busy,
                            onClick = { menu = false; onChangePreview() })
                        DropdownMenuItem(text = { Text("位置与说明") }, onClick = { menu = false; showDetails = true })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("移除来源", color = MaterialTheme.colorScheme.error) }, enabled = !busy,
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (source.type == SourceType.FOLDER || source.type == SourceType.MULTI_IMAGES) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceMediaSwitch("图片", source.imageMediaCount, source.imagesEnabled, !busy,
                        Modifier.weight(1f)) { onSetMediaEnabled(false, it) }
                    SourceMediaSwitch("视频", source.videoMediaCount, source.videosEnabled, !busy,
                        Modifier.weight(1f)) { onSetMediaEnabled(true, it) }
                }
                if (source.unknownMediaCount > 0) Text("${source.unknownMediaCount} 项类型待确认，请重新扫描",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (source.type == SourceType.FOLDER && source.directoryRows.rows.isNotEmpty()) {
                HorizontalDivider()
                source.directoryRows.rows.forEach { row ->
                    DirectoryRowItem(row, onToggle = { onToggleDirectory(row.path) },
                        onSetEnabled = { onSetDirectoryEnabled(row.path, it) })
                }
                if (source.directoryRows.truncated) Text("请先折叠部分目录后继续",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (source.type == SourceType.MULTI_IMAGES) {
                HorizontalDivider()
                BatchImageSection(source.batchPage, source.isEnabled, busy, onToggleBatch, onMoveBatchPage, onSetBatchImageEnabled)
            }
        }
    }
    if (showDetails) AlertDialog(onDismissRequest = { showDetails = false }, title = { Text("来源信息") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer { Text(source.location) }
            if (source.type != SourceType.SINGLE_IMAGE) Text("动态照片归图片。分类从下次换图生效；关闭分类保留原内容和单项设置。")
        } }, confirmButton = { TextButton(onClick = { showDetails = false }) { Text("关闭") } })
}

@Composable
private fun BatchImageSection(
    page: GalleryBatchPage,
    sourceEnabled: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onMovePage: (Int) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
) {
    dev.abdus.apps.shimmer.ui.components.FolderLevelHeader(
        name = "内容列表", count = page.totalCount, depth = 0,
        expanded = page.expanded, expandable = page.totalCount > 0,
        state = if (page.stoppedCount > 0) "停用 ${page.stoppedCount} 项" else null,
        onToggle = onToggle,
    )
    if (!page.expanded) return
    page.rows.forEach { row ->
        BatchImageRowItem(row = row, busy = busy, onSetEnabled = { onSetEnabled(row.uri, it) })
    }
    if (page.pageCount > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onMovePage(-1) }, enabled = page.pageIndex > 0) { Text("上一页") }
            Text("第 ${page.pageIndex + 1} / ${page.pageCount} 页", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onMovePage(1) }, enabled = page.pageIndex + 1 < page.pageCount) { Text("下一页") }
        }
    }
}

@Composable
private fun BatchImageRowItem(row: GalleryBatchImageRow, busy: Boolean, onSetEnabled: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${row.position}. ${row.name}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.enabled || !row.canToggle) Text(row.stateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = row.enabled,
            enabled = row.canToggle && !busy,
            onCheckedChange = onSetEnabled,
            modifier = Modifier.semantics { contentDescription = "${row.name}的启用开关" },
        )
    }
}

/**
 * 一层文件夹：左侧整行（至少 48dp 高）点一次展开/折叠，右侧是本层自己的启用控制。
 * 受上级或来源开关控制的层不显示按钮，而是写明由谁控制，避免灰色按钮仍像可点。
 */
@Composable
private fun DirectoryRowItem(
    row: DirectoryRow,
    onToggle: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    dev.abdus.apps.shimmer.ui.components.FolderLevelHeader(
        name = row.name, count = row.totalCount, depth = row.depth,
        expanded = row.expanded, expandable = row.hasChildren, nested = true,
        state = row.stateLabel, onToggle = onToggle,
    ) {
        when (row.control) {
            DirectoryControl.SOURCE_SWITCH -> Unit
            DirectoryControl.PARENT -> DirectoryControlNote("上级控制")
            DirectoryControl.TOGGLE -> TextButton(onClick = { onSetEnabled(row.stopped) }) {
                Text(if (row.stopped) "启用" else "停用")
            }
        }
    }
}

@Composable
private fun SourceMediaSwitch(label: String, count: Int, checked: Boolean, enabled: Boolean,
                              modifier: Modifier, onChanged: (Boolean) -> Unit) {
    FilterChip(
        selected = checked, enabled = enabled, onClick = { onChanged(!checked) },
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "$label $count 项类型开关"
            stateDescription = if (checked) "已开启" else "已关闭"
        },
        label = { Text("$label $count", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (checked) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null,
        colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
    )
}

@Composable
private fun DirectoryControlNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.s)
    )
}

// 每层缩进 12dp，超过 6 层不再继续缩进，避免深层目录把文字挤出卡片
private val DirectoryIndentStep = 12.dp
private const val MaxIndentDepth = 6

/** 预览缩略图边长；与卡片左侧图标占位相当，不改变卡片高度。 */
private val PreviewSize = 40.dp

/** 预览图的触控区；48dp 是可点击目标的下限。 */
private val PreviewTouchSize = 48.dp
