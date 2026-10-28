package dev.abdus.apps.shimmer.ui.playback.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.effects.GlassEffectDefaults
import dev.abdus.apps.shimmer.ui.gallery.GallerySourceSummary
import dev.abdus.apps.shimmer.ui.playback.QueueRow
import dev.abdus.apps.shimmer.ui.playback.PlaybackRowDisclosure
import dev.abdus.apps.shimmer.ui.playback.parseQueuePosition
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * P9-ORDER-GROUP-01：播放页队列的各类行。
 *
 * 版本 39 的队列"直接按图片播放顺序单张展开"。这里恢复旧 P2 完整播放控制页的组头、逐层目录、
 * 分页与"刚移动"高亮。每类行是 LazyColumn 的一项，滚动代价只与可见行数有关。
 *
 * 只有组头用玻璃卡片；目录层与图片行用同一透明度的纯色底，上千行时不为每行叠加玻璃效果。
 */

/** 组头：预览图、"第 N 组/共 M 组"、类型与张数、来源位置；手动顺序下可整组上移／下移。 */
@Composable
fun QueueGroupHeaderRow(
    header: QueueRow.GroupHeader,
    previewUri: String,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = highlightColors()
    val shape = MaterialTheme.shapes.large
    BlurredCard(
        modifier = modifier
            .fillMaxWidth()
            .then(if (header.highlighted) Modifier.border(2.dp, highlight.border, shape) else Modifier),
        shape = shape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            // 相邻组用不同的强调色条区分，取代旧页的整行底色，深色主题下同样可读
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = PreviewSize)
                    .clip(RoundedCornerShape(2.dp))
                    .background(groupAccent(header.group.sourceId)),
            )
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(previewUri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(PreviewSize).clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "第 ${header.groupIndex} 组/共 ${header.groupCount} 组",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(GallerySourceSummary.kindLabel(header.group.kind))
                        append(" · ${header.itemCount} 张")
                        if (header.continued) append("（续段，同一来源）")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = header.sourcePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (header.highlighted || header.containsCurrent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (header.containsCurrent) CurrentTag()
                        if (header.highlighted) MovedTag(highlight)
                    }
                }
            }
            if (header.canMoveUp || header.canMoveDown) {
                Column {
                    IconButton(onClick = onMoveUp, enabled = enabled && header.canMoveUp) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "整组上移")
                    }
                    IconButton(onClick = onMoveDown, enabled = enabled && header.canMoveDown) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "整组下移")
                    }
                }
            }
        }
    }
}

/**
 * 可折叠的一层。整行都可点击（版本 15 反馈"点击位置不明确"），并向读屏报告展开状态。
 */
@Composable
fun QueueLevelRow(
    level: QueueRow.Level,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = if (level.expanded) "已展开" else "已折叠"
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indentOf(level.depth))
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = if (level.expanded) "折叠" else "展开", role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state },
        color = GlassEffectDefaults.cardContainerColor(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(
                imageVector = if (level.expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${level.label} · 共 ${level.totalCount} 张",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (level.containsCurrent && !level.expanded) CurrentTag()
        }
    }
}

/**
 * 一张图片。主行点按只展开操作区；明确播放按钮和手动调序选择互不复用，防止浏览时误切图。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun QueueImageRow(
    image: QueueRow.Image,
    enabled: Boolean,
    manual: Boolean,
    multiMode: Boolean = false,
    onMultiSelect: () -> Unit = {},
    actionsExpanded: Boolean,
    onToggleActions: () -> Unit,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveTo: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时是停用段：沿用缩略图和展开行为，只给恢复操作，绝不播放或参与调序。 */
    restoreAction: (() -> Unit)? = null,
) {
    val actions = PlaybackRowDisclosure.actions(actionsExpanded, manual && !multiMode && restoreAction == null, image.selected)
    val highlight = highlightColors()
    val shape = MaterialTheme.shapes.medium
    val container = when {
        image.moved -> highlight.container
        image.selected -> MaterialTheme.colorScheme.primaryContainer
        else -> GlassEffectDefaults.cardContainerColor()
    }
    val content = when {
        image.moved -> highlight.content
        image.selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indentOf(image.depth))
            .then(if (image.moved) Modifier.border(2.dp, highlight.border, shape) else Modifier)
            .clip(shape)
            .clickable(enabled = enabled, onClickLabel = if (actionsExpanded) "收起图片操作" else "展开图片操作", role = Role.Button, onClick = onToggleActions)
            .semantics {
                stateDescription = if (actionsExpanded) "操作已展开" else "操作已收起"
            },
        color = container,
        contentColor = content,
        shape = shape,
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (multiMode && restoreAction == null) {
                    Checkbox(checked = image.selected, onCheckedChange = { onMultiSelect() }, enabled = enabled)
                }
                Box(modifier = Modifier.weight(1f)) {
                    ImageRowBody(image, enabled, content, actionsExpanded, actions.showReorderSelection,
                        onMoveUp, onMoveDown, disabled = restoreAction != null)
                }
            }
            if (actions.showPlay) {
                if (restoreAction != null) {
                    OutlinedButton(onClick = restoreAction, enabled = enabled) { Text("恢复启用") }
                } else if (!multiMode) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = onPlay, enabled = enabled) { Text("播放此图片") }
                    if (actions.showReorderSelection) {
                        OutlinedButton(onClick = onSelect, enabled = enabled) {
                            Text(if (image.selected) "取消调序选择" else "调序选择")
                        }
                    }
                }
                }
            }
            if (actions.showMoveToPosition) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    TextButton(onClick = onMoveTo, enabled = enabled) { Text("调整播放位置") }
                    TextButton(onClick = onSelect, enabled = enabled) { Text("取消选定") }
                }
            }
        }
    }
}

@Composable
private fun ImageRowBody(
    image: QueueRow.Image,
    enabled: Boolean,
    content: Color,
    actionsExpanded: Boolean,
    showReorderButtons: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    disabled: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            text = "${image.position}.",
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(image.item.id).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(ThumbSize).clip(MaterialTheme.shapes.small),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = image.item.displayName.ifBlank { image.item.relativePath },
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (image.item.relativePath.isNotBlank() && image.item.relativePath != image.item.displayName) {
                Text(
                    text = image.item.relativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (disabled || image.isCurrent || image.moved || image.selected) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (disabled) Tag("已停用", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    if (image.isCurrent) CurrentTag()
                    if (image.moved) MovedTag(highlightColors())
                    if (image.selected) Tag("已选定", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Icon(
            imageVector = if (actionsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = content.copy(alpha = 0.78f),
        )
        if (showReorderButtons && (image.canMoveUp || image.canMoveDown)) {
            IconButton(onClick = onMoveUp, enabled = enabled && image.canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = onMoveDown, enabled = enabled && image.canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
            }
        }
    }
}

/** 与旧页相同的分页按钮。 */
@Composable
fun QueueMoreRow(more: QueueRow.More, onShowMore: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onShowMore,
        modifier = modifier.fillMaxWidth().padding(start = indentOf(more.depth)),
    ) {
        Text("显示后续 ${minOf(more.remaining, dev.abdus.apps.shimmer.ui.playback.QueuePageSize)} 张（剩余 ${more.remaining} 张）")
    }
}

/**
 * 选定一张后输入目标位置。只接受 1..[size] 的整数；越界或非数字时提示，不静默截断到首尾。
 */
@Composable
fun MoveToPositionDialog(
    fileName: String,
    from: Int,
    size: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val target = parseQueuePosition(input, size)
    val showError = input.isNotBlank() && target == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整播放位置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("“$fileName”当前播放序号为 $from，共 $size 张。调整后其他图片相对顺序不变。")
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.filter(Char::isDigit) },
                    label = { Text("目标播放序号（1～$size）") },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text("请输入 1 到 $size 之间的整数") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let(onConfirm) }, enabled = target != null && target != from) {
                Text("调整")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(container)
            .padding(horizontal = Spacing.xs, vertical = 1.dp),
    )
}

@Composable
private fun CurrentTag() =
    Tag("正在播放", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)

@Composable
private fun MovedTag(colors: HighlightColors) = Tag("刚移动", colors.border, colors.onBorder)

private data class HighlightColors(val container: Color, val content: Color, val border: Color, val onBorder: Color)

/**
 * 旧页"刚移动"的琥珀色高亮。旧页的 rgb(180,85,0) 文字在 rgb(255,232,170) 上对比度约 4.1:1，
 * 低于正文 4.5:1，这里文字改用更深的棕色；深色主题换成深琥珀底、浅色字。
 */
@Composable
private fun highlightColors(): HighlightColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        HighlightColors(Color(0xFF4A2C00), Color(0xFFFFE8AA), Color(0xFFFFB050), Color(0xFF2A1800))
    } else {
        HighlightColors(Color(0xFFFFE8AA), Color(0xFF4A2600), Color(0xFFB45500), Color(0xFFFFFFFF))
    }
}

/** 相邻组的强调色；与旧页一样按来源取模，同一来源各段颜色相同。 */
@Composable
private fun groupAccent(sourceId: String): Color {
    val scheme = MaterialTheme.colorScheme
    return when (Math.floorMod(sourceId.hashCode(), 3)) {
        0 -> scheme.primary
        1 -> scheme.tertiary
        else -> scheme.secondary
    }
}

/** 每层缩进 14dp，最深按 6 层计，避免深层目录把文字挤成一列。 */
private fun indentOf(depth: Int): Dp = (14 * depth.coerceIn(0, 6)).dp

private val PreviewSize = 72.dp
private val ThumbSize = 44.dp
