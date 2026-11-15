package dev.abdus.apps.shimmer.media

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WallpaperSoundFocusTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val audio get() = context.getSystemService(AudioManager::class.java)
    @Test fun transientLossMutesAndGainRestoresButOldRequestsCannotReviveSound() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        var audible = false
        val focus = WallpaperSoundFocus(context) { audible = it }
        focus.start()
        val old = shadowOf(audio).lastAudioFocusRequest.listener
        old.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(audible)
        old.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(audible)
        focus.stop(); focus.start()
        old.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(audible)
        focus.stop()
        old.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(audible)
    }
    @Test fun deniedFocusKeepsSoundSilentAndStopAbandonsRequest() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        var audible = true
        val focus = WallpaperSoundFocus(context) { audible = it }
        focus.start()
        assertFalse(audible)
        focus.stop()
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }
    @Test fun grantedFocusIsReleasedOnHeadphoneDisconnection() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        var audible = false
        val focus = WallpaperSoundFocus(context) { audible = it }
        focus.start(); assertTrue(audible)
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(audible)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        focus.stop()
    }
}
