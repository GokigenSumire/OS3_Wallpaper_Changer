package dev.abdus.apps.shimmer.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.p10.WallpaperTarget

internal val gestureSteps = setOf(GuideStep.GESTURE_NEXT, GuideStep.GESTURE_PREVIOUS, GuideStep.GESTURE_EDIT)

/** Hands practice to the actual wallpaper; no synthetic recognition can complete this step. */
@Composable
internal fun GesturePractice(ready: Boolean, armed: Boolean, target: WallpaperTarget, onBegin: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when {
            ready -> "✓ 已确认真实画面切换／编辑进入"
            armed -> "等待实际操作结果；返回应用后可继续，未完成可重新开始。"
            else -> "练习目标：${if (target == WallpaperTarget.HOME) "桌面" else "锁屏"}"
        }, style = MaterialTheme.typography.bodyMedium,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (!ready) Button(onClick = onBegin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (armed) "重新开始实际练习" else "开始实际练习")
        }
    }
}
