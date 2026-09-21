package dev.abdus.apps.shimmer.p0

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import dev.abdus.apps.shimmer.ImageSet

/** P0 test fixture selection, not the P1 multi-source library. No user file is modified. */
object P0TestImage {
    fun selectedUri(context: Context): Uri? = context.getSharedPreferences("p0_fixture", Context.MODE_PRIVATE)
        .getString("uri", null)?.let(Uri::parse)

    fun commit(context: Context, uri: Uri) {
        context.getSharedPreferences("p0_fixture", Context.MODE_PRIVATE).edit()
            .putString("uri", uri.toString()).apply()
    }

    fun decode(context: Context, uri: Uri): ImageSet? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (options.outWidth / sample > 2048 || options.outHeight / sample > 2048 ||
            options.outWidth.toLong() / sample * (options.outHeight / sample) > 4_194_304L) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        val image = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        return ImageSet(image, id = "USER_TEST")
    }

    fun calibration(): ImageSet {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 38, 58))
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; strokeWidth = 5f }
        canvas.drawLine(0f, 640f, 720f, 640f, pen)
        canvas.drawLine(360f, 0f, 360f, 1280f, pen)
        canvas.drawText("C / TOP LEFT", 20f, 60f, pen)
        canvas.drawText("TOP RIGHT", 430f, 120f, pen)
        canvas.drawText("BOTTOM LEFT", 20f, 1160f, pen)
        canvas.drawText("BOTTOM RIGHT", 340f, 1230f, pen)
        canvas.drawText("CENTER", 280f, 700f, pen)
        canvas.drawCircle(360f, 640f, 22f, pen)
        return ImageSet(bitmap, id = "C")
    }
}
