package dev.abdus.apps.shimmer.ui.effects.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.effects.EffectsMapping
import dev.abdus.apps.shimmer.ui.effects.TransitionEffect
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * 过渡效果设置卡片
 * 效果选择 + 时长滑块（P9-EFFECTS-02：1 ms 无极；点按数值可输入）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitionSettingsCard(
    selectedEffect: TransitionEffect,
    onEffectSelected: (TransitionEffect) -> Unit,
    duration: Int,
    onDurationChange: (Int) -> Unit,
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
                text = "过渡效果",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 效果选择下拉
            Column {
                Text(
                    text = "效果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.xs))

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedEffect.displayName,
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
                        TransitionEffect.values().forEach { effect ->
                            DropdownMenuItem(
                                text = { Text(effect.displayName) },
                                onClick = {
                                    onEffectSelected(effect)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // P9-EFFECTS-02/03：1 ms 步进的无极滑块；点按数值可直接输入
            Column {
                var showInput by remember { mutableStateOf(false) }
                // 跟随已保存值，手指按下时自由移动
                var position by remember(duration) { mutableFloatStateOf(duration.toFloat()) }
                val shown = EffectsMapping.durationAt(position)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "时长",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 点按数值打开输入框，可填任意受支持时长
                    TextButton(onClick = { showInput = true }) {
                        Text(
                            text = "$shown ms",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Slider(
                    value = position,
                    onValueChange = { position = it },
                    onValueChangeFinished = { onDurationChange(EffectsMapping.durationAt(position)) },
                    // steps = 0 时滑块连续取值；落点在 durationAt 里取整到 1 ms
                    valueRange = EffectsMapping.minDurationMillis.toFloat()..EffectsMapping.maxDurationMillis.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = "$shown 毫秒" }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${EffectsMapping.minDurationMillis} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${EffectsMapping.maxDurationMillis} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (showInput) {
                    DurationInputDialog(
                        current = shown,
                        onDismiss = { showInput = false },
                        onConfirm = { millis ->
                            showInput = false
                            position = millis.toFloat()
                            onDurationChange(millis)
                        }
                    )
                }
            }
        }
    }
}

/**
 * P9-EFFECTS-03：时长数值输入。
 *
 * 越界或非数字一律拒绝并提示，不静默截断——静默截断会让用户以为设置成功。
 */
@Composable
private fun DurationInputDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = EffectsMapping.parseDurationInput(text)
    val isBlank = text.isBlank()
    // 空输入不算错误，只是还没填；非空且解析不出合法值才提示
    val showError = !isBlank && parsed == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义过渡时长") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        // 只接受数字，避免把输入法的其他字符带进来
                        text = input.filter { it.isDigit() }.take(5)
                    },
                    label = { Text("毫秒") },
                    singleLine = true,
                    isError = showError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (showError) {
                        "请输入 ${EffectsMapping.minDurationMillis}～${EffectsMapping.maxDurationMillis} 之间的整数"
                    } else {
                        "可填 ${EffectsMapping.minDurationMillis}～${EffectsMapping.maxDurationMillis} ms，步进 1 ms"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (showError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
