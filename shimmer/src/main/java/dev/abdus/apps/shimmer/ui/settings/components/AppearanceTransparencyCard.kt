package dev.abdus.apps.shimmer.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.theme.AppearanceSettings
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * P9-GLASS-01～03 界面质感设置。
 *
 * 三项透明度分别对应壁纸背景遮罩、卡片、底栏，与原先硬编码的三个常量一一对应。
 * 悬浮底栏仍独立可选，不再包含折射效果。
 */
@Composable
fun AppearanceTransparencyCard(
    settings: AppearanceSettings,
    onScrimAlphaChange: (Float) -> Unit,
    onCardAlphaChange: (Float) -> Unit,
    onBottomBarAlphaChange: (Float) -> Unit,
    onFloatingBottomBarToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
                text = "透明度与底栏",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            AlphaSlider(
                label = "背景遮罩",
                value = settings.scrimAlpha,
                onValueChange = onScrimAlphaChange,
            )
            AlphaSlider(
                label = "卡片",
                value = settings.cardAlpha,
                onValueChange = onCardAlphaChange,
            )
            AlphaSlider(
                label = "底栏",
                value = settings.bottomBarAlpha,
                onValueChange = onBottomBarAlphaChange,
            )

            Text(
                text = "透明度调得很低时，文字可能难以辨认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SwitchRow(
                label = "悬浮底栏",
                checked = settings.floatingBottomBarEnabled,
                onCheckedChange = onFloatingBottomBarToggle,
            )
        }
    }
}

@Composable
private fun AlphaSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    // 跟随已保存值，手指按下时自由移动；松手保存一次
    var position by remember(value) { mutableFloatStateOf(value) }
    val shown = AppearanceSettings.snapAlpha(position)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${AppearanceSettings.percentOf(shown)}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onValueChange(AppearanceSettings.snapAlpha(position)) },
            valueRange = AppearanceSettings.MIN_ALPHA..AppearanceSettings.MAX_ALPHA,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = "$label ${AppearanceSettings.percentOf(shown)}%" }
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
