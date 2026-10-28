package dev.abdus.apps.shimmer.ui.effects.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.effects.FitMode
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * 全局设置卡片
 * 适配方式 + 模糊填充 + 新图继承上一张的调整
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsCard(
    selectedFitMode: FitMode,
    onFitModeSelected: (FitMode) -> Unit,
    blurFillEnabled: Boolean,
    onBlurFillToggle: (Boolean) -> Unit,
    inheritPreviousEnabled: Boolean,
    onInheritPreviousToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

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
                text = "本域默认",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 适配方式下拉选择
            Column {
                Text(
                    text = "适配方式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.xs))

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedFitMode.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        FitMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
                                    onFitModeSelected(mode)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // 模糊填充滑块开关
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "模糊填充",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = blurFillEnabled,
                        onCheckedChange = onBlurFillToggle
                    )
                }

            }

            // P9-PLAYBACK-ENTRIES-01：旧 P2 页 P3 区的第三个开关，版本 39 未搬入
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "新图继承上一张的调整",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = inheritPreviousEnabled,
                        onCheckedChange = onInheritPreviousToggle
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "向前播放新图时继承调整，只记入本次播放历史，不改图片已保存设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
