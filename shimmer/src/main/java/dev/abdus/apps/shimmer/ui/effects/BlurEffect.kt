package dev.abdus.apps.shimmer.ui.effects

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.abdus.apps.shimmer.ui.theme.AppearanceSettings

/**
 * 模糊背景与普通半透明表面的参数。
 *
 * 模糊只作用于页面最底层的壁纸背景（见 ui/components/WallpaperBackdrop.kt），
 * 卡片、顶栏、底栏是叠在其上的半透明表面。早期实现把 RenderEffect 加在卡片和底栏自身，
 * 模糊的是它们自己的文字和图标，而不是背后的内容，已废弃。
 *
 * 背景在解码时一次性做 CPU 模糊（[BoxBlur]），之后只是绘制一张小位图：滚动和切换 Tab
 * 不再逐帧计算模糊，Android 12 以下也有同样效果。
 */
object GlassEffectDefaults {
    /** 背景解码边长：只需模糊底图，越小越省内存；放大到全屏后本身也会变柔。 */
    const val BackdropDecodeSizePx = 160

    /** 在解码尺寸上的模糊半径与次数；三次盒式模糊近似高斯模糊。 */
    const val BackdropBlurRadiusPx = 4
    const val BackdropBlurPasses = 3

    /**
     * 背景上的遮罩不透明度。0.75 时浅色主题黑字、深色主题白字在纯白/纯黑壁纸上的
     * 估算对比度仍不低于 4.5:1；实际以目标机检查为准。
     *
     * P9-GLASS-01 起这三个值只是默认值，实际取值来自 [LocalAppearanceSettings]。
     */
    const val ScrimAlpha = AppearanceSettings.DEFAULT_SCRIM_ALPHA

    const val CardAlpha = AppearanceSettings.DEFAULT_CARD_ALPHA
    const val BottomBarAlpha = AppearanceSettings.DEFAULT_BOTTOM_BAR_ALPHA

    /** 半透明卡片底色。半透明卡片不加投影：阴影会透过卡片显示成灰边。 */
    @Composable
    fun cardContainerColor(): Color =
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = LocalAppearanceSettings.current.cardAlpha)

    @Composable
    fun bottomBarContainerColor(): Color =
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = LocalAppearanceSettings.current.bottomBarAlpha)

    @Composable
    fun scrimColor(): Color =
        MaterialTheme.colorScheme.background.copy(alpha = LocalAppearanceSettings.current.scrimAlpha)
}

/**
 * 当前生效的外观设置。
 *
 * 参考 KernelSU 的 `LocalEnableBlur` / `LocalEnableFloatingBottomBar` 做法：用 CompositionLocal
 * 把开关从顶层一次性提供给所有组件，避免逐层传参。默认值等于 [AppearanceSettings] 的默认值，
 * 所以未提供时的观感与版本 34 一致。
 */
val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }
