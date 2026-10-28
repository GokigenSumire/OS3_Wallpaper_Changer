package dev.abdus.apps.shimmer.p1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads small source-card previews off the main thread.
 *
 * `ImageView.setImageURI` decodes the full image on the main thread. Several camera photos
 * at full resolution can exhaust the shared app/wallpaper process, so previews are sampled
 * to the card size and cached by URI and size.
 */
object PreviewThumbnailLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(scope: CoroutineScope, view: ImageView, uri: String, sizePx: Int) {
        val key = "$sizePx|$uri"
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val context = view.context.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(context, Uri.parse(uri), sizePx) }
            if (bitmap != null) cache.put(key, bitmap)
            // A recycled or re-bound view must not show a stale request's image.
            if (view.tag == key) view.setImageBitmap(bitmap)
        }
    }

    fun calculateSampleSize(width: Int, height: Int, requested: Int): Int {
        if (width <= 0 || height <= 0 || requested <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= requested && height / (sample * 2) >= requested) sample *= 2
        return sample
    }

    private fun decode(context: Context, uri: Uri, sizePx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, sizePx)
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
