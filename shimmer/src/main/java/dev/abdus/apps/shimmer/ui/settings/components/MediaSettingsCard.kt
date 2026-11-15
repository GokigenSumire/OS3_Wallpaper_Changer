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
import dev.abdus.apps.shimmer.media.MediaKind
import dev.abdus.apps.shimmer.media.MotionMode
import dev.abdus.apps.shimmer.ui.components.BlurredCard

@Composable
fun MediaSettingsCard() {
    val context = LocalContext.current
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    fun setSound(kind: MediaKind, enabled: Boolean) {
        if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        MediaPreferences.setSoundEnabled(context, kind, enabled)
    }
    var selected by remember { mutableStateOf(MediaPreferences.motionMode(context)) }
    var restartLock by remember { mutableStateOf(MediaPreferences.restartOnLock(context)) }
    var restartUnlock by remember { mutableStateOf(MediaPreferences.restartOnUnlock(context)) }
    var videoSound by remember { mutableStateOf(MediaPreferences.soundEnabled(context, MediaKind.VIDEO)) }
    var motionSound by remember { mutableStateOf(MediaPreferences.soundEnabled(context, MediaKind.MOTION_PHOTO)) }
    val soundError by dev.abdus.apps.shimmer.media.MediaSoundService.error.collectAsState()
    dev.abdus.apps.shimmer.ui.navigation.RefreshOnResume {
        selected = MediaPreferences.motionMode(context)
        restartLock = MediaPreferences.restartOnLock(context)
        restartUnlock = MediaPreferences.restartOnUnlock(context)
        videoSound = MediaPreferences.soundEnabled(context, MediaKind.VIDEO)
        motionSound = MediaPreferences.soundEnabled(context, MediaKind.MOTION_PHOTO)
    }
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
            androidx.compose.material3.HorizontalDivider()
            SettingsToggleRow("视频声音", videoSound, { value ->
                videoSound = value; setSound(MediaKind.VIDEO, value)
            })
            SettingsToggleRow("动态照片声音", motionSound, { value ->
                motionSound = value; setSound(MediaKind.MOTION_PHOTO, value)
            }, enabled = selected != MotionMode.STILL,
                description = if (selected == MotionMode.STILL) "静态模式不播放声音，保留开关选择" else null)
            Text("使用媒体音量；开启后显示声音通知，可从通知关闭。仅可见壁纸发声，预览静音；文件须有音轨。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            soundError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.TextButton(onClick = { dev.abdus.apps.shimmer.media.MediaSoundService.sync(context) }) {
                    Text("重试开启声音")
                }
            }
            androidx.compose.material3.HorizontalDivider()
            SettingsToggleRow("锁屏后从头播放", restartLock, { value ->
                restartLock = value; MediaPreferences.setRestartOnLock(context, value)
            }, description = "重新播放当前媒体，不切换图片")
            SettingsToggleRow("解锁后从头播放", restartUnlock, { value ->
                restartUnlock = value; MediaPreferences.setRestartOnUnlock(context, value)
            })
            states.forEach { (target, entry) ->
                Text("${if (target == dev.abdus.apps.shimmer.p10.WallpaperTarget.HOME) "桌面" else "锁屏"}最近画面：${entry.label()}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
