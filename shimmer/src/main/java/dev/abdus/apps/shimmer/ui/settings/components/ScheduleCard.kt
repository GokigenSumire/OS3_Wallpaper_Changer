package dev.abdus.apps.shimmer.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * 调度设置卡片（简化版）
 * 常用开关 + 进入 P4 调度页修改间隔数值、单位、计时方式和规则
 */
@Composable
fun ScheduleCard(
    intervalEnabled: Boolean,
    onIntervalToggle: (Boolean) -> Unit,
    intervalLabel: String,
    unlockEnabled: Boolean,
    onUnlockToggle: (Boolean) -> Unit,
    onMoreScheduleClick: () -> Unit,
    modifier: Modifier = Modifier,
    lockEnabled: Boolean = false,
    onLockToggle: (Boolean) -> Unit = {},
    screenOffEnabled: Boolean = false,
    onScreenOffToggle: (Boolean) -> Unit = {},
    onDateRulesClick: (() -> Unit)? = null,
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
                text = "自动切换",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 间隔切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "间隔切换",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (intervalEnabled) {
                        Text(
                            text = intervalLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = intervalEnabled,
                    onCheckedChange = onIntervalToggle
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("锁屏后切换", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = lockEnabled, onCheckedChange = onLockToggle)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("熄屏后切换", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = screenOffEnabled, onCheckedChange = onScreenOffToggle)
            }
            // 三种锁周期事件在本域内合并，另一域独立。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "解锁切换",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = unlockEnabled,
                    onCheckedChange = onUnlockToggle
                )
            }

            Divider()
            Text(
                "每个锁屏周期本域至多切换一次；不可见时待下次显示生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 更多调度设置入口
            TextButton(
                onClick = onMoreScheduleClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "间隔与计时方式",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null
                    )
                }
            }
            onDateRulesClick?.let { open ->
                TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Text("每日定时与日期规则")
                }
            }
        }
    }
}
