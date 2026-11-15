package dev.abdus.apps.shimmer.media

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaSoundPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun defaultIsSilentAndKindsPersistIndependently() {
        assertFalse(MediaPreferences.soundEnabled(context, MediaKind.VIDEO))
        assertFalse(MediaPreferences.soundEnabled(context, MediaKind.MOTION_PHOTO))
        MediaPreferences.setSoundEnabled(context, MediaKind.VIDEO, true)
        assertTrue(MediaPreferences.soundEnabled(context.applicationContext, MediaKind.VIDEO))
        assertFalse(MediaPreferences.soundEnabled(context, MediaKind.MOTION_PHOTO))
        MediaPreferences.setSoundEnabled(context, MediaKind.MOTION_PHOTO, true)
        MediaPreferences.setSoundEnabled(context, MediaKind.VIDEO, false)
        assertTrue(MediaPreferences.soundEnabled(context, MediaKind.MOTION_PHOTO))
    }
    @Test fun imageCannotEnableAudioAndCorruptPreferenceStaysSilent() {
        MediaPreferences.setSoundEnabled(context, MediaKind.IMAGE, true)
        assertFalse(MediaPreferences.soundEnabled(context, MediaKind.IMAGE))
        context.getSharedPreferences("p11_media", Context.MODE_PRIVATE).edit().putString("video_sound", "bad").commit()
        assertFalse(MediaPreferences.soundEnabled(context, MediaKind.VIDEO))
    }
}
