package dev.abdus.apps.shimmer.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** P8 设计规范 3.1 的取值与 WCAG 对比度。 */
class ThemeContrastTest {

    @Test
    fun specifiedRolesUseTheSpecValues() {
        // P8 纠正：原规范 #1A73E8 在透明卡片上所有壁纸均不足 4.5:1，改用 #174EA6
        assertEquals(Color(0xFF174EA6), primaryLight)
        assertEquals(Color(0xFF8AB4F8), primaryDark)
        assertEquals(Color(0xFF5F6368), secondaryLight)
        assertEquals(Color(0xFFE8EAED), secondaryDark)
        assertEquals(Color(0xFFFFFFFF), surfaceLight)
        assertEquals(Color(0xFF1F1F1F), surfaceDark)
        assertEquals(Color(0xFF202124), onSurfaceLight)
        assertEquals(Color(0xFFE8EAED), onSurfaceDark)
        // 规范的 Outline 用于分隔线（outlineVariant），见下面的对比度说明
        assertEquals(Color(0xFFDADCE0), outlineVariantLight)
        assertEquals(Color(0xFF5F6368), outlineVariantDark)
    }

    @Test
    fun textRolesReachFourPointFiveToOneOnCards() {
        val pairs = listOf(
            "浅色 onSurface" to (onSurfaceLight to surfaceLight),
            "浅色 onSurfaceVariant" to (onSurfaceVariantLight to surfaceLight),
            "浅色 onSurfaceVariant/背景" to (onSurfaceVariantLight to backgroundLight),
            "浅色 primary" to (primaryLight to surfaceLight),
            "浅色 onPrimary" to (onPrimaryLight to primaryLight),
            "浅色 error" to (errorLight to surfaceLight),
            "深色 onSurface" to (onSurfaceDark to surfaceDark),
            "深色 onSurfaceVariant" to (onSurfaceVariantDark to surfaceDark),
            "深色 onSurfaceVariant/背景" to (onSurfaceVariantDark to backgroundDark),
            "深色 primary" to (primaryDark to surfaceDark),
            "深色 onPrimary" to (onPrimaryDark to primaryDark),
            "深色 error" to (errorDark to surfaceDark),
        )
        for ((name, pair) in pairs) {
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name 对比度 $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun controlBordersReachThreeToOne() {
        // 开关、单选框边框用 outline；规范值 #DADCE0 / #5F6368 在卡片上只有约 1.4 / 2.7
        assertTrue(contrast(outlineLight, surfaceLight) >= 3.0)
        assertTrue(contrast(outlineDark, surfaceDark) >= 3.0)
        assertTrue(contrast(Color(0xFFDADCE0), surfaceLight) < 3.0)
        assertTrue(contrast(Color(0xFF5F6368), surfaceDark) < 3.0)
    }

    @Test
    fun primaryReachesFourPointFiveOnCompositedCard() {
        // P8 纠正：BlurredCard 用 surfaceContainer@0.8 叠加在 background@0.75 和壁纸上。
        // 测试黑色与白色壁纸的合成背景，确保 primary 达到 4.5:1。
        val cardAlpha = 0.8f
        val scrimAlpha = 0.75f
        val blackWallpaper = Color(0xFF000000)
        val whiteWallpaper = Color(0xFFFFFFFF)

        // 浅色主题：surfaceContainer #FFFFFF @ 0.8 over background #F1F3F4 @ 0.75 over wallpaper
        val lightScrimOverBlack = composite(backgroundLight, scrimAlpha, blackWallpaper)
        val lightCardOverBlack = composite(surfaceContainerLight, cardAlpha, lightScrimOverBlack)
        val lightRatioBlack = contrast(primaryLight, lightCardOverBlack)
        assertTrue("浅色 primary 在黑色壁纸合成卡片上 $lightRatioBlack < 4.5", lightRatioBlack >= 4.5)

        val lightScrimOverWhite = composite(backgroundLight, scrimAlpha, whiteWallpaper)
        val lightCardOverWhite = composite(surfaceContainerLight, cardAlpha, lightScrimOverWhite)
        val lightRatioWhite = contrast(primaryLight, lightCardOverWhite)
        assertTrue("浅色 primary 在白色壁纸合成卡片上 $lightRatioWhite < 4.5", lightRatioWhite >= 4.5)

        // 深色主题同理
        val darkScrimOverBlack = composite(backgroundDark, scrimAlpha, blackWallpaper)
        val darkCardOverBlack = composite(surfaceContainerDark, cardAlpha, darkScrimOverBlack)
        val darkRatioBlack = contrast(primaryDark, darkCardOverBlack)
        assertTrue("深色 primary 在黑色壁纸合成卡片上 $darkRatioBlack < 4.5", darkRatioBlack >= 4.5)

        val darkScrimOverWhite = composite(backgroundDark, scrimAlpha, whiteWallpaper)
        val darkCardOverWhite = composite(surfaceContainerDark, cardAlpha, darkScrimOverWhite)
        val darkRatioWhite = contrast(primaryDark, darkCardOverWhite)
        assertTrue("深色 primary 在白色壁纸合成卡片上 $darkRatioWhite < 4.5", darkRatioWhite >= 4.5)
    }

    private fun composite(foreground: Color, alpha: Float, background: Color): Color {
        val a = alpha
        val r = foreground.red * a + background.red * (1f - a)
        val g = foreground.green * a + background.green * (1f - a)
        val b = foreground.blue * a + background.blue * (1f - a)
        return Color(r, g, b)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
