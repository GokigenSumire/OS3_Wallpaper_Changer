package dev.abdus.apps.shimmer.ui.navigation

import androidx.annotation.StringRes
import dev.abdus.apps.shimmer.R

/**
 * 底栏导航目标定义
 * 四个主要 Tab：图库、播放、特效、设置
 */
sealed class NavigationDestination(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: String, // Material Symbol 名称，仅作标识；底栏图标见 MainNavigation.iconOf
) {
    object Gallery : NavigationDestination(
        route = "gallery",
        titleRes = R.string.nav_gallery,
        icon = "photo_library"
    )

    object Playback : NavigationDestination(
        route = "playback",
        titleRes = R.string.nav_playback,
        icon = "play_circle"
    )

    object Effects : NavigationDestination(
        route = "effects",
        titleRes = R.string.nav_effects,
        icon = "auto_awesome"
    )

    object Settings : NavigationDestination(
        route = "settings",
        titleRes = R.string.nav_settings,
        icon = "settings"
    )

    companion object {
        val items: List<NavigationDestination>
            get() = listOf(Gallery, Playback, Effects, Settings)
    }
}
