package dev.abdus.apps.shimmer.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.ArrayDeque

/**
 * One render thread and unshared EGL context per Engine. All renderer callbacks,
 * including resource disposal, belong to that thread. A window Surface is not a context.
 */
open class GLWallpaperService : WallpaperService() {
    companion object {
        const val RENDERMODE_WHEN_DIRTY = 0
        const val RENDERMODE_CONTINUOUSLY = 1
    }

    override fun onCreateEngine(): Engine = GLEngine()

    interface Renderer {
        /** Called exactly once for each newly created, current context. */
        fun onSurfaceCreated()
        fun onSurfaceChanged(width: Int, height: Int)
        fun onDrawFrame()
        /** Release GL objects; only called on the render thread with their context current. */
        fun onSurfaceDestroyed() {}
        /** Forget stale object names only. MUST NOT call GL, including glDelete*. */
        fun onContextLost() {}
        /** Numeric resource counts only; called on the render thread at lifecycle boundaries. */
        fun resourceSummary(): String = ""
    }

    open inner class GLEngine : Engine() {
        private var glThread: GLThread? = null
        private var eglContextClientVersion = 3

        /** Implementations must be thread safe and bound/sanitize stored diagnostics. */
        protected open fun diagnosticEvent(event: String, details: String) {}

        override fun onVisibilityChanged(visible: Boolean) {
            glThread?.setVisible(visible)
            super.onVisibilityChanged(visible)
        }

        override fun onDestroy() {
            glThread?.requestExitAndWait()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            glThread?.resize(width, height)
            super.onSurfaceChanged(holder, format, width, height)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            glThread?.surfaceCreated(holder)
            super.onSurfaceCreated(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // Wait until this thread has stopped using the Android Surface. No GL on main.
            glThread?.surfaceDestroyedAndWait()
            super.onSurfaceDestroyed(holder)
        }

        fun setRenderer(renderer: Renderer) {
            check(glThread == null) { "Renderer already set" }
            glThread = GLThread(renderer, eglContextClientVersion, ::diagnosticEvent).also {
                it.setVisible(isVisible)
                it.start()
            }
        }

        fun setEGLContextClientVersion(version: Int) {
            check(glThread == null) { "Renderer already set" }
            require(version == 2 || version == 3)
            eglContextClientVersion = version
        }

        fun setRenderMode(renderMode: Int) {
            require(renderMode == RENDERMODE_WHEN_DIRTY || renderMode == RENDERMODE_CONTINUOUSLY)
            glThread?.setRenderMode(renderMode)
        }

        open fun requestRender() { glThread?.requestRender() }

        /** False means no thread/closed/full queue; callers must not treat rejection as applied. */
        fun queueEvent(runnable: Runnable): Boolean = glThread?.queueEvent(runnable) ?: false

        /** Local diagnostic experiment. Requests coalesce and wait for visibility + valid Surface. */
        fun requestP0Rebuild(contextToo: Boolean) { glThread?.requestRebuild(contextToo) }
    }
}

/** EGL displays are shared even though contexts are not; terminate only after the last Engine. */
private object EglDisplayPool {
    private var display: EGLDisplay? = null
    private var users = 0

    @Synchronized fun acquire(): EGLDisplay {
        if (users == 0) {
            val candidate = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (candidate == EGL14.EGL_NO_DISPLAY) throw EglFailure("get_display", EGL14.eglGetError())
            val version = IntArray(2)
            if (!EGL14.eglInitialize(candidate, version, 0, version, 1)) {
                throw EglFailure("initialize", EGL14.eglGetError())
            }
            display = candidate
        }
        users++
        return checkNotNull(display)
    }

