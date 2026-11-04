package dev.abdus.apps.shimmer

import android.content.ContentResolver
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import dev.abdus.apps.shimmer.gl.MAX_SUPPORTED_BLUR_RADIUS_PIXELS
import dev.abdus.apps.shimmer.gl.generateBlurLevels
import dev.abdus.apps.shimmer.p3.BackgroundBlur
import dev.abdus.apps.shimmer.p3.DecodeBudget
import dev.abdus.apps.shimmer.p3.FitMode
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.OrientationSpec
import dev.abdus.apps.shimmer.p3.TransformMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.max
import dev.abdus.apps.shimmer.media.MediaClip
import dev.abdus.apps.shimmer.media.MediaKind
import dev.abdus.apps.shimmer.media.MediaProbe

class ImageLoader(
    private val contentResolver: ContentResolver,
    private val resources: Resources,
) {
    companion object {
        private const val TAG = "ImageLoader"
        private const val DEFAULT_SCREEN_WIDTH = 1080
        private const val DEFAULT_SCREEN_HEIGHT = 1920

        /** [ImageSet.id] of the bundled fallback image; its P3 placement is stored under it. */
        const val DEFAULT_IMAGE_ID = "default"
    }

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    /** Reported by the renderer from GL_MAX_TEXTURE_SIZE; read by the decode thread. */
    @Volatile
    var maxTextureSize: Int = DecodeBudget.FALLBACK_MAX_TEXTURE_SIZE

    /** Called by the Engine when the surface size is known. */
    fun setViewportSize(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    /** Legacy height-only entry; keeps the width if one is already known. */
    fun setScreenHeight(height: Int) {
        viewportHeight = height
    }

    /**
     * Loads an image from a URI.
     *
     * EXIF orientation is applied before any size is computed, and the sample size keeps enough
     * pixels for [transform] (legacy callers pass null and get the P2 "cover" budget) within the
     * pixel and GL texture caps.
     */
    suspend fun loadFromUri(uri: Uri, blurAmount: Float, transform: ImageTransform? = null, adaptFit: Boolean = false): ImageSet? {
        currentCoroutineContext().ensureActive()
        var clip: MediaClip? = null
        val decoded = try {
            val still = decodeOrientedFromUri(uri, transform, adaptFit)
            if (still != null) {
                val motion = MediaProbe.motionPhoto(contentResolver, uri)
                if (motion != null) {
                    val video = runCatching { MediaProbe.videoInfo(contentResolver, uri, motion.video) }.getOrNull()
                    if (video != null) clip = MediaClip(uri.toString(), MediaKind.MOTION_PHOTO, motion.video,
                        video.durationMs, motion.photoTimestampUs, video.hdr, video.width, video.height)
                }
                still
            } else {
                val (vw, vh) = viewport()
                MediaProbe.poster(contentResolver, uri, minOf(vw * 2, maxTextureSize), minOf(vh * 2, maxTextureSize))?.let { poster ->
                    clip = MediaClip(uri.toString(), MediaKind.VIDEO, durationMs = poster.info.durationMs,
                        hdr = poster.info.hdr, videoWidth = poster.info.width, videoHeight = poster.info.height)
                    Decoded(poster.bitmap, poster.info.width, poster.info.height, 1)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Image load failed: ${e.javaClass.simpleName}")
            null
        } ?: run {
            currentCoroutineContext().ensureActive()
            return null
        }

        return prepareImageSet(decoded, blurAmount, id = uri.toString(), mediaClip = clip)
    }

    /** Loads the bundled default wallpaper under the same budget. */
    suspend fun loadDefault(blurAmount: Float, transform: ImageTransform? = null, adaptFit: Boolean = false): ImageSet? {
        currentCoroutineContext().ensureActive()
        val decoded = try {
            decodeDefault(transform, adaptFit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Default wallpaper load failed: ${e.javaClass.simpleName}")
            null
        } ?: run {
            currentCoroutineContext().ensureActive()
            return null
        }

        return prepareImageSet(decoded, blurAmount, id = DEFAULT_IMAGE_ID)
    }

    /**
     * True when [imageSet] was sampled more coarsely than [transform] needs, e.g. after the user
     * zoomed in. The caller then re-decodes; a finer-than-needed image is kept as is.
     */
    fun needsFinerDecode(imageSet: ImageSet, transform: ImageTransform): Boolean =
        targetSampleSize(imageSet, transform) < imageSet.sampleSize

    /** The `inSampleSize` a decode of [imageSet]'s source would use for [transform]. */
    fun targetSampleSize(imageSet: ImageSet, transform: ImageTransform): Int =
        sampleSizeFor(imageSet.sourceWidth, imageSet.sourceHeight, transform)

    private class Decoded(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int, val sampleSize: Int)

    private fun viewport(): Pair<Int, Int> {
        val h = if (viewportHeight > 0) viewportHeight else DEFAULT_SCREEN_HEIGHT
        val w = if (viewportWidth > 0) viewportWidth else if (viewportHeight > 0) viewportHeight * 9 / 16 else DEFAULT_SCREEN_WIDTH
        return w to h
    }

    private fun sampleSizeFor(orientedWidth: Int, orientedHeight: Int, transform: ImageTransform?, adaptFit: Boolean = false): Int {
        val (vw, vh) = viewport()
        val placement = if (adaptFit && transform != null) {
            val adaptedFit = dev.abdus.apps.shimmer.p3.SmartFit.choose(orientedWidth, orientedHeight, vw, vh)
            transform.copy(fitMode = adaptedFit)
        } else {
            transform ?: ImageTransform(fitMode = FitMode.COVER)
        }
        val displayScale = TransformMath.totalScale(placement, orientedWidth, orientedHeight, vw, vh)
        return DecodeBudget.sampleSize(orientedWidth, orientedHeight, displayScale, maxTextureSize)
    }

    private fun decodeOrientedFromUri(uri: Uri, transform: ImageTransform?, adaptFit: Boolean = false): Decoded? {
        // Pass 1: bounds and orientation only.
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val orientation = readOrientation(uri)
        val sourceWidth = orientation.orientedWidth(options.outWidth, options.outHeight)
        val sourceHeight = orientation.orientedHeight(options.outWidth, options.outHeight)

        options.inSampleSize = sampleSizeFor(sourceWidth, sourceHeight, transform, adaptFit)
        options.inJustDecodeBounds = false
        Log.d(TAG, "Decoding image: ${sourceWidth}x$sourceHeight, orientation=$orientation, sampleSize=${options.inSampleSize}")

        // Pass 2: pixels, then turn them upright once so every later size is already oriented.
        val stored = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null
        val upright = applyOrientation(stored, orientation)
        return Decoded(upright, sourceWidth, sourceHeight, options.inSampleSize)
    }

    private fun decodeDefault(transform: ImageTransform?, adaptFit: Boolean = false): Decoded? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            // Budget in real pixels; density scaling would enlarge the decode past the budget.
            inScaled = false
        }
        BitmapFactory.decodeResource(resources, R.drawable.default_wallpaper, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = sampleSizeFor(options.outWidth, options.outHeight, transform, adaptFit)
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.default_wallpaper, options) ?: return null
        return Decoded(bitmap, options.outWidth, options.outHeight, options.inSampleSize)
    }

    /**
     * Reads the EXIF orientation. A seekable descriptor is tried first because HEIF metadata is
     * not readable from a plain stream; any failure means "upright" rather than a failed image.
     */
    private fun readOrientation(uri: Uri): OrientationSpec {
        val tag = runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull()
        return OrientationSpec.fromExif(tag ?: ExifInterface.ORIENTATION_NORMAL)
    }

    private fun applyOrientation(stored: Bitmap, orientation: OrientationSpec): Bitmap {
        if (orientation.isIdentity) return stored
        val matrix = Matrix().apply { setValues(orientation.matrixValues(stored.width, stored.height)) }
        val upright = try {
            Bitmap.createBitmap(stored, 0, 0, stored.width, stored.height, matrix, true)
        } catch (failed: Throwable) {
            if (!stored.isRecycled) stored.recycle()
            throw failed
        }
        if (upright !== stored && !stored.isRecycled) stored.recycle()
        return upright
    }

    /** Builds the ≤ 96 px blurred fill; halving first keeps the reduction from aliasing. */
    private fun buildBackground(foreground: Bitmap): Bitmap {
        val (targetW, targetH) = BackgroundBlur.targetSize(foreground.width, foreground.height)
        var current = foreground
        try {
            while (max(current.width, current.height) >= 2 * max(targetW, targetH)) {
                val half = Bitmap.createScaledBitmap(current, max(1, current.width / 2), max(1, current.height / 2), true)
                if (current !== foreground && current !== half) current.recycle()
                current = half
            }
            val pixels = IntArray(targetW * targetH)
            val sized = if (current.width == targetW && current.height == targetH) {
                current
            } else {
                Bitmap.createScaledBitmap(current, targetW, targetH, true)
            }
            sized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            if (sized !== foreground && sized !== current) sized.recycle()
            BackgroundBlur.blurAndDim(pixels, targetW, targetH)
            return Bitmap.createBitmap(pixels, targetW, targetH, Bitmap.Config.ARGB_8888)
        } finally {
            if (current !== foreground && !current.isRecycled) current.recycle()
        }
    }

    private suspend fun prepareImageSet(decoded: Decoded, blurAmount: Float, id: String, mediaClip: MediaClip? = null): ImageSet {
        val bitmap = decoded.bitmap
        val maxRadius = ceil(blurAmount * MAX_SUPPORTED_BLUR_RADIUS_PIXELS)
        val context = currentCoroutineContext()
        var blurred = emptyList<Bitmap>()
        var background: Bitmap? = null
        try {
            context.ensureActive()
            val blurResult = bitmap.generateBlurLevels(maxRadius) {
                context.ensureActive()
            }
            blurred = blurResult.bitmaps
            // The blur helper can use a fallback after a GPU failure. Cancellation still wins.
            context.ensureActive()
            // A missing fill only hides the blurred margin; it must not fail the image.
            background = runCatching { buildBackground(bitmap) }.getOrNull()
            context.ensureActive()

            return ImageSet(
                id = id,
                original = bitmap,
                blurred = blurred,
                blurRadii = blurResult.radii,
                width = bitmap.width,
                height = bitmap.height,
                sourceWidth = decoded.sourceWidth,
                sourceHeight = decoded.sourceHeight,
                sampleSize = decoded.sampleSize,
                background = background,
                mediaClip = mediaClip,
            )
        } catch (failed: Throwable) {
            // Ownership transfers only on return; failed/cancelled preparation releases it here.
            blurred.forEach { if (!it.isRecycled) it.recycle() }
            background?.let { if (!it.isRecycled) it.recycle() }
            if (!bitmap.isRecycled) bitmap.recycle()
            throw failed
        }
    }
}
