package dev.abdus.apps.shimmer.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.effects.LocalAppearanceSettings
import dev.abdus.apps.shimmer.ui.theme.Spacing

/**
 * P9-FLOAT-INSET-01：悬浮底栏开启时各页需要的底部内容留白。
 *
 * 用户在版本 38 实测"页面现在铺到屏幕底部，设置列表最后一项关于（还有图库的编辑/删除）落在药丸
 * 后面，希望页面上下仍按不开启悬浮时的滑动位置"。
 *
 * 起因是版本 38 为实现"真正悬浮"（`P9-FLOAT-OVERLAY-01`）把药丸从 Scaffold 的 `bottomBar` 槽移
 * 出——页面因此铺满整屏，但滚动范围没有相应延长，最后一项停在屏幕底边、正好被药丸挡住。
 *
 * 修法不是退回 `bottomBar` 槽（那会让药丸周围重新变成空白留白、丢掉已验证通过的"真正悬浮"），
 * 而是给各页滚动容器加底部留白：内容仍从药丸下方穿过，但能一直滚到最后一项露出药丸上方。
 *
 * 贴边形态返回 [Spacing.m]，与版本 34 一致——那时 Scaffold 已为底栏预留空间，无需额外留白。
 */
@Composable
fun tabContentPadding(): PaddingValues {
    val floating = LocalAppearanceSettings.current.floatingBottomBarEnabled
    return PaddingValues(
        start = Spacing.m,
        top = Spacing.m,
        end = Spacing.m,
        bottom = if (floating) FloatingBottomInset else Spacing.m,
    )
}

/**
 * 悬浮形态下的底部留白。
 *
 * 药丸高 48dp，外层另有 8dp 上下留白与系统导航栏内边距，再留 16dp 视觉间隙，取 88dp。
 */
private val FloatingBottomInset = 88.dp
