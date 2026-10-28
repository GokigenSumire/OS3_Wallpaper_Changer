package dev.abdus.apps.shimmer.ui.playback.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.playback.PlayOrder
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * 播放顺序选择区域
 */
@Composable
fun PlayOrderSection(
    selectedOrder: PlayOrder,
    onOrderSelected: (PlayOrder) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BlurredCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            Text(
                text = "播放顺序",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 整行可点，读屏按单选组朗读；保存期间禁用，避免连续切换互相覆盖
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                PlayOrder.entries.forEach { order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selectedOrder == order,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onOrderSelected(order) }
                            ),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedOrder == order,
                            onClick = null,
                            enabled = enabled
                        )
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(
                            text = order.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedOrder == order) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}