    @Synchronized fun release(): Int? {
        if (users == 0) return null
        users--
        if (users == 0) {
            val failure = if (EGL14.eglTerminate(display)) null else EGL14.eglGetError()
            display = null
            return failure
        }
        return null
    }
}

private class EglFailure(val operation: String, val code: Int) : RuntimeException(operation)
private class GlFailure(val operation: String, val code: Int) : RuntimeException(operation)

/** Accessed exclusively on its owning GLThread. */
private class EglHelper(
    private val clientVersion: Int,
    private val error: (String, Int) -> Unit,
) {
    private var display: EGLDisplay? = null
    private var config: EGLConfig? = null
    private var context: EGLContext? = null
    private var window: EGLSurface? = null
    val hasContext: Boolean get() = context != null
    val hasSurface: Boolean get() = window != null
    val isCurrent: Boolean get() = context != null && EGL14.eglGetCurrentContext() == context
    val currentId: String get() = EGL14.eglGetCurrentContext().nativeHandle.toString(16)
    val contextId: String get() = context?.nativeHandle?.toString(16) ?: "0"

    fun createContext() {
        check(context == null)
        if (display == null) display = EglDisplayPool.acquire()
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, if (clientVersion == 3) 64 else 4,
            EGL14.EGL_NONE,
        )
        checked(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0), "choose_config")
        if (count[0] == 0) throw EglFailure("no_config", EGL14.EGL_BAD_CONFIG)
        config = configs[0]
        val created = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE), 0,
        )
        if (created == null || created == EGL14.EGL_NO_CONTEXT) {
            throw EglFailure("create_context", EGL14.eglGetError())
        }
        context = created
    }

    fun createWindow(holder: SurfaceHolder, onCreated: () -> Unit) {
        check(context != null && window == null)
        val created = EGL14.eglCreateWindowSurface(
            display, config, holder.surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (created == null || created == EGL14.EGL_NO_SURFACE) {
            throw EglFailure("create_window", EGL14.eglGetError())
        }
        window = created
        checked(EGL14.eglMakeCurrent(display, window, window, context), "make_current")
        checked(EGL14.eglSwapInterval(display, 1), "swap_interval")
        onCreated()
    }

    fun swap() { checked(EGL14.eglSwapBuffers(display, window), "swap_buffers") }

    /**
     * A tiny pbuffer permits correct glDelete when Engine exits after its window was destroyed.
     * If binding fails, caller abandons names; destroying the context reclaims its objects.
     */
    fun releaseWithCurrent(block: () -> Unit): Boolean {
        if (!hasContext) return false
        if (isCurrent) {
            block()
            return true
        }
        val scratch = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (scratch == null || scratch == EGL14.EGL_NO_SURFACE) {
            error("cleanup_pbuffer", EGL14.eglGetError())
            return false
        }
        try {
            if (!EGL14.eglMakeCurrent(display, scratch, scratch, context)) {
                error("cleanup_make_current", EGL14.eglGetError())
                return false
            }
            block()
            return true
        } finally {
            report(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "cleanup_unbind")
            report(EGL14.eglDestroySurface(display, scratch), "cleanup_destroy_pbuffer")
        }
    }

    fun destroyWindow() {
        val old = window ?: return
        report(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "unbind")
        report(EGL14.eglDestroySurface(display, old), "destroy_window")
        window = null
    }

    fun destroyContext() {
        destroyWindow()
        val old = context ?: return
        report(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "context_unbind")
        report(EGL14.eglDestroyContext(display, old), "destroy_context")
        context = null
    }

    fun finish() {
        destroyContext()
        if (display != null) EglDisplayPool.release()?.let { error("terminate", it) }
        display = null
        config = null
    }

    private fun checked(ok: Boolean, operation: String) {
        if (!ok) throw EglFailure(operation, EGL14.eglGetError())
    }
    private fun report(ok: Boolean, operation: String) {
        if (!ok) error(operation, EGL14.eglGetError())
    }
}

