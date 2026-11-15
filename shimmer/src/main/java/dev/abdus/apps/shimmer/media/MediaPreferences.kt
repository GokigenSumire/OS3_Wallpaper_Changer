package dev.abdus.apps.shimmer.media

import android.content.Context
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import java.security.MessageDigest

object MediaPreferences {
    private const val FILE = "p11_media"
    private const val MODE = "motion_mode"
    private const val ITEM_FILE = "p11_item_sound"
    private fun itemKey(uri: String, target: WallpaperTarget): String = target.name + ":" +
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun itemSoundEnabled(context: Context, uri: String, target: WallpaperTarget): Boolean = try {
        context.getSharedPreferences(ITEM_FILE, Context.MODE_PRIVATE).getBoolean(itemKey(uri, target), true)
    } catch (_: ClassCastException) { false }
    fun setItemSoundEnabled(context: Context, uri: String, target: WallpaperTarget, enabled: Boolean) {
        require(uri.isNotBlank())
        context.getSharedPreferences(ITEM_FILE, Context.MODE_PRIVATE).edit().putBoolean(itemKey(uri, target), enabled).apply()
        MediaPlaybackOwner.settingsChanged()
    }
    /** UI observes both global switches and item overrides without opening original media. */
    fun observeSound(context: Context, changed: () -> Unit): () -> Unit {
        val global = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val items = context.getSharedPreferences(ITEM_FILE, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        global.registerOnSharedPreferenceChangeListener(listener)
        items.registerOnSharedPreferenceChangeListener(listener)
        return { global.unregisterOnSharedPreferenceChangeListener(listener); items.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    private fun soundKey(kind: MediaKind): String = when (kind) {
        MediaKind.VIDEO -> "video_sound"
        MediaKind.MOTION_PHOTO -> "motion_sound"
        else -> "still_sound"
    }
    fun soundEnabled(context: Context, kind: MediaKind): Boolean = try {
        kind != MediaKind.IMAGE && context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(soundKey(kind), false)
    } catch (_: ClassCastException) { false }
    fun setSoundEnabled(context: Context, kind: MediaKind, enabled: Boolean) {
        if (kind == MediaKind.IMAGE) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(soundKey(kind), enabled).apply()
        MediaSoundService.sync(context)
        MediaPlaybackOwner.settingsChanged()
    }
    fun anySoundEnabled(context: Context): Boolean = soundEnabled(context, MediaKind.VIDEO) || soundEnabled(context, MediaKind.MOTION_PHOTO)
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
