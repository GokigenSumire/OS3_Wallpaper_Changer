package dev.abdus.apps.shimmer.ui.effects.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import dev.abdus.apps.shimmer.p5.SlideDirectionChoice
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * P9-EFFECTS-01：滑动方向设置。
 *
 * 原先只在旧 P5 过渡页可改，新 UI 靠"过渡诊断与更多设置"按钮跳转过去；该跳转已消除，方向改在特效
 * 页设置。文案与旧页一致，避免两处说法不同。
 *
 * 只有"方向滑动"效果用得到这个设置，但不隐藏——隐藏会让用户以为功能缺失；改为在说明里写清。
 */
@Composable
fun SlideDirectionCard(
    selected: SlideDirectionChoice,
    onSelected: (SlideDirectionChoice) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = "滑动方向",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "只在过渡效果为“方向滑动”时生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SlideDirectionChoice.entries.forEach { choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = choice == selected,
                            role = Role.RadioButton,
                            onClick = { onSelected(choice) },
                        )
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    RadioButton(selected = choice == selected, onClick = null)
                    Text(
                        text = choice.label(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 与旧 P5 过渡页相同的中文标签。 */
private fun SlideDirectionChoice.label(): String = when (this) {
    SlideDirectionChoice.FOLLOW -> "跟随播放方向"
    SlideDirectionChoice.LEFT -> "向左（新图从右侧进入）"
    SlideDirectionChoice.RIGHT -> "向右（新图从左侧进入）"
    SlideDirectionChoice.UP -> "向上（新图从下方进入）"
    SlideDirectionChoice.DOWN -> "向下（新图从上方进入）"
    SlideDirectionChoice.RANDOM -> "每次随机"
}
