package dev.abdus.apps.shimmer.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.media.MediaPreferences
import dev.abdus.apps.shimmer.media.MotionMode
import dev.abdus.apps.shimmer.ui.components.BlurredCard

@Composable
fun MediaSettingsCard() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(MediaPreferences.motionMode(context)) }
    var restartLock by remember { mutableStateOf(MediaPreferences.restartOnLock(context)) }
    var restartUnlock by remember { mutableStateOf(MediaPreferences.restartOnUnlock(context)) }
    val states by dev.abdus.apps.shimmer.media.MediaPlaybackStatus.states.collectAsState()
    BlurredCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("视频与动态照片", style = MaterialTheme.typography.titleMedium)
            Text("全局", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(MotionMode.LOOP to "循环播放动态部分", MotionMode.ONCE to "播放一次后回到原照片", MotionMode.STILL to "显示原始静态照片").forEach { (mode, label) ->
                val choose = { selected = mode; MediaPreferences.setMotionMode(context, mode) }
                Row(Modifier.fillMaxWidth().clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == mode, onClick = choose)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            listOf("锁屏后从头播放" to restartLock, "解锁后从头播放" to restartUnlock).forEachIndexed { index, (label, enabled) ->
                val change: (Boolean) -> Unit = { value ->
                    if (index == 0) { restartLock = value; MediaPreferences.setRestartOnLock(context, value) }
                    else { restartUnlock = value; MediaPreferences.setRestartOnUnlock(context, value) }
                }
                Row(Modifier.fillMaxWidth().clickable { change(!enabled) }, horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = change)
                }
            }
            states.forEach { (target, entry) ->
                Text("${if (target == dev.abdus.apps.shimmer.p10.WallpaperTarget.HOME) "桌面" else "锁屏"}最近画面：${entry.label()}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
