package dev.abdus.apps.shimmer.p4

import android.content.Context
import android.content.Intent
import dev.abdus.apps.shimmer.p10.WallpaperTarget

/** 显式目标只读路由；旧无参数入口属于 HOME，损坏参数必须拒绝而非落入 HOME。 */
object SchedulePageTarget {
    const val EXTRA_TARGET = "personal.wallpaper.os3.schedule.TARGET"

    fun createIntent(context: Context, target: WallpaperTarget): Intent =
        Intent(context, P4ScheduleActivity::class.java).putExtra(EXTRA_TARGET, target.name)

    fun read(intent: Intent): WallpaperTarget? {
        if (!intent.hasExtra(EXTRA_TARGET)) return WallpaperTarget.HOME
        val value = runCatching { intent.getStringExtra(EXTRA_TARGET) }.getOrNull() ?: return null
        return WallpaperTarget.entries.firstOrNull { it.name == value }
    }
}
