package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30

/**
 * Surface-sized RGBA8 offscreen target (P5). The incoming composite is drawn here first so it can
 * fade or scale as one layer over the outgoing one. GL thread only; allocated on first use for a
 * transition and released when that transition ends (F04).
 */
class TransitionTarget {
    var texture = 0
        private set
    private var framebuffer = 0
    var width = 0
        private set
    var height = 0
        private set

    val isAllocated: Boolean get() = framebuffer != 0

    /** GL objects held: the texture and the framebuffer. */
    val objectCount: Int get() = (if (texture != 0) 1 else 0) + (if (framebuffer != 0) 1 else 0)

    /** Ensures a complete target of [w]×[h]; false (and nothing held) if the driver refuses. */
    fun ensure(w: Int, h: Int): Boolean {
        if (isAllocated && width == w && height == h) return true
        release()
        if (w <= 0 || h <= 0) return false
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) {
            release()
            return false
        }
        width = w
        height = h
        return true
    }

    /** Binds the target and clears it to opaque black, the same base as the screen. */
    fun begin() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /** Back to the window surface with a full-surface viewport. */
    fun end(surfaceWidth: Int, surfaceHeight: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    fun release() {
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        forget()
    }

    /** Context loss: the driver already reclaimed the names, which must not be deleted again. */
    fun forget() {
        framebuffer = 0
        texture = 0
        width = 0
        height = 0
    }
}
