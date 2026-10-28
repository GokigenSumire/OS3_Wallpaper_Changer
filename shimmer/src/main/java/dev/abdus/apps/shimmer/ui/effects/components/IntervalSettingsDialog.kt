package dev.abdus.apps.shimmer.ui.effects.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import dev.abdus.apps.shimmer.p4.IntervalClock
import dev.abdus.apps.shimmer.p4.IntervalSettings
import dev.abdus.apps.shimmer.p4.IntervalUnit
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * P9-EFFECTS-01：间隔数值、单位与计时方式。
 *
 * 原先只在旧 P4 调度页可改，新 UI 靠"更多调度设置"按钮跳转过去；该跳转已消除。
 *
 * 越界输入在确定前就被拒绝（禁用确定按钮并提示），不静默夹取——静默夹取会让用户以为设置成功。
 * 有效范围由 [IntervalSettings.MIN_MILLIS]／[IntervalSettings.MAX_MILLIS] 定义，与存储层一致。
 */
@Composable
fun IntervalSettingsDialog(
    value: Int,
    unit: IntervalUnit,
    clock: IntervalClock,
    onDismiss: () -> Unit,
    onConfirm: (Int, IntervalUnit, IntervalClock) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var pickedUnit by remember { mutableStateOf(unit) }
    var pickedClock by remember { mutableStateOf(clock) }

    val parsed = text.trim().toIntOrNull()
    val candidate = parsed?.let {
        IntervalSettings(value = it, unit = pickedUnit, clock = pickedClock)
    }
    val valid = candidate?.valid == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换间隔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(5) },
                    label = { Text("数值") },
                    singleLine = true,
                    isError = text.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "单位",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IntervalUnit.entries.forEach { candidateUnit ->
                    ChoiceRow(
                        label = candidateUnit.label,
                        selected = candidateUnit == pickedUnit,
                        onClick = { pickedUnit = candidateUnit },
                    )
                }

                Text(
                    text = "计时方式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IntervalClock.entries.forEach { candidateClock ->
                    ChoiceRow(
                        label = candidateClock.label(),
                        selected = candidateClock == pickedClock,
                        onClick = { pickedClock = candidateClock },
                    )
                }

                Text(
                    text = if (text.isNotBlank() && !valid) {
                        "间隔须在 10 秒到 24 小时之间"
                    } else {
                        "可设 10 秒到 24 小时。可见累计只在壁纸可见时计时；自然时间不论可见与否都计时。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (text.isNotBlank() && !valid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(it, pickedUnit, pickedClock) } },
                enabled = valid,
            ) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 与旧 P4 调度页相同的中文标签。 */
private fun IntervalClock.label(): String = when (this) {
    IntervalClock.VISIBLE -> "可见累计"
    IntervalClock.WALL -> "自然时间"
}
