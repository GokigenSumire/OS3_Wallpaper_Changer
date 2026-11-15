package dev.abdus.apps.shimmer.ui.playback.components

import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.media.*
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only a single expanded row probes bounded container metadata; no video decoder is started. */
@Composable
internal fun ItemSoundButton(uri: String, name: String, target: WallpaperTarget, enabled: Boolean) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val stop = MediaPreferences.observeSound(context) { revision++ }
        onDispose(stop)
    }
    val kind by produceState<MediaKind?>(null, uri, name) {
        value = withContext(Dispatchers.IO) {
            val parsed = Uri.parse(uri)
            val mime = runCatching { context.contentResolver.getType(parsed) }.getOrNull()
            when {
                MediaFileTypes.isVideo(name, mime) -> MediaKind.VIDEO
                MediaProbe.motionPhoto(context.contentResolver, parsed) != null -> MediaKind.MOTION_PHOTO
                else -> MediaKind.IMAGE
            }
        }
    }
    val media = kind ?: return
    // Revision also causes recomposition when a global switch changes outside this row.
    val global = remember(revision, media) { MediaPreferences.soundEnabled(context, media) }
    if (media == MediaKind.IMAGE || !global) return
    val soundOn = remember(revision, uri, target) { MediaPreferences.itemSoundEnabled(context, uri, target) }
    OutlinedIconToggleButton(checked = soundOn,
        onCheckedChange = { MediaPreferences.setItemSoundEnabled(context, uri, target, it) },
        enabled = enabled, modifier = Modifier.size(48.dp).semantics { stateDescription = if (soundOn) "声音开启" else "已静音" }) {
        Icon(if (soundOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            contentDescription = if (soundOn) "关闭此媒体声音" else "开启此媒体声音")
    }
}
