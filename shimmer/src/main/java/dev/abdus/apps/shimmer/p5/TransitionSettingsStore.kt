package dev.abdus.apps.shimmer.p5

import android.content.Context
import android.content.SharedPreferences
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `p5_transition` preferences: one JSON key with the transition settings, so a write is atomic.
 * An unreadable value falls back to the defaults (crossfade, 1500 ms), never a crash.
 */
class TransitionSettingsStore(context: Context, val target: WallpaperTarget = WallpaperTarget.HOME) {
    private val preferences = context.applicationContext.getSharedPreferences(nameFor(target), Context.MODE_PRIVATE)

    fun load(): TransitionSettings {
        val raw = preferences.getString(KEY_SETTINGS, null) ?: return TransitionSettings()
        return runCatching { json.decodeFromString(TransitionSettings.serializer(), raw).normalized() }
            .getOrDefault(TransitionSettings())
    }

    fun save(settings: TransitionSettings) {
        preferences.edit().putString(KEY_SETTINGS, json.encodeToString(settings.normalized())).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val NAME = "p5_transition"
        const val LOCK_NAME = "p10_lock_transition"
        const val KEY_SETTINGS = "settings"
        fun nameFor(target: WallpaperTarget): String = when (target) {
            WallpaperTarget.HOME -> NAME
            WallpaperTarget.LOCK -> LOCK_NAME
        }
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
