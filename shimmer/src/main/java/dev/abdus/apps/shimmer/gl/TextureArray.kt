package dev.abdus.apps.shimmer.gl

import android.graphics.Bitmap
import android.opengl.GLES30

class TextureArray {
    private var handles = IntArray(0)
    // Immutable storage: a re-upload is only valid for bitmaps of exactly these sizes.
    private var sizes = emptyList<Pair<Int, Int>>()
    var aspectRatio = 0f
        private set

    val isEmpty: Boolean get() = handles.isEmpty()
    val size: Int get() = handles.size

    /** True when [bitmaps] can be uploaded into the existing storage without reallocating. */
    fun fits(bitmaps: List<Bitmap>): Boolean =
        !isEmpty && bitmaps.size == sizes.size &&
            bitmaps.indices.all { bitmaps[it].width to bitmaps[it].height == sizes[it] }

    fun allocate(bitmaps: List<Bitmap>) {
        release()

        require(bitmaps.isNotEmpty()) { "Cannot allocate empty texture array" }
        require(bitmaps.all { !it.isRecycled }) { "Cannot upload recycled bitmap" }

        val first = bitmaps.first()
        aspectRatio = first.width.toFloat() / first.height
        handles = IntArray(bitmaps.size)
        sizes = bitmaps.map { it.width to it.height }

        bitmaps.forEachIndexed { i, bitmap ->
            handles[i] = TextureLoader.allocate(bitmap.width, bitmap.height)
            TextureLoader.upload(handles[i], bitmap)
        }

        GLES30.glFlush()
    }

    fun upload(bitmaps: List<Bitmap>) {
        require(fits(bitmaps)) { "Bitmap count or size mismatch" }

        bitmaps.forEachIndexed { i, bitmap ->
            TextureLoader.upload(handles[i], bitmap)
        }

        GLES30.glFlush()
    }

    fun bind(index: Int, textureUnit: Int) {
        require(index in handles.indices) { "Invalid texture index" }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + textureUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handles[index])
    }

    fun release() {
        if (handles.isNotEmpty()) {
            GLES30.glDeleteTextures(handles.size, handles, 0)
            handles = IntArray(0)
            sizes = emptyList()
            aspectRatio = 0f
        }
    }
}