package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import dev.abdus.apps.shimmer.gl.FillProgram
import dev.abdus.apps.shimmer.gl.ShaderHandles
import dev.abdus.apps.shimmer.gl.QuadMesh
import dev.abdus.apps.shimmer.gl.TextureArray
import dev.abdus.apps.shimmer.gl.TextureLoader
import dev.abdus.apps.shimmer.gl.ImageUploadIdentity
import kotlin.math.ceil
import kotlin.math.floor

class ImageRenderer {
    private val textures = TextureArray()
    private val uploadIdentity = ImageUploadIdentity()
    // P3 fill layer; owned and released together with the foreground textures (F04).
    private var backgroundTexture = 0
    private var backgroundSource: Bitmap? = null

    val aspectRatio: Float get() = textures.aspectRatio
    val isEmpty: Boolean get() = textures.isEmpty
    val textureCount: Int get() = textures.size
    val hasBackground: Boolean get() = backgroundTexture != 0

    fun load(imageSet: ImageSet) {
        if (imageSet.original.isRecycled) return
        if (uploadIdentity.canReuse(imageSet, imageSet.original, imageSet.blurred, imageSet.background, !textures.isEmpty)) return
        uploadIdentity.clear()

        val bitmaps = listOf(imageSet.original) + imageSet.blurred

        // A same-aspect image of a different size (e.g. a finer P3 re-decode) cannot reuse the
        // immutable storage, so reallocation is keyed on exact sizes rather than aspect ratio.
        if (textures.fits(bitmaps)) {
            textures.upload(bitmaps)
        } else {
            textures.allocate(bitmaps)
        }
        loadBackground(imageSet.background)

        uploadIdentity.record(imageSet, imageSet.original, imageSet.blurred, imageSet.background)
    }

    private fun loadBackground(bitmap: Bitmap?) {
        if (bitmap === backgroundSource && backgroundTexture != 0) return
        releaseBackground()
        if (bitmap == null || bitmap.isRecycled) return
        backgroundTexture = TextureLoader.load(bitmap)
        backgroundSource = bitmap
    }

    private fun releaseBackground() {
        if (backgroundTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(backgroundTexture), 0)
            backgroundTexture = 0
        }
        backgroundSource = null
    }

    /** Draws the P3 blurred fill; a no-op when the image set carried no background. */
    fun drawBackground(
        fill: FillProgram,
        mvpMatrix: FloatArray,
        clipToQuad: FloatArray?,
        dimAmount: Float,
        alpha: Float,
    ) {
        if (backgroundTexture == 0 || alpha <= 0f) return
        fill.draw(backgroundTexture, mvpMatrix, clipToQuad, dimAmount, alpha)
    }

    fun draw(
        handles: ShaderHandles,
        mvpMatrix: FloatArray,
        blurPercent: Float,
        alpha: Float,
        duotone: Duotone,
        dimAmount: Float,
        grain: GrainSettings,
        grainCounts: Pair<Float, Float>,
        touchPoints: FloatArray,
        touchIntensities: FloatArray,
        aspectRatio: Float,
        timeSeconds: Float,
    ) {
        if (textures.isEmpty || alpha <= 0f) return

        val keyframes = (textures.size - 1).coerceAtLeast(0)
        val progress = blurPercent * keyframes
        val lo = floor(progress).toInt().coerceIn(0, keyframes)
        val hi = ceil(progress).toInt().coerceIn(0, keyframes)
        val mix = progress - lo

        GLES30.glUseProgram(handles.program)
        setUniforms(handles, mvpMatrix, duotone, dimAmount, grain, grainCounts,
                    touchPoints, touchIntensities, aspectRatio, timeSeconds, mix, alpha)

        textures.bind(lo, 0)
        GLES30.glUniform1i(handles.uniformTexture0, 0)

        textures.bind(hi, 1)
        GLES30.glUniform1i(handles.uniformTexture1, 1)

        QuadMesh.draw(handles.attribPosition, handles.attribTexCoords)
    }

    private fun setUniforms(
        h: ShaderHandles,
        mvp: FloatArray,
        duotone: Duotone,
        dim: Float,
        grain: GrainSettings,
        grainCounts: Pair<Float, Float>,
        touchPoints: FloatArray,
        touchIntensities: FloatArray,
        aspectRatio: Float,
        timeSeconds: Float,
        blurMix: Float,
        alpha: Float
    ) {
        GLES30.glUniformMatrix4fv(h.uniformMvpMatrix, 1, false, mvp, 0)
        GLES30.glUniform3f(h.uniformDuotoneLight,
                           Color.red(duotone.lightColor)/255f,
                           Color.green(duotone.lightColor)/255f,
                           Color.blue(duotone.lightColor)/255f)
        GLES30.glUniform3f(h.uniformDuotoneDark,
                           Color.red(duotone.darkColor)/255f,
                           Color.green(duotone.darkColor)/255f,
                           Color.blue(duotone.darkColor)/255f)
        GLES30.glUniform1f(h.uniformDuotoneOpacity, duotone.opacity)
        GLES30.glUniform1i(h.uniformDuotoneBlendMode, duotone.blendMode.value)
        GLES30.glUniform1f(h.uniformDimAmount, dim)
        GLES30.glUniform1f(h.uniformGrainAmount, if (grain.enabled) grain.amount else 0f)
        GLES30.glUniform2f(h.uniformGrainCount, grainCounts.first, grainCounts.second)
        GLES30.glUniform1i(h.uniformTouchPointCount, touchIntensities.size)
        GLES30.glUniform3fv(h.uniformTouchPoints, touchIntensities.size, touchPoints, 0)
        GLES30.glUniform1fv(h.uniformTouchIntensities, touchIntensities.size, touchIntensities, 0)
        GLES30.glUniform1f(h.uniformAspectRatio, aspectRatio)
        GLES30.glUniform1f(h.uniformTime, timeSeconds)
        GLES30.glUniform1f(h.uniformBlurMix, blurMix)
        GLES30.glUniform1f(h.uniformAlpha, alpha)
    }

    fun release() {
        textures.release()
        releaseBackground()
        uploadIdentity.clear()
    }
}
