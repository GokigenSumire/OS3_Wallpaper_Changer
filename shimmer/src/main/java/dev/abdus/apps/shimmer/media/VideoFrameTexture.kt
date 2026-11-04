package dev.abdus.apps.shimmer.media

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import dev.abdus.apps.shimmer.gl.QuadMesh
import dev.abdus.apps.shimmer.gl.ShaderCompiler
import dev.abdus.apps.shimmer.gl.TextureLoader
import java.util.concurrent.atomic.AtomicBoolean

/** GL-only resources. Decoder output never writes to the wallpaper window owned by EGL. */
class VideoFrameTexture(
    val clip: MediaClip,
    private val width: Int,
    private val height: Int,
    owner: MediaPlaybackOwner,
    private val requestRender: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val available = AtomicBoolean(false)
    private val transform = FloatArray(16)
    private var external = 0
    private var copyProgram = 0
    private var blurProgram = 0
    private val textures = IntArray(5)
    private val framebuffers = IntArray(5)
    private var source: SurfaceTexture? = null
    private var surface: Surface? = null
    private var session: MediaPlaybackOwner.Session? = null
    private var frozen = false
    var hasFrame = false
        private set
    private var latchedGeneration = -1L
    val texture: Int get() = textures[0]
    val blurredTexture: Int get() = if (lastRadius > 0) textures[1] else textures[0]
    val fillTexture: Int get() = textures[3]
    val state: MediaFrameState get() = session?.state ?: MediaFrameState.FALLBACK_ERROR
    private val currentFrame: Boolean get() = hasFrame && session?.let { it.producerReady && latchedGeneration == it.outputGeneration } == true
    val showVideo: Boolean get() = hasFrame && (frozen || session?.transientlyPaused == true || currentFrame && (state == MediaFrameState.VIDEO || state == MediaFrameState.PREPARING))
    val presentationReady: Boolean get() = currentFrame || hasFrame && session?.transientlyPaused == true || state in setOf(MediaFrameState.STILL, MediaFrameState.ENDED,
        MediaFrameState.FALLBACK_ERROR, MediaFrameState.FALLBACK_HDR, MediaFrameState.FALLBACK_BUSY, MediaFrameState.FALLBACK_TIMEOUT)
    private val sourceSize = VideoBlurPlan.Size(width, height)
    private val blurSize = VideoBlurPlan.size(width, height)
    private val fillWidth = if (width >= height) minOf(width, 96) else (minOf(height, 96).toLong() * width / height).toInt().coerceAtLeast(1)
    private val fillHeight = if (height >= width) minOf(height, 96) else (minOf(width, 96).toLong() * height / width).toInt().coerceAtLeast(1)
    private var lastRadius = -1
    private val kernels = HashMap<Int, VideoBlurPlan.Kernel>()

    init {
        try {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            external = ids[0]
            check(external != 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, external)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            copyProgram = program(VERTEX, OES_FRAGMENT)
            blurProgram = program(VERTEX, BLUR_FRAGMENT)
            // A complete mip pyramid prefilters reduction BEFORE the separable blur samples.
            GLES30.glGenTextures(1, textures, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, VideoBlurPlan.mipLevels(width, height), GLES30.GL_RGBA8, width, height)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            textures[1] = TextureLoader.allocate(blurSize.width, blurSize.height)
            textures[2] = TextureLoader.allocate(blurSize.width, blurSize.height)
            textures[3] = TextureLoader.allocate(fillWidth, fillHeight)
            textures[4] = TextureLoader.allocate(fillWidth, fillHeight)
            GLES30.glGenFramebuffers(5, framebuffers, 0)
            source = SurfaceTexture(external).also { stream ->
                stream.setDefaultBufferSize(width, height)
                stream.setOnFrameAvailableListener {
                    if (!closed.get()) { available.set(true); requestRender() }
                }
            }
            surface = Surface(checkNotNull(source))
            session = owner.open(clip, checkNotNull(surface))
        } catch (error: Throwable) {
            dispose(lost = false)
            throw error
        }
    }

    fun update(blurPercent: Float, maxRadius: Float) {
        if (closed.get() || frozen) return
        val stream = source ?: return
        val active = session ?: return
        if (!active.producerReady) {
            // Drain stale buffers without admitting them as the new decoder's first frame.
            if (available.getAndSet(false)) stream.updateTexImage()
            return
        }
        val generation = active.outputGeneration
        val newFrame = available.getAndSet(false) || latchedGeneration != generation
        val radius = VideoBlurPlan.radius(maxRadius, blurPercent, sourceSize, blurSize)
        if (!newFrame && radius == lastRadius) return
        val oldFramebuffer = IntArray(1)
        val viewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, oldFramebuffer, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        val blend = GLES30.glIsEnabled(GLES30.GL_BLEND)
        try {
            GLES30.glDisable(GLES30.GL_BLEND)
            if (newFrame) {
                stream.updateTexImage()
                stream.getTransformMatrix(transform)
                draw(copyProgram, 0, width, height, external, externalTexture = true)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                // Resolve resizing to a fixed texel grid before applying bilinear-paired weights.
                draw(blurProgram, 3, fillWidth, fillHeight, textures[0], radius = 0)
                draw(blurProgram, 4, fillWidth, fillHeight, textures[3], dx = 1f / fillWidth, radius = 6)
                draw(blurProgram, 3, fillWidth, fillHeight, textures[4], dy = 1f / fillHeight, radius = 6)
            }
            if (radius > 0) {
                draw(blurProgram, 1, blurSize.width, blurSize.height, textures[0], radius = 0)
                draw(blurProgram, 2, blurSize.width, blurSize.height, textures[1], dx = 1f / blurSize.width, radius = radius)
                draw(blurProgram, 1, blurSize.width, blurSize.height, textures[2], dy = 1f / blurSize.height, radius = radius)
            }
            lastRadius = radius
            if (newFrame && active.outputGeneration == generation && active.producerReady) {
                hasFrame = true
                latchedGeneration = generation
                active.frameConsumed(generation)
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, oldFramebuffer[0])
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            if (blend) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    private fun draw(p: Int, target: Int, w: Int, h: Int, input: Int, externalTexture: Boolean = false, dx: Float = 0f, dy: Float = 0f, radius: Int = 0) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[target])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[target], 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(p)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (externalTexture) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, input)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uTexture"), 0)
        if (externalTexture) {
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, "uTransform"), 1, false, transform, 0)
            val ratio = session?.videoAspect?.takeIf { it.isFinite() && it > 0 } ?: (width.toFloat() / height)
            val crop = MediaFrameGeometry.crop(ratio, width.toFloat() / height)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uCrop"), crop.first, crop.second)
        }
        else {
            val kernel = kernels.getOrPut(radius) { VideoBlurPlan.kernel(radius) }
            GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "uStep"), dx, dy)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uSamples"), kernel.weights.size)
            GLES30.glUniform1fv(GLES30.glGetUniformLocation(p, "uWeights"), kernel.weights.size, kernel.weights, 0)
            GLES30.glUniform1fv(GLES30.glGetUniformLocation(p, "uOffsets"), kernel.offsets.size, kernel.offsets, 0)
        }
        QuadMesh.draw(GLES30.glGetAttribLocation(p, "aPosition"), GLES30.glGetAttribLocation(p, "aTexCoords"))
    }

    /** Retain the last GPU frame for the existing bounded P5 transition; stop its decoder now. */
    fun freeze() { frozen = true; session?.close() }
    fun dispose(lost: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        available.set(false)
        val stream = source
        val output = surface
        source = null; surface = null
        stream?.setOnFrameAvailableListener(null)
        if (!lost) {
            if (stream != null) {
                // detach deletes its external texture name in the owning current context.
                val detached = runCatching { stream.detachFromGLContext() }.isSuccess
                if (!detached && external != 0) GLES30.glDeleteTextures(1, intArrayOf(external), 0)
            } else if (external != 0) GLES30.glDeleteTextures(1, intArrayOf(external), 0)
            GLES30.glDeleteTextures(5, textures, 0)
            GLES30.glDeleteFramebuffers(5, framebuffers, 0)
            if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram)
            if (blurProgram != 0) GLES30.glDeleteProgram(blurProgram)
        }
        val release = { output?.release(); stream?.release(); Unit }
        val active = session
        session = null
        if (active == null) release() else active.close(release)
        external = 0; copyProgram = 0; blurProgram = 0
    }

    companion object {
        private fun program(vertex: String, fragment: String): Int {
            val v = ShaderCompiler.compile(GLES30.GL_VERTEX_SHADER, vertex)
            var f = 0
            var p = 0
            try {
                f = ShaderCompiler.compile(GLES30.GL_FRAGMENT_SHADER, fragment)
                p = GLES30.glCreateProgram()
                GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
                val status = IntArray(1)
                GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { "Video program link failed" }
                return p
            } catch (e: Throwable) { if (p != 0) GLES30.glDeleteProgram(p); throw e }
            finally { GLES30.glDeleteShader(v); if (f != 0) GLES30.glDeleteShader(f) }
        }
        private val VERTEX = """
            #version 300 es
            in vec4 aPosition;
            in vec2 aTexCoords;
            out vec2 uv;
            void main() { gl_Position = aPosition; uv = aTexCoords; }
        """.trimIndent()
        private val OES_FRAGMENT = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTransform;
            uniform vec2 uCrop;
            in vec2 uv;
            out vec4 color;
            void main() { color = texture(uTexture, (uTransform * vec4((uv - 0.5) * uCrop + 0.5, 0.0, 1.0)).xy); }
        """.trimIndent()
        private val BLUR_FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform highp sampler2D uTexture;
            uniform vec2 uStep;
            uniform int uSamples;
            uniform float uWeights[101];
            uniform float uOffsets[101];
            in vec2 uv;
            out vec4 color;
            void main() {
                vec3 c = texture(uTexture, uv).rgb * uWeights[0];
                for (int i = 1; i < 101; ++i) {
                    if (i >= uSamples) break;
                    vec2 stepUV = uStep * uOffsets[i];
                    c += (texture(uTexture, uv + stepUV).rgb + texture(uTexture, uv - stepUV).rgb) * uWeights[i];
                }
                color = vec4(c, 1.0);
            }
        """.trimIndent()
    }
}
