package dev.abdus.apps.shimmer.p4

import android.content.Context
import android.content.SharedPreferences
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `p4_schedule` preferences: settings, runtime state, the recent event log and the threshold
 * statistics, one JSON key each so every write is atomic. Unreadable values fall back to defaults
 * (scheduling off), never a crash.
 */
class ScheduleStore(context: Context, val target: WallpaperTarget = WallpaperTarget.HOME) {
    private val preferences = context.applicationContext.getSharedPreferences(nameFor(target), Context.MODE_PRIVATE)

    fun loadSettings(): ScheduleSettings = read(KEY_SETTINGS, ScheduleSettings.serializer()) ?: ScheduleSettings()

    fun saveSettings(settings: ScheduleSettings) {
        synchronized(lock) { preferences.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply() }
    }

    fun loadState(): ScheduleState = read(KEY_STATE, ScheduleState.serializer()) ?: ScheduleState()

    fun loadLog(): List<ScheduleEvent> = read(KEY_LOG, EVENTS) ?: emptyList()

    fun loadStats(): ScheduleStats = read(KEY_STATS, ScheduleStats.serializer()) ?: ScheduleStats()

    /** Saves [state] and appends [events] to the log and statistics in one commit. */
    fun commitRun(state: ScheduleState, events: List<ScheduleEvent>, now: Long) {
        synchronized(lock) {
            val edit = preferences.edit().putString(KEY_STATE, json.encodeToString(state))
            if (events.isNotEmpty()) {
                val log = (loadLog() + events).takeLast(LOG_CAP)
                var stats = loadStats().let { if (it.sinceWall == 0L) it.copy(sinceWall = now) else it }
                events.forEach { stats = stats.record(it) }
                edit.putString(KEY_LOG, json.encodeToString(EVENTS, log))
                edit.putString(KEY_STATS, json.encodeToString(stats))
            }
            edit.apply()
        }
    }

    /** Starts a new measurement: empties the log and statistics, keeps settings and state. */
    fun clearDiagnostics(now: Long) {
        synchronized(lock) {
            preferences.edit()
                .remove(KEY_LOG)
                .putString(KEY_STATS, json.encodeToString(ScheduleStats(sinceWall = now)))
                .apply()
        }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun <T> read(key: String, serializer: KSerializer<T>): T? = synchronized(lock) {
        val encoded = preferences.getString(key, null) ?: return@synchronized null
        runCatching { json.decodeFromString(serializer, encoded) }.getOrNull()
    }

    companion object {
        const val NAME = "p4_schedule"
        const val LOCK_NAME = "p10_lock_schedule"
        /** Existing settings/state/log remain HOME; LOCK has an independent initially empty file. */
        fun nameFor(target: WallpaperTarget): String = when (target) {
            WallpaperTarget.HOME -> NAME
            WallpaperTarget.LOCK -> LOCK_NAME
        }
        const val KEY_SETTINGS = "settings"
        const val KEY_STATE = "state"
        const val KEY_LOG = "log"
        const val KEY_STATS = "stats"
        const val LOG_CAP = 60
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = true }
        private val EVENTS = ListSerializer(ScheduleEvent.serializer())
    }
}
