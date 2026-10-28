package dev.abdus.apps.shimmer.ui.effects

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * 对 ARGB_8888 像素做可分离盒式模糊，多次叠加近似高斯模糊。
 * 纯函数，不依赖 Android 位图，便于单测；边缘按最近像素延伸，四周不会发暗。
 */
object BoxBlur {
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height) { "像素数与尺寸不符" }
        if (radius <= 0 || passes <= 0) return pixels.copyOf()
        val src = pixels.copyOf()
        val tmp = IntArray(pixels.size)
        repeat(passes) {
            // 横向 src→tmp，纵向 tmp→src；每轮结束结果都在 src
            pass(src, tmp, count = height, length = width, lineStride = width, step = 1, radius = radius)
            pass(tmp, src, count = width, length = height, lineStride = 1, step = width, radius = radius)
        }
        return src
    }

    /** 沿一个方向做滑动窗口平均：共 [count] 条线，每条 [length] 个像素。 */
    private fun pass(src: IntArray, dst: IntArray, count: Int, length: Int, lineStride: Int, step: Int, radius: Int) {
        val window = 2 * radius + 1
        val last = length - 1
        for (line in 0 until count) {
            val base = line * lineStride
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (k in -radius..radius) {
                val p = src[base + k.coerceIn(0, last) * step]
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0..last) {
                dst[base + i * step] = average(a, r, g, b, window)
                val out = src[base + (i - radius).coerceIn(0, last) * step]
                val inn = src[base + (i + radius + 1).coerceIn(0, last) * step]
                a += (inn ushr 24) - (out ushr 24)
                r += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** 四舍五入求平均，避免多轮截断累积变暗。 */
    private fun average(a: Int, r: Int, g: Int, b: Int, window: Int): Int {
        val half = window / 2
        return (((a + half) / window) shl 24) or
            (((r + half) / window) shl 16) or
            (((g + half) / window) shl 8) or
            ((b + half) / window)
    }
}

/** Coil 解码后对小尺寸背景图做一次 [BoxBlur]，结果随图片进入内存缓存。 */
class BackdropBlurTransformation(
    private val radius: Int = GlassEffectDefaults.BackdropBlurRadiusPx,
    private val passes: Int = GlassEffectDefaults.BackdropBlurPasses,
) : Transformation {
    override val cacheKey: String = "${javaClass.name}-$radius-$passes"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurred = BoxBlur.blur(pixels, width, height, radius, passes)
        return Bitmap.createBitmap(blurred, width, height, Bitmap.Config.ARGB_8888)
    }
}
