package dev.abdus.apps.shimmer.p3

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.math.min

/**
 * The P3 calibration and stress images, written to [RELATIVE_DIR] so they can be added as a P1
 * source. Text and markers make orientation, mirroring and aspect errors visible at a glance.
 * Blocking; call off the main thread.
 */
object P3TestImages {
    const val RELATIVE_DIR = "Pictures/OS3_P3"
    private const val JPEG_QUALITY = 92

    data class Result(val written: List<String>, val skipped: List<String>, val failed: List<String>)

    @RequiresApi(29)
    fun writeAll(context: Context): Result {
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val jobs = listOf<Pair<String, (File) -> Unit>>(
            "p3_calibration_4000x3000.jpg" to { file -> writeCalibration(file) },
            "p3_panorama_12000x2000.jpg" to { file -> writePanorama(file) },
            "p3_exif6_3000x4000.jpg" to { file -> writeExifRotated(file) },
        )
        for ((name, render) in jobs) {
            if (exists(context, name)) {
                skipped += name
                continue
            }
            val temp = File(context.cacheDir, name)
            try {
                render(temp)
                publish(context, name, temp)
                written += name
            } catch (failure: Exception) {
                failed += "$name（${failure.javaClass.simpleName}）"
            } finally {
                temp.delete()
            }
        }
        return Result(written, skipped, failed)
    }

    private fun writeCalibration(file: File) = render(file, 4000, 3000, Bitmap.Config.ARGB_8888) { canvas ->
        drawCalibration(canvas, 4000, 3000, "P3 标定图 4000×3000")
    }

    /** 12000×2000 in RGB_565 to halve the transient memory; no alpha is needed. */
    private fun writePanorama(file: File) = render(file, 12000, 2000, Bitmap.Config.RGB_565) { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 12000f, 0f,
            intArrayOf(0xFF3050A0.toInt(), 0xFF30A070.toInt(), 0xFFB0A030.toInt(), 0xFFB04040.toInt(), 0xFF7040A0.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 12000f, 2000f, paint)
        paint.shader = null
        val text = textPaint(160f)
        for (i in 0..12) {
            val x = i * 1000f
            canvas.drawRect(x - 6f, 0f, x + 6f, 2000f, Paint().apply { color = Color.WHITE })
            canvas.drawText("$i", x + 30f, 1000f, text)
        }
        canvas.drawText("左端", 60f, 260f, text)
        canvas.drawText("右端", 11500f, 260f, text)
        canvas.drawText("↑ 上  P3 全景 12000×2000", 4800f, 260f, text)
    }

    /**
     * Upright content is portrait 3000×4000, stored as 4000×3000 pixels turned a quarter anti-
     * clockwise with EXIF orientation 6. It reads upright only when the orientation is applied.
     */
    private fun writeExifRotated(file: File) {
        render(file, 4000, 3000, Bitmap.Config.ARGB_8888) { canvas ->
            // Maps displayed (x, y) to stored (y, 3000 - x): the inverse of EXIF 6's clockwise turn.
            canvas.translate(0f, 3000f)
            canvas.rotate(-90f)
            drawCalibration(canvas, 3000, 4000, "EXIF 方向 6：此行应正立")
        }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
    }

    private fun drawCalibration(canvas: Canvas, w: Int, h: Int, title: String) {
        val short = min(w, h).toFloat()
        canvas.drawColor(0xFF202830.toInt())
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF50606E.toInt(); strokeWidth = 3f }
        val step = short / 12f
        var x = 0f
        while (x <= w) { canvas.drawLine(x, 0f, x, h.toFloat(), line); x += step }
        var y = 0f
        while (y <= h) { canvas.drawLine(0f, y, w.toFloat(), y, line); y += step }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        // A red frame shows the image edge, the cyan circle shows any aspect distortion.
        stroke.color = Color.RED; stroke.strokeWidth = short / 100f
        canvas.drawRect(stroke.strokeWidth / 2, stroke.strokeWidth / 2, w - stroke.strokeWidth / 2, h - stroke.strokeWidth / 2, stroke)
        stroke.color = Color.CYAN; stroke.strokeWidth = short / 150f
        canvas.drawCircle(w / 2f, h / 2f, short * 0.4f, stroke)
        stroke.color = Color.YELLOW
        canvas.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), stroke)
        canvas.drawLine(0f, h / 2f, w.toFloat(), h / 2f, stroke)
        val text = textPaint(short / 14f)
        val margin = short / 20f
        canvas.drawText("左上", margin, margin + text.textSize, text)
        canvas.drawText("右上", w - margin - text.measureText("右上"), margin + text.textSize, text)
        canvas.drawText("左下", margin, h - margin, text)
        canvas.drawText("右下", w - margin - text.measureText("右下"), h - margin, text)
        val up = textPaint(short / 8f).apply { color = Color.GREEN }
        canvas.drawText("↑ 上", (w - up.measureText("↑ 上")) / 2f, margin + up.textSize * 1.6f, up)
        val caption = textPaint(short / 20f)
        canvas.drawText(title, (w - caption.measureText(title)) / 2f, h / 2f + short * 0.25f, caption)
    }

    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        isFakeBoldText = true
    }

    private fun render(file: File, width: Int, height: Int, config: Bitmap.Config, draw: (Canvas) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, config)
        try {
            draw(Canvas(bitmap))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    @RequiresApi(29)
    private fun exists(context: Context, name: String): Boolean =
        context.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.DISPLAY_NAME}=?",
            arrayOf("$RELATIVE_DIR/", name),
            null,
        )?.use { it.moveToFirst() } == true

    @RequiresApi(29)
    private fun publish(context: Context, name: String, file: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) {
            // A half-written pending entry would otherwise linger in the gallery.
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
