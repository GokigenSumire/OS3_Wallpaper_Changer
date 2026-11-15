package dev.abdus.apps.shimmer.media

import android.app.Service
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MediaSoundServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @After fun reset() {
        MediaPreferences.setSoundEnabled(context, MediaKind.VIDEO, false)
        MediaPreferences.setSoundEnabled(context, MediaKind.MOTION_PHOTO, false)
    }
    @Test fun enabledSoundStartsMediaNotificationAndActionDisablesBothKinds() {
        MediaPreferences.setSoundEnabled(context, MediaKind.VIDEO, true)
        MediaPreferences.setSoundEnabled(context, MediaKind.MOTION_PHOTO, true)
        val controller = Robolectric.buildService(MediaSoundService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(MediaSoundService.isReady)
            val notification = shadowOf(service).lastForegroundNotification
            assertEquals("壁纸声音已开启", notification.extras.getString("android.title"))
            assertEquals("关闭声音", notification.actions.single().title.toString())
            service.onStartCommand(Intent().setAction(MediaSoundService.ACTION_DISABLE), 0, 2)
            assertFalse(MediaPreferences.anySoundEnabled(context))
            assertFalse(MediaSoundService.isReady)
        } finally { controller.destroy() }
    }
    @Test fun noSoundPreferenceNeverPromotesServiceAndStopKeepsPreferences() {
        val controller = Robolectric.buildService(MediaSoundService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1)
            assertFalse(MediaSoundService.isReady)
            assertNull(shadowOf(controller.get()).lastForegroundNotification)
            MediaPreferences.setSoundEnabled(context, MediaKind.VIDEO, true)
            controller.get().onStartCommand(null, 0, 2)
            assertTrue(MediaSoundService.isReady)
        } finally { controller.destroy() }
        assertFalse(MediaSoundService.isReady)
        assertTrue(MediaPreferences.soundEnabled(context, MediaKind.VIDEO))
    }
}
