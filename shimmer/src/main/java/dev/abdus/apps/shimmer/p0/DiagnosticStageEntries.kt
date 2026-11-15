package dev.abdus.apps.shimmer.p0

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import dev.abdus.apps.shimmer.MainActivity
import dev.abdus.apps.shimmer.p1.P1SourcesActivity
import dev.abdus.apps.shimmer.p2.P2PlaybackActivity
import dev.abdus.apps.shimmer.p3.P3EditActivity
import dev.abdus.apps.shimmer.p4.P4ScheduleActivity
import dev.abdus.apps.shimmer.p5.P5TransitionActivity
import dev.abdus.apps.shimmer.p10.capability.CapabilityActivity

data class MainEntry(val route: String = "gallery", val playbackSettings: Boolean = false) {
    val allowAutomaticGuide: Boolean get() = route == "gallery"
}
val LocalMainEntry = staticCompositionLocalOf { MainEntry() }

object DiagnosticStageEntries {
    data class Entry(val stage: Int, val title: String, val available: Boolean = true)
    val entries = listOf(Entry(0, "诊断记录"), Entry(1, "来源与数据"), Entry(2, "播放控制"),
        Entry(3, "桌面实时编辑"), Entry(4, "调度诊断"), Entry(5, "过渡诊断"),
        Entry(6, "Android回归", false), Entry(7, "个人试用", false),
        Entry(8, "主界面"), Entry(9, "特效设置"), Entry(10, "锁屏能力"), Entry(11, "媒体与声音"))
    private const val EXTRA_ROUTE = "personal.wallpaper.os3.diagnostic.ROUTE"
    private const val EXTRA_SETTINGS = "personal.wallpaper.os3.diagnostic.PLAYBACK_SETTINGS"
    fun intent(context: Context, stage: Int): Intent? = when (stage) {
        0 -> Intent(context, P0DiagnosticsActivity::class.java)
        1 -> Intent(context, P1SourcesActivity::class.java)
        2 -> Intent(context, P2PlaybackActivity::class.java)
        3 -> Intent(context, P3EditActivity::class.java)
        4 -> Intent(context, P4ScheduleActivity::class.java)
        5 -> Intent(context, P5TransitionActivity::class.java)
        8 -> Intent(context, MainActivity::class.java)
        9 -> Intent(context, MainActivity::class.java).putExtra(EXTRA_ROUTE, "effects")
        10 -> Intent(context, CapabilityActivity::class.java)
        11 -> Intent(context, MainActivity::class.java).putExtra(EXTRA_ROUTE, "playback").putExtra(EXTRA_SETTINGS, true)
        else -> null
    }
    fun readMainEntry(intent: Intent): MainEntry = try {
        val route = intent.getStringExtra(EXTRA_ROUTE)?.takeIf { it in setOf("gallery", "playback", "effects", "settings") } ?: "gallery"
        MainEntry(route, route == "playback" && intent.getBooleanExtra(EXTRA_SETTINGS, false))
    } catch (_: RuntimeException) { MainEntry() }
}
