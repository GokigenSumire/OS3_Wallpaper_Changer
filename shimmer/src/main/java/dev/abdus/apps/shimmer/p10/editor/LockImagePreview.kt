package dev.abdus.apps.shimmer.p10.editor

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.widget.FrameLayout
import dev.abdus.apps.shimmer.ImageLoader
import dev.abdus.apps.shimmer.ImageSet
import dev.abdus.apps.shimmer.ShimmerRenderer
import dev.abdus.apps.shimmer.p3.FrameStats
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p5.TransitionSpec
import dev.abdus.apps.shimmer.recycleAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The authenticated LOCK editor owns this surface, context and every decoded bitmap. It never
 * borrows the formal Engine's ImageSet and never displays the window's HOME wallpaper. Only
 * opaque, authenticated session data may be handed to [show]; no Intent contains the image URI.
 */
internal class LockImagePreview(
    context: Context,
    private val stats: FrameStats,
    private val onUnavailable: () -> Unit,
) : FrameLayout(context), LockEditorPreview {
    override val view get() = this
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loader = ImageLoader(context.contentResolver, context.resources)
    private val surface = GLSurfaceView(context)
    private var decode: Job? = null
    private var refine: Job? = null
    private var uri: Uri? = null
    private var image: LiveEditBridge.EditImage? = null
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var paused = false
    @Volatile private var ready = false
    @Volatile private var placement = ImageTransform()
    // Only the GL thread adopts a displayed image. onPause joins that thread before
    // clearing it on Main, so recycled pixels cannot be read by an outstanding frame/upload.
    private val pixels = LockPreviewResources<ImageSet>(ImageSet::recycleAll)
    private val renderer: ShimmerRenderer = ShimmerRenderer(object : ShimmerRenderer.Callbacks {
        override fun requestRender() { if (!closed && !paused) surface.requestRender() }
        override fun onRendererReady() {
            loader.maxTextureSize = renderer.maxTextureSize
            ready = true
            post { if (!closed && !paused) requestDecode() }
        }
        override fun onReadyForNextImage() = Unit
        override fun onSurfaceDimensionsChanged(width: Int, height: Int) {
            loader.setViewportSize(width, height)
        }
    }, context.applicationContext)

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = false
        surface.apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            preserveEGLContextOnPause = false
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    if (closed) return
                    renderer.frameStats = stats
                    renderer.setEffectTransitionDuration(0)
                    renderer.onSurfaceCreated()
                }
                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    if (!closed && ready) renderer.onSurfaceChanged(width, height)
                }
                override fun onDrawFrame(gl: GL10?) {
                    if (!closed && ready) renderer.onDrawFrame()
                    // GLSurfaceView performs the EGL swap after this callback. Do not invent
                    // an onFramePresented / input-to-present sample before that actual swap.
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun show(nextImage: LiveEditBridge.EditImage, nextUri: Uri, transform: ImageTransform) {
        if (closed) return
        image = nextImage
        uri = nextUri
        placement = transform.sanitized()
        generation++
        decode?.cancel()
        requestDecode()
    }

    override fun preview(transform: ImageTransform, inputUptimeMillis: Long) {
        if (closed || paused) return
        placement = transform.sanitized()
        val current = image ?: return
        renderer.setLiveTransform(current.imageId, placement, inputUptimeMillis)
        refine?.cancel()
        refine = scope.launch {
            delay(180L)
            // A placement update also refreshes the decode budget after pinch zoom. The
            // formal Engine's bitmap and sampling generation are never read or modified.
            requestDecode(onlyRefine = true)
        }
    }

    private fun requestDecode(onlyRefine: Boolean = false) {
        if (!ready || paused || closed) return
        val current = image ?: return
        val currentUri = uri ?: return
        if (onlyRefine) {
            val decoded = pixels.current() ?: return
            if (decoded.id != current.imageId || !loader.needsFinerDecode(decoded, placement)) return
        }
        val ticket = ++generation
        decode?.cancel()
        decode = scope.launch {
            var loaded: ImageSet? = null
            try {
                withContext(Dispatchers.Default) {
                    // Assignment happens inside withContext so prompt cancellation on its
                    // return cannot discard an allocated ImageSet without this finally owner.
                    loaded = loader.loadFromUri(currentUri, blurAmount = 0f, transform = placement)
                }
                if (closed || paused || generation != ticket) return@launch
                val decoded = loaded ?: run {
                    onUnavailable()
                    return@launch
                }
                val uploadTicket = pixels.enqueue(decoded) ?: run { loaded = null; return@launch }
                loaded = null
                // Queued uploads retain an explicit owner even if the surface is detached
                // before this runnable executes; pause/close reclaim the unconsumed queue.
                surface.queueEvent {
                    val upload = pixels.take(uploadTicket) ?: return@queueEvent
                    if (closed || paused || !ready || generation != ticket) {
                        upload.recycleAll()
                    } else {
                        val next = upload.copy(id = current.imageId)
                        try {
                            renderer.setImage(next, placement, TransitionSpec.NONE)
                            pixels.uploaded(next)
                            surface.requestRender()
                        } catch (_: RuntimeException) {
                            // Partial GL uploads remain owned by this context until pause.
                            // Stop drawing; Main closes the private editor and releases its hold.
                            ready = false
                            next.recycleAll()
                            post { if (!closed) onUnavailable() }
                        }
                    }
                }
            } finally {
                loaded?.recycleAll()
            }
        }
    }

    override fun resumePreview() {
        renderer.setMediaPlaybackAllowed(true)
        if (closed || !paused) return
        paused = false
        surface.onResume()
    }

    override fun pausePreview() {
        renderer.setMediaPlaybackAllowed(false)
        if (paused) return
        paused = true
        generation++
        decode?.cancel()
        decode = null
        refine?.cancel()
        refine = null
        // With preservation disabled, GLSurfaceView waits for its GL thread/context to stop.
        // Only afterwards may Main forget names and reclaim CPU pixels without a live context.
        surface.onPause()
        ready = false
        renderer.onContextLost()
        pixels.clearAfterRendererStopped()
    }

    override fun closePreview() {
        renderer.setMediaPlaybackAllowed(false)
        if (closed) return
        pausePreview()
        closed = true
        pixels.closeAfterRendererStopped()
        uri = null
        image = null
        scope.cancel()
    }
}
