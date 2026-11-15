package dev.abdus.apps.shimmer.ui.onboarding

import android.content.Context

/** Introduction acknowledgement is independent of wallpaper, target and permission state. */
class FirstUseGuideStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldShow(): Boolean = try {
        prefs.getInt(KEY_VERSION, 0) < VERSION
    } catch (_: ClassCastException) {
        true
    }

    fun complete(): Boolean = prefs.edit().putInt(KEY_VERSION, VERSION).commit()

    companion object {
        const val PREFS_NAME = "first_use_guide"
        const val KEY_VERSION = "acknowledged_version"
        const val VERSION = 4
    }
}
