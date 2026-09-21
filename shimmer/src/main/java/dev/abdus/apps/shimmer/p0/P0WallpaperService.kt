package dev.abdus.apps.shimmer.p0

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.abdus.apps.shimmer.ImageSet
import dev.abdus.apps.shimmer.ShimmerRenderer
import dev.abdus.apps.shimmer.gl.GLWallpaperService
import dev.abdus.apps.shimmer.recycleAll
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** P0 isolates diagnostic state from upstream playback, Room, timers and preview side effects. */
class P0WallpaperService : GLWallpaperService() {
    private val main = Handler(Looper.getMainLooper())
    private val screenSignal = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val signal = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
                Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
                Intent.ACTION_USER_PRESENT -> "USER_PRESENT"
                else -> return
            }
            // One service receiver, not one submission per Engine. These are observations only.
            P0Diagnostics.screen(context, "SERVICE", signal, null)
            for (delay in listOf(100L, 500L)) main.postDelayed({
                P0Diagnostics.screen(context, "SERVICE", "${signal}_AFTER_${delay}MS", null)
            }, delay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screenSignal, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
        P0Diagnostics.screen(this, "SERVICE", "CREATE", null)
    }

    override fun onDestroy() {
        unregisterReceiver(screenSignal)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = ProbeEngine()

    inner class ProbeEngine : GLEngine(), ShimmerRenderer.Callbacks {
        private val instance = P0Diagnostics.newId("ENGINE")
        private var scopeId = instance
        private var visible = false
        private var destroyed = false
        private var surfaceGeneration = 0
        @Volatile private var loadGeneration = 0
        private var fixture: ImageSet? = null // Main-thread receipt of completed GL upload, not just queue acceptance.
        private val ownedImages = mutableSetOf<ImageSet>() // Main thread; covers every accepted, unconsumed payload.
        private var glFixture: ImageSet? = null // GL thread only.
        /** Atomic hand-off: the main thread may replace a queued image while GL is taking it. */
        private val pendingUpload = AtomicReference<PendingUpload?>(null)
        private var uploadQueued = false
        private data class PendingUpload(val image: ImageSet, val generation: Int)
        private val loader = Executors.newSingleThreadExecutor()
        private lateinit var renderer: ShimmerRenderer
        private lateinit var gestures: P0GestureDetector

        override fun diagnosticEvent(event: String, details: String) = P0Diagnostics.event(scopeId, event, details)

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            scopeId = "$instance/${if (isPreview) "PREVIEW" else "FORMAL"}"
            gestures = P0GestureDetector(this@P0WallpaperService) { kind ->
                if (P0Diagnostics.enabled && !destroyed && visible) {
                    P0Diagnostics.gesture(scopeId, kind)
                    if (kind == P0GestureMachine.TWO_FINGER_EDIT) {
                        P0EditActivity.request(this@P0WallpaperService, scopeId, fixture?.id ?: "C")
                    }
                }
            }
            renderer = ShimmerRenderer(this)
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            setRenderMode(RENDERMODE_WHEN_DIRTY)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
            P0Controls.add(P0Controls.EngineControl(scopeId,
                rebuild = { contextToo -> requestP0Rebuild(contextToo) }, reload = { reloadFixture() }))
            P0Diagnostics.event(scopeId, "ENGINE_CREATE", "preview=$isPreview ${gestures.thresholdSummary}")
            reloadFixture()
        }

        private fun reloadFixture() {
            val generation = ++loadGeneration
            val uri = P0TestImage.selectedUri(this@P0WallpaperService)
            loader.execute {
                val image = try {
                    if (uri == null) P0TestImage.calibration() else P0TestImage.decode(this@P0WallpaperService, uri)
                } catch (failed: Exception) {
                    P0Diagnostics.event(scopeId, "FIXTURE_LOAD_FAILED", "type=${failed.javaClass.simpleName} configRetained=true")
                    null
                }
                main.post {
                    if (destroyed || generation != loadGeneration) { image?.recycleAll(); return@post }
                    if (image == null) {
                        P0Diagnostics.event(scopeId, "FIXTURE_NOT_COMMITTED", "configRetained=true displayRetained=true")
                        // First launch with inaccessible URI shows C, retaining the stored URI.
                        if (fixture == null) publish(P0TestImage.calibration())
                    } else publish(image)
                }
            }
        }

        private fun publish(image: ImageSet) {
            val generation = loadGeneration
            ownedImages.add(image)
            val previousPending = pendingUpload.getAndSet(PendingUpload(image, generation))
            previousPending?.let { releaseOwned(it.image) }
            scheduleUploadIfNeeded()
        }

        /** Main-thread scheduler keeps at most one GL upload closure and only the newest payload. */
        private fun scheduleUploadIfNeeded() {
            if (destroyed || uploadQueued || pendingUpload.get() == null) return
            uploadQueued = true
            val accepted = queueEvent {
                // Atomic get-and-clear prevents publish() from replacing and recycling this
                // payload between the read and the clear on the GL thread.
                val pending = pendingUpload.getAndSet(null)
                main.post {
                    uploadQueued = false
                    scheduleUploadIfNeeded()
                }
                if (pending == null) return@queueEvent
                if (pending.generation != loadGeneration) {
                    main.post { releaseOwned(pending.image) }
                } else {
                    val previous = glFixture
                    renderer.setEffectTransitionDuration(0)
                    renderer.setImage(pending.image)
                    renderer.enableBlur(false, true)
                    glFixture = pending.image
                    main.post {
                        if (!destroyed) {
                            // The renderer now owns the target state. Keep this ImageSet until a
                            // later setImage replaces it or Engine destruction joins the GL thread.
                            fixture = pending.image
                            if (previous != null && previous !== pending.image) releaseOwned(previous)
                            P0Diagnostics.event(scopeId, "FIXTURE_UPLOAD_ACK", "testImage=${pending.image.id} generation=${pending.generation} " +
                                "presentedUnconfirmed=true")
                        }
                    }
                }
            }
            if (!accepted) {
                uploadQueued = false
                val rejected = pendingUpload.getAndSet(null)
                rejected?.let { releaseOwned(it.image) }
                P0Diagnostics.event(scopeId, "FIXTURE_QUEUE_REJECTED", "configRetained=true")
            } else {
                requestRender()
            }
        }

        private fun releaseOwned(image: ImageSet) {
            if (ownedImages.remove(image)) image.recycleAll()
        }

        override fun onRendererReady() {
            P0Diagnostics.event(scopeId, "RENDERER_READY")
            // Existing renderer restores the last CPU image itself; queued initial upload runs next.
        }
        override fun onReadyForNextImage() = Unit
        override fun onSurfaceDimensionsChanged(width: Int, height: Int) {
            P0Diagnostics.event(scopeId, "RENDER_VIEWPORT", "surfaceWidth=$width surfaceHeight=$height")
        }

        override fun onTouchEvent(event: MotionEvent) {
            P0Diagnostics.rawTouch(scopeId, event)
            if (P0Diagnostics.enabled && visible) gestures.onTouchEvent(event) else gestures.reset()
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (::gestures.isInitialized && !visible) gestures.reset()
            super.onVisibilityChanged(visible)
            P0Diagnostics.screen(this@P0WallpaperService, scopeId, "VISIBILITY", visible)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            surfaceGeneration++
            P0Diagnostics.event(scopeId, "ANDROID_SURFACE_CREATE", "generation=$surfaceGeneration valid=${holder.surface.isValid}")
            super.onSurfaceCreated(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val wm = getSystemService(WindowManager::class.java)
            val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.toShortString()
                else "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}"
            P0Diagnostics.event(scopeId, "VIEWPORT_BASELINE", "generation=$surfaceGeneration surface=${width}x$height " +
                "displayBounds=$bounds density=${resources.displayMetrics.density}")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            P0Diagnostics.event(scopeId, "ANDROID_SURFACE_DESTROY", "generation=$surfaceGeneration")
            super.onSurfaceDestroyed(holder) // GL owner handles release; never delete GL resources here.
        }

        override fun onOffsetsChanged(x: Float, y: Float, xs: Float, ys: Float, xp: Int, yp: Int) {
            P0Diagnostics.event(scopeId, "OFFSETS", "x=$x y=$y stepX=$xs stepY=$ys pixelX=$xp pixelY=$yp")
        }
        override fun onZoomChanged(zoom: Float) { P0Diagnostics.event(scopeId, "SYSTEM_ZOOM", "zoom=$zoom") }
        override fun onApplyWindowInsets(insets: WindowInsets) {
            super.onApplyWindowInsets(insets)
            @Suppress("DEPRECATION")
            P0Diagnostics.event(scopeId, "INSETS", "left=${insets.systemWindowInsetLeft} top=${insets.systemWindowInsetTop} " +
                "right=${insets.systemWindowInsetRight} bottom=${insets.systemWindowInsetBottom}")
        }
        override fun onWallpaperFlagsChanged(which: Int) { P0Diagnostics.event(scopeId, "WALLPAPER_FLAGS", "flags=$which") }

        override fun onDestroy() {
            destroyed = true
            loadGeneration++
            P0Controls.remove(scopeId)
            gestures.close()
            loader.shutdownNow()
            super.onDestroy() // Joins GL thread before CPU bitmaps can be recycled.
            ownedImages.forEach { it.recycleAll() }
            ownedImages.clear()
            pendingUpload.getAndSet(null)
            uploadQueued = false
            fixture = null
            P0Diagnostics.event(scopeId, "ENGINE_DESTROY", "surfaceGeneration=$surfaceGeneration")
        }
    }
}
