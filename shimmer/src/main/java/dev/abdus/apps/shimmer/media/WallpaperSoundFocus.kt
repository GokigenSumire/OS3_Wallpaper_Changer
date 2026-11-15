package dev.abdus.apps.shimmer.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Losing or being denied audio focus silences sound without stopping wallpaper frames. */
internal class WallpaperSoundFocus(context: Context, private val onAudible: (Boolean) -> Unit) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(AudioManager::class.java)
    private var active = false
    private var registered = false
    private var generation = 0
    private var request: AudioFocusRequest? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }
    fun start() {
        if (active) return
        active = true
        val ticket = ++generation
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener({ change ->
                if (active && generation == ticket) {
                    onAudible(change == AudioManager.AUDIOFOCUS_GAIN)
                    if (change == AudioManager.AUDIOFOCUS_LOSS) stop()
                }
            }, Handler(Looper.getMainLooper())).build()
        request = next
        try {
            app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            registered = true
            onAudible(manager?.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        } catch (_: RuntimeException) { stop() }
    }
    fun stop() {
        active = false
        generation++
        onAudible(false)
        if (registered) {
            app.unregisterReceiver(noisy)
            registered = false
        }
        request?.let { manager?.abandonAudioFocusRequest(it) }
        request = null
    }
}
