package dev.abdus.apps.shimmer.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.p3.DisabledImageEntry
import dev.abdus.apps.shimmer.p3.DisabledImageList
import dev.abdus.apps.shimmer.p3.DisabledImageText
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * 已停用图片卡片：编辑页“启用：关”停用的图片在这里逐张或全部恢复。
 * 恢复只删除停用记录，不重新扫描来源；壁纸收到变化后按新目录播放。
 */
@Composable
fun DisabledImagesCard(
    totalCount: Int,
    entries: List<DisabledImageEntry>,
    onRestore: (DisabledImageEntry) -> Unit,
    onRestoreAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRestoreAll by remember { mutableStateOf(false) }

    BlurredCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            Text(
                text = DisabledImageText.SECTION_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (totalCount == 0) {
                Text(
                    text = "没有已停用的图片。在编辑页点“启用：开”可停用当前图片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            Text(
                text = "共 $totalCount 张；这些图片不出现在播放列表中，恢复后重新参与播放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { onRestore(entry) }) { Text("恢复") }
                }
            }
            if (totalCount > entries.size) {
                Text(
                    text = "只列出前 ${DisabledImageList.DISPLAY_LIMIT} 张；“全部恢复”会恢复全部 $totalCount 张。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { confirmRestoreAll = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("全部恢复（$totalCount 张）")
            }
        }
    }

    if (confirmRestoreAll) {
        AlertDialog(
            onDismissRequest = { confirmRestoreAll = false },
            title = { Text("恢复全部已停用图片？") },
            text = { Text("将恢复 $totalCount 张图片，它们会重新出现在播放列表中。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestoreAll = false
                    onRestoreAll()
                }) { Text("全部恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestoreAll = false }) { Text("取消") }
            }
        )
    }
}
