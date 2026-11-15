package dev.abdus.apps.shimmer.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.abdus.apps.shimmer.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-enabled media playback foreground service permits audio focus after leaving the app. */
class MediaSoundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            MediaPreferences.setSoundEnabled(this, MediaKind.VIDEO, false)
            MediaPreferences.setSoundEnabled(this, MediaKind.MOTION_PHOTO, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!MediaPreferences.anySoundEnabled(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "壁纸声音", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, MediaSoundService::class.java).setAction(ACTION_DISABLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("壁纸声音已开启").setContentText("仅在壁纸可见时发声，点此管理")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "关闭声音", stop).build()).build()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            ready.value = true
            failure.value = null
            MediaPlaybackOwner.settingsChanged()
        } catch (_: RuntimeException) {
            failure.value = "声音通知未能启用，请重新开启声音"
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        ready.value = false
        MediaPlaybackOwner.settingsChanged()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "wallpaper_audio_v1"
        const val NOTIFICATION_ID = 11201
        const val ACTION_DISABLE = "personal.wallpaper.os3.DISABLE_WALLPAPER_SOUND"
        private val ready = MutableStateFlow(false)
        private val failure = MutableStateFlow<String?>(null)
        val isReady get() = ready.value
        val error = failure.asStateFlow()
        /** Called from an explicit toggle or resumed Activity; never starts from a hidden wallpaper. */
        fun sync(context: Context) {
            val app = context.applicationContext
            if (!MediaPreferences.anySoundEnabled(app)) {
                app.stopService(Intent(app, MediaSoundService::class.java))
                ready.value = false
                failure.value = null
                MediaPlaybackOwner.settingsChanged()
            } else if (!isReady) try {
                app.startForegroundService(Intent(app, MediaSoundService::class.java))
            } catch (_: RuntimeException) {
                failure.value = "声音通知未能启用，请重新开启声音"
            }
        }
    }
}
