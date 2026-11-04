package dev.abdus.apps.shimmer.media

import android.content.Context

object MediaPreferences {
    private const val FILE = "p11_media"
    private const val MODE = "motion_mode"
    fun restartOnLock(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("restart_on_lock", false)
    fun restartOnUnlock(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("restart_on_unlock", false)
    fun setRestartOnLock(context: Context, value: Boolean) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("restart_on_lock", value).apply() }
    fun setRestartOnUnlock(context: Context, value: Boolean) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("restart_on_unlock", value).apply() }
    fun motionMode(context: Context): MotionMode = runCatching {
        MotionMode.valueOf(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(MODE, MotionMode.LOOP.name)!!)
    }.getOrDefault(MotionMode.LOOP)
    fun setMotionMode(context: Context, mode: MotionMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(MODE, mode.name).apply()
        MediaPlaybackOwner.settingsChanged()
    }
}
