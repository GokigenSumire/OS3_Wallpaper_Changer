package dev.abdus.apps.shimmer.ui.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import dev.abdus.apps.shimmer.ui.gallery.GalleryDirectoryRows
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

/**
 * P8.3 来源卡片组件
 * 卡片化设计；删除由调用方先弹出确认对话框。
 * 文件夹来源的子目录在卡片内逐级展开（整行点一次即展开/折叠），每层可单独启用或停用。
 */
@Composable
fun SourceCard(
    source: SourceItem,
    modifier: Modifier = Modifier,
    onToggleEnabled: () -> Unit = {},
    /** P9-GALLERY-04：重新扫描该来源，取代原先跳转旧 P1 页的"编辑"。 */
    onRescan: () -> Unit = {},
    /** P9-PREVIEW-EDIT-01：点预览图更换预览图；只改预览存储，不改来源与图片。 */
    onChangePreview: () -> Unit = {},
    /** 导入或扫描进行中时禁用操作，避免并发改同一来源。 */
    busy: Boolean = false,
    onDelete: () -> Unit = {},
    onToggleDirectory: (path: String) -> Unit = {},
    onSetDirectoryEnabled: (path: String, enabled: Boolean) -> Unit = { _, _ -> },
    onToggleBatch: () -> Unit = {},
    onMoveBatchPage: (delta: Int) -> Unit = {},
    onSetBatchImageEnabled: (uri: String, enabled: Boolean) -> Unit = { _, _ -> },
) {
    BlurredCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.m)
        ) {
            // 主信息行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标 + 名称
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // P9-GALLERY-01：有图片时显示预览缩略图，没有时回落到类型图标。
                    // 用 Coil 而非 P1 页的 PreviewThumbnailLoader——后者是为 View 体系的 ImageView
                    // 写的；Coil 在新 UI 已用于壁纸背景与当前图片卡，缓存与采样一并复用。
                    // P9-PREVIEW-EDIT-01：预览图本身就是"更换预览图"按钮，不画可见按钮；
                    // 触控区按 48dp 下限外扩，图片仍是 40dp，卡片高度不变。
                    val preview = source.previewUri
                    Box(
                        modifier = Modifier
                            .size(PreviewTouchSize)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "更换预览图",
                                onClick = onChangePreview,
                            )
                            .semantics { contentDescription = "更换预览图" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (preview != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(preview)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(PreviewSize)
                                    .clip(MaterialTheme.shapes.small),
                            )
                        } else {
                            Icon(
                                imageVector = when (source.type) {
                                    SourceType.FOLDER -> Icons.Default.Folder
                                    else -> Icons.Default.Image
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${source.kindLabel} · ${source.imageCount} 张图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = source.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 启用开关
                Switch(
                    checked = source.isEnabled,
                    onCheckedChange = { onToggleEnabled() }
                )
            }

            // 子目录：来源文件夹本身是最外层，默认折叠；规则与 P1 来源页相同
            if (source.type == SourceType.FOLDER && source.directoryRows.rows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.s))
                Text(
                    text = "${source.subdirectoryCount} 个子目录 · 停用上级会同时停用下级",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                source.directoryRows.rows.forEach { row ->
                    DirectoryRowItem(
                        row = row,
                        onToggle = { onToggleDirectory(row.path) },
                        onSetEnabled = { enabled -> onSetDirectoryEnabled(row.path, enabled) },
                    )
                }
                if (source.directoryRows.truncated) {
                    Text(
                        text = "只列出前 ${GalleryDirectoryRows.ROW_LIMIT} 行；请先折叠部分文件夹再展开要查看的层级。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (source.type == SourceType.MULTI_IMAGES) {
                Spacer(modifier = Modifier.height(Spacing.s))
                BatchImageSection(
                    page = source.batchPage,
                    sourceEnabled = source.isEnabled,
                    busy = busy,
                    onToggle = onToggleBatch,
                    onMovePage = onMoveBatchPage,
                    onSetEnabled = onSetBatchImageEnabled,
                )
            }

            // 操作按钮
            Spacer(modifier = Modifier.height(Spacing.m))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                OutlinedButton(
                    onClick = onRescan,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("重新扫描")
                }

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("删除")
                }
            }
        }
    }
}

/** 多选组固定分页，父来源停用时保留各图自身记录，但逐张开关不可操作。 */
@Composable
private fun BatchImageSection(
    page: GalleryBatchPage,
    sourceEnabled: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onMovePage: (Int) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = if (page.expanded) "折叠图片组" else "展开图片组",
                onClick = onToggle,
            )
            .semantics { stateDescription = if (page.expanded) "已展开" else "已折叠" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${if (page.expanded) "▼" else "▶"} 图片组 · 共 ${page.totalCount} 张 · 本图停用 ${page.stoppedCount} 张",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (!page.expanded) return
    Text(
        text = if (sourceEnabled) {
            "每页最多 50 张；逐张启停同步影响所有共享来源，关联与手动位置保留。"
        } else {
            "来源已停用，逐张开关由来源控制；重新启用来源后各图自身状态恢复。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
            Text(row.stateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val arrow = when {
            !row.hasChildren -> "　"
            row.expanded -> "▼"
            else -> "▶"
        }
        val rowModifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .let { base ->
                if (row.hasChildren) {
                    base
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (row.expanded) "折叠" else "展开",
                            onClick = onToggle
                        )
                        .semantics { stateDescription = if (row.expanded) "已展开" else "已折叠" }
                } else base
            }
            .padding(start = DirectoryIndentStep * row.depth.coerceAtMost(MaxIndentDepth))
        Box(modifier = rowModifier, contentAlignment = Alignment.CenterStart) {
            Text(
                text = "$arrow ${row.name}/ · 共 ${row.totalCount} 张 · ${row.stateLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.stopped || row.control == DirectoryControl.PARENT) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
        when (row.control) {
            DirectoryControl.SOURCE_SWITCH -> DirectoryControlNote("由来源开关控制")
            DirectoryControl.PARENT -> DirectoryControlNote("由上级控制")
            DirectoryControl.TOGGLE -> TextButton(onClick = { onSetEnabled(row.stopped) }) {
                Text(if (row.stopped) "启用" else "停用")
            }
        }
    }
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
