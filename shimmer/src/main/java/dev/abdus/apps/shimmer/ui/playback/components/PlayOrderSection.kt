package dev.abdus.apps.shimmer.ui.playback.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.playback.PlayOrder
import dev.abdus.apps.shimmer.ui.playback.PlayOrderControls
import dev.abdus.apps.shimmer.ui.playback.PlayOrderMode
import dev.abdus.apps.shimmer.ui.onboarding.GuideAnchor
import dev.abdus.apps.shimmer.ui.onboarding.guideAnchor
import dev.abdus.apps.shimmer.ui.theme.Spacing

/** Wrapping groups accommodate the extra modes on narrow and large-font screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayOrderSection(selectedOrder: PlayOrder, onOrderSelected: (PlayOrder) -> Unit,
                     modifier: Modifier = Modifier, enabled: Boolean = true) {
    val controls = PlayOrderControls.from(selectedOrder)
    BlurredCard(modifier = modifier.fillMaxWidth().guideAnchor(GuideAnchor.ORDER), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("播放顺序", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(selectedOrder.displayName, Modifier.widthIn(max = 180.dp),
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End,
                    maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayOrderMode.entries.forEach { mode ->
                    OrderChip(mode.label, controls.mode == mode, enabled) {
                        onOrderSelected(controls.copy(mode = mode).order())
                    }
                }
            }
            if (controls.mode == PlayOrderMode.TIME) {
                Text("时间对象", style = MaterialTheme.typography.labelMedium)
                FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrderChip("图片", !controls.folders, enabled) { onOrderSelected(controls.copy(folders = false).order()) }
                    OrderChip("文件夹", controls.folders, enabled) { onOrderSelected(controls.copy(folders = true).order()) }
                }
                FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrderChip("创建时间", controls.creation, enabled) { onOrderSelected(controls.copy(creation = true).order()) }
                    OrderChip("修改时间", !controls.creation, enabled) { onOrderSelected(controls.copy(creation = false).order()) }
                }
            }
            if (controls.mode == PlayOrderMode.NAME || controls.mode == PlayOrderMode.TIME) {
                FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val time = controls.mode == PlayOrderMode.TIME
                    OrderChip(if (time) "升序 · 旧到新" else "升序", !controls.descending, enabled) {
                        onOrderSelected(controls.copy(descending = false).order())
                    }
                    OrderChip(if (time) "降序 · 新到旧" else "降序", controls.descending, enabled) {
                        onOrderSelected(controls.copy(descending = true).order())
                    }
                }
            }
            if (controls.mode == PlayOrderMode.TIME) {
                Text(if (controls.folders) "目录逐层排序，目录内按名称排列；未知时间置后。" else "按文件原始时间排列，未知时间置后。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("已有来源请重新载入以读取时间。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OrderChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton })
}
