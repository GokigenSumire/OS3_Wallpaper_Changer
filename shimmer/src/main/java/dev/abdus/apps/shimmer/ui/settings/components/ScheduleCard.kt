package dev.abdus.apps.shimmer.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.theme.Spacing

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
    paused: Boolean = false,
    onPauseToggle: (Boolean) -> Unit = {},
    clockLabel: String = "",
    rulesLabel: String = "",
) {
    BlurredCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动切换", style = MaterialTheme.typography.titleMedium)
                    Text(if (paused) "已暂停 · 保留以下设置" else "按以下条件运行",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = !paused, onCheckedChange = { onPauseToggle(!it) },
                    modifier = Modifier.semantics { contentDescription = "自动切换总开关" })
            }
            HorizontalDivider()
            SettingsToggleRow("间隔切换", intervalEnabled, onIntervalToggle,
                description = if (intervalEnabled) "$intervalLabel · $clockLabel" else "未开启 · 已保存$intervalLabel",
                enabled = !paused)
            SettingsActionRow("设置间隔与计时方式", "$intervalLabel · $clockLabel", onMoreScheduleClick)
            HorizontalDivider()
            Text("锁屏周期", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SettingsToggleRow("锁屏后切换", lockEnabled, onLockToggle, enabled = !paused)
            SettingsToggleRow("熄屏后切换", screenOffEnabled, onScreenOffToggle, enabled = !paused)
            SettingsToggleRow("解锁后切换", unlockEnabled, onUnlockToggle, enabled = !paused)
            Text("多个条件开启时，先发生的条件生效；同一锁屏周期只切换一次，不可见时延后显示。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onDateRulesClick?.let { open ->
                HorizontalDivider()
                SettingsActionRow("每日定时与日期规则", rulesLabel, open)
            }
        }
    }
}