private class GLThread(
    private val renderer: GLWallpaperService.Renderer,
    clientVersion: Int,
    private val diagnostic: (String, String) -> Unit,
) : Thread() {
    private val monitor = Object()
    private var done = false
    private var exited = false
    private var visible = false
    private var holder: SurfaceHolder? = null
    private var width = 0
    private var height = 0
    private var sizeChanged = true
    private var renderRequested = true
    private var renderMode = GLWallpaperService.RENDERMODE_WHEN_DIRTY
    private var idleRequested = false
    private var destroySerial = 0L
    private var acknowledgedDestroy = 0L
    private var rebuildSurface = false
    private var rebuildContext = false
    private var blocked = false
    private val events = ArrayDeque<Runnable>()

    // These fields and every EGL/renderer access belong solely to this render thread.
    private var contextGeneration = 0L
    private var contextDestroys = 0L
    private var surfaceGeneration = 0L
    private var surfaceCreates = 0L
    private var surfaceDestroys = 0L
    private var resourceCreates = 0L
    private var resourceReleases = 0L
    private var resourceLost = 0L
    private var resourcesLive = false
    private var errorCount = 0L
    private var consecutiveContextLosses = 0
    private val egl = EglHelper(clientVersion) { operation, code ->
        errorCount++
        emit("egl_error", "operation=$operation code=$code")
    }

    init { name = "WallpaperGL-$id" }

    private data class Work(
        val idle: Boolean = false,
        val destroySerial: Long = 0,
        val holder: SurfaceHolder? = null,
        val width: Int = 0,
        val height: Int = 0,
        val resized: Boolean = false,
        val rebuildSurface: Boolean = false,
        val rebuildContext: Boolean = false,
    )

    override fun run() {
        try {
            emit("gl_thread_start")
            while (true) {
                val work = awaitWork() ?: break
                if (work.idle) {
                    dropSurface("invisible_or_surface_destroyed")
                    synchronized(monitor) {
                        acknowledgedDestroy = maxOf(acknowledgedDestroy, work.destroySerial)
                        monitor.notifyAll()
                    }
                    continue
                }
                try {
                    render(work)
                    consecutiveContextLosses = 0
                } catch (failure: EglFailure) {
                    errorCount++
                    emit("egl_error", "operation=${failure.operation} code=${failure.code}")
                    if (failure.code == EGL14.EGL_CONTEXT_LOST) {
                        disposeContext(lost = true, reason = "driver_context_lost")
                        consecutiveContextLosses++
                        synchronized(monitor) {
                            renderRequested = true
                            blocked = consecutiveContextLosses >= 3
                        }
                    } else {
                        dropSurface("egl_failure")
                        synchronized(monitor) { blocked = true }
                    }
                } catch (failure: RuntimeException) {
                    // Do not expose exception messages: queued work may originate from private URIs.
                    errorCount++
                    emit("renderer_error", "type=${failure.javaClass.simpleName}")
                    disposeContext(lost = !egl.isCurrent, reason = "renderer_error")
                    synchronized(monitor) { blocked = true }
                }
            }
        } catch (_: InterruptedException) {
            emit("gl_thread_interrupted")
            currentThread().interrupt()
        } finally {
            try {
                disposeContext(lost = false, reason = "engine_exit")
            } finally {
                try {
                    egl.finish()
                } finally {
                    // Always release Surface-destruction waiters, even if EGL cleanup or
                    // a diagnostic callback unexpectedly throws during thread shutdown.
                    synchronized(monitor) {
                        exited = true
                        acknowledgedDestroy = destroySerial
                        events.clear()
                        monitor.notifyAll()
                    }
                }
                emit("gl_thread_exit")
            }
        }
    }

    private fun awaitWork(): Work? = synchronized(monitor) {
        while (true) {
            if (done) return@synchronized null
            if (idleRequested) {
                idleRequested = false
                return@synchronized Work(idle = true, destroySerial = destroySerial)
            }
            if (visible && holder != null && width > 0 && height > 0 && !blocked &&
                (renderRequested || sizeChanged || rebuildSurface || rebuildContext ||
                    events.isNotEmpty() || renderMode == GLWallpaperService.RENDERMODE_CONTINUOUSLY)
            ) {
                val work = Work(
                    holder = holder, width = width, height = height, resized = sizeChanged,
                    rebuildSurface = rebuildSurface, rebuildContext = rebuildContext,
                )
                sizeChanged = false
                renderRequested = false
                rebuildSurface = false
                rebuildContext = false
                return@synchronized work
            }
            monitor.wait()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun render(work: Work) {
        if (work.rebuildContext) {
            emit("p0_rebuild_begin", "kind=context")
            disposeContext(lost = false, reason = "p0_controlled_context")
        } else if (work.rebuildSurface) {
            emit("p0_rebuild_begin", "kind=surface")
            dropSurface("p0_controlled_surface")
        }

        if (!egl.hasContext) {
            egl.createContext()
            contextGeneration++
            emit("egl_context_created")
        }
        val newSurface = !egl.hasSurface
        if (newSurface) {
            // Creation and current binding are separate observations, including failed binds.
            egl.createWindow(checkNotNull(work.holder)) {
                surfaceGeneration++
                surfaceCreates++
                emit("egl_surface_created", "width=${work.width} height=${work.height}")
            }
            emit("egl_surface_bound")
        }
        check(egl.isCurrent) { "Renderer without current owner context" }
        if (!resourcesLive) {
            // Mark before invoking so partial initialization still receives cleanup.
            resourcesLive = true
            resourceCreates++
            renderer.onSurfaceCreated()
            checkGl("resource_create")
            emit("gl_resources_created", renderer.resourceSummary())
        }
        if (work.resized || newSurface) {
            renderer.onSurfaceChanged(work.width, work.height)
            checkGl("resize")
            emit("gl_resized", "width=${work.width} height=${work.height}")
        }
        // Commands never run before context binding + initialization + viewport setup.
        // Keep commands queued if EGL setup fails. Bound the batch so producers cannot
        // indefinitely prevent Surface destruction; an exception preserves later commands.
        val eventCount = synchronized(monitor) { events.size }
        repeat(eventCount) {
            val event = synchronized(monitor) { events.pollFirst() }
            event?.run()
        }
        check(egl.isCurrent)
        renderer.onDrawFrame()
        checkGl("draw")
        egl.swap()
        if (newSurface || work.rebuildContext || work.rebuildSurface) {
            emit("gl_presented", renderer.resourceSummary())
        }
    }

    private fun dropSurface(reason: String) {
        if (!egl.hasSurface) return
        egl.destroyWindow()
        surfaceDestroys++
        emit("egl_surface_destroyed", "reason=$reason")
    }

    private fun disposeContext(lost: Boolean, reason: String) {
        if (resourcesLive) {
            var released = false
            if (!lost) {
                try {
                    released = egl.releaseWithCurrent {
                        check(egl.isCurrent)
                        renderer.onSurfaceDestroyed()
                        checkGl("resource_release")
                        resourceReleases++
                        emit("gl_resources_released", renderer.resourceSummary())
                    }
                } catch (failure: RuntimeException) {
                    errorCount++
                    emit("gl_release_error", "type=${failure.javaClass.simpleName}")
                }
            }
            if (!released) {
                // An invalid/lost context's names must NEVER be deleted in another context.
                renderer.onContextLost()
                resourceLost++
                emit("gl_resources_abandoned", "reason=$reason ${renderer.resourceSummary()}")
            }
            resourcesLive = false
        }
        dropSurface(reason)
        if (egl.hasContext) {
            egl.destroyContext()
            contextDestroys++
            emit("egl_context_destroyed", "reason=$reason lost=$lost")
        }
    }

    private fun checkGl(operation: String) {
        var firstError = GLES30.GL_NO_ERROR
        repeat(8) {
            val code = GLES30.glGetError()
            if (code == GLES30.GL_NO_ERROR) {
                if (firstError != GLES30.GL_NO_ERROR) throw GlFailure(operation, firstError)
                return
            }
            if (firstError == GLES30.GL_NO_ERROR) firstError = code
            errorCount++
            emit("gl_error", "operation=$operation code=$code")
            // GLES context loss forbids further GL cleanup; EGL release remains safe.
            if (code == 0x0507) throw EglFailure("gl_context_lost", EGL14.EGL_CONTEXT_LOST)
        }
        // Stop an invalid render loop instead of flooding diagnostics every animation frame.
        throw GlFailure(operation, firstError)
    }

    private fun emit(event: String, details: String = "") {
        diagnostic(event, "thread=$name tid=$id current=${egl.currentId} owned=${egl.contextId} " +
            "contextGen=$contextGeneration contextDestroy=$contextDestroys surfaceGen=$surfaceGeneration " +
            "surfaceCreate=$surfaceCreates surfaceDestroy=$surfaceDestroys " +
            "resourceCreate=$resourceCreates resourceRelease=$resourceReleases resourceLost=$resourceLost " +
            "errors=$errorCount $details")
    }

    fun setVisible(value: Boolean) = synchronized(monitor) {
        visible = value
        if (!value) idleRequested = true
        if (value) { renderRequested = true; blocked = false }
        monitor.notifyAll()
    }
    fun resize(w: Int, h: Int) = synchronized(monitor) {
        width = w; height = h; sizeChanged = true; blocked = false
        monitor.notifyAll()
    }
    fun surfaceCreated(value: SurfaceHolder) = synchronized(monitor) {
        holder = value
        sizeChanged = true
        renderRequested = true
        blocked = false
        monitor.notifyAll()
    }
    fun surfaceDestroyedAndWait() = synchronized(monitor) {
        holder = null
        destroySerial++
        val expected = destroySerial
        idleRequested = true
        monitor.notifyAll()
        var interrupted = false
        while (!exited && acknowledgedDestroy < expected && isAlive) {
            try { monitor.wait() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) currentThread().interrupt()
    }
    fun requestRebuild(contextToo: Boolean) = synchronized(monitor) {
        rebuildSurface = true
        rebuildContext = rebuildContext || contextToo
        blocked = false
        renderRequested = true
        monitor.notifyAll()
    }
    fun requestRender() = synchronized(monitor) {
        renderRequested = true
        monitor.notifyAll()
    }
    fun setRenderMode(value: Int) = synchronized(monitor) {
        renderMode = value
        monitor.notifyAll()
    }
    fun queueEvent(event: Runnable): Boolean = synchronized(monitor) {
        if (done || exited || events.size >= 128) return@synchronized false
        events.addLast(event)
        renderRequested = true
        monitor.notifyAll()
        true
    }
    fun requestExitAndWait() {
        synchronized(monitor) { done = true; monitor.notifyAll() }
        if (currentThread() == this) return
        var interrupted = false
        while (isAlive) {
            try { join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) currentThread().interrupt()
    }
}
