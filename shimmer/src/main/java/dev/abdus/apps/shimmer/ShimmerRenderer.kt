package dev.abdus.apps.shimmer

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES30
import android.os.SystemClock
import dev.abdus.apps.shimmer.gl.FillProgram
import dev.abdus.apps.shimmer.gl.GLWallpaperService
import dev.abdus.apps.shimmer.gl.ShimmerProgram
import dev.abdus.apps.shimmer.gl.TransitionProgram
import dev.abdus.apps.shimmer.gl.TransitionTarget
import dev.abdus.apps.shimmer.p3.FrameStats
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LatestTransformSlot
import dev.abdus.apps.shimmer.p3.TransformMath
import dev.abdus.apps.shimmer.p5.TransitionEffect
import dev.abdus.apps.shimmer.p5.TransitionSpec
import dev.abdus.apps.shimmer.p5.TransitionStats
import dev.abdus.apps.shimmer.p5.TransitionTimeline
import java.util.concurrent.atomic.AtomicReference

class ShimmerRenderer(private val callbacks: Callbacks, mediaContext: android.content.Context? = null) : GLWallpaperService.Renderer {
    private val mediaOwner = mediaContext?.let { dev.abdus.apps.shimmer.media.MediaPlaybackOwner(it, callbacks::requestRender) }
    private fun newImageRenderer() = ImageRenderer(mediaOwner, callbacks::requestRender)
    fun setMediaPlaybackAllowed(allowed: Boolean) { mediaOwner?.setAllowed(allowed) }
    fun observeMediaKeyguard(locked: Boolean) { mediaOwner?.observeKeyguard(locked) }

    interface Callbacks {
        fun requestRender()
        fun onRendererReady()
        fun onReadyForNextImage()
        fun onSurfaceDimensionsChanged(width: Int, height: Int)
        /** Called only after EGL swapped a frame containing this image; not a decode receipt. */
        fun onImagePresented(imageId: String, presentationToken: Any) {}
        fun onMediaFrameState(imageId: String, presentationToken: Any, state: dev.abdus.apps.shimmer.media.MediaFrameState?) {}
        fun onPresentationInvalidated() {}
    }

    companion object {
        private const val TAG = "ShimmerRenderer"
    }

    /**
     * One displayed image with its own placement (REQ-EDIT-04: old and new images keep their own
     * transform and fill). Everything except [image] is CPU state and survives context loss.
     * A null [transform] keeps the legacy aspect-fill framing with parallax.
     */
    private class Layer(
        var image: ImageRenderer = ImageRenderer(),
        var imageId: String = "",
        var presentationToken: Any = Any(),
        var transform: ImageTransform? = null,
        var sourceWidth: Int = 0,
        var sourceHeight: Int = 0,
    ) {
        private data class MatrixKey(val transform: ImageTransform, val w: Int, val h: Int, val vw: Int, val vh: Int)

        private var cacheKey: MatrixKey? = null
        private var foregroundMvp: FloatArray? = null
        private var fillMvp: FloatArray? = null
        private var clip: FloatArray? = null

        fun foregroundMatrix(viewWidth: Int, viewHeight: Int): FloatArray? {
            update(viewWidth, viewHeight)
            return foregroundMvp
        }

        fun drawFill(fill: FillProgram, viewWidth: Int, viewHeight: Int, dimAmount: Float, alpha: Float) {
            update(viewWidth, viewHeight)
            val mvp = fillMvp ?: return
            image.drawBackground(fill, mvp, clip, dimAmount, alpha)
        }

        private fun update(viewWidth: Int, viewHeight: Int) {
            val t = transform
            if (t == null) {
                cacheKey = null; foregroundMvp = null; fillMvp = null; clip = null
                return
            }
            val key = MatrixKey(t, sourceWidth, sourceHeight, viewWidth, viewHeight)
            if (key == cacheKey) return
            cacheKey = key
            val foreground = TransformMath.foregroundMvp(t, sourceWidth, sourceHeight, viewWidth, viewHeight)
            foregroundMvp = foreground
            // No fill when the image already covers every screen pixel.
            val showFill = t.blurFill && !TransformMath.coversViewport(t, sourceWidth, sourceHeight, viewWidth, viewHeight)
            fillMvp = if (showFill) TransformMath.backgroundMvp(t, sourceWidth, sourceHeight, viewWidth, viewHeight) else null
            clip = if (showFill) TransformMath.clipToQuad(foreground) else null
        }

        companion object {
            fun of(imageSet: ImageSet, transform: ImageTransform?, image: ImageRenderer) =
                Layer(image, imageSet.id, imageSet, transform, imageSet.sourceWidth, imageSet.sourceHeight)
        }
    }

    private class PendingImage(val imageSet: ImageSet, val transform: ImageTransform?, val spec: TransitionSpec)

    /** Render-thread state of the running P5 transition (REQ-FX-01). */
    private class ActiveTransition(
        val spec: TransitionSpec,
        val recorder: TransitionStats.Recorder?,
        val stats: TransitionStats?,
        val startEpoch: Int,
    ) {
        var peakLayers = 0
        var peakTargets = 0
        var fallbackRecorded = false
    }

    private var currentLayer = Layer(image = newImageRenderer())
    private var previousLayer = Layer(image = newImageRenderer())
    private var pendingImage: PendingImage? = null
    private val pendingParallaxOffset = AtomicReference<Float?>(null)
    private val pendingTouches = AtomicReference<List<TouchData>?>(null)
    // Latest-only delivery while dragging: newer placements overwrite older ones (REQ-PERF-01).
    private val pendingLiveTransform = LatestTransformSlot()

    private val viewportManager = ViewportManager()

    private val animationController = AnimationController().apply {
        onImageAnimationComplete = {
            viewportManager.setImageTransitionState(null)
            callbacks.onReadyForNextImage()
        }
    }
    private val touchAnimator = TouchAnimationController()

    private var surfaceCreated = false
    private var surfaceDimensions = SurfaceDimensions(0, 0)

    private var program: ShimmerProgram? = null
    private var fillProgram: FillProgram? = null
    private var transitionProgram: TransitionProgram? = null
    // Holds at most one offscreen copy of the incoming composite (CROSSFADE / ZOOM only).
    private val transitionTarget = TransitionTarget()
    private var activeTransition: ActiveTransition? = null
    // Used when a caller passes no spec; follows setEffectTransitionDuration like the legacy fade.
    @Volatile
    private var defaultTransition = TransitionSpec.crossfade(AnimationController.DEFAULT_DURATION_MILLIS)
    private var ownerContext: EGLContext? = null
    private var ownerThread: Thread? = null

    /** GL_MAX_TEXTURE_SIZE of the current context, 0 before the first context (REQ-SRC-05). */
    @Volatile
    var maxTextureSize = 0
        private set

    /** Attached only while this renderer's image is being edited, so other engines never record. */
    @Volatile
    var frameStats: FrameStats? = null

    /** P5 transition diagnostics; attached only on the home-screen engine, never in previews. */
    @Volatile
    var transitionStats: TransitionStats? = null

    // Bumped whenever the engine is hidden; a transition spanning a change is marked paused.
    @Volatile
    private var hiddenEpoch = 0

    // Render-thread only: markers carried from the frame being drawn to its presentation.
    private var frameInputUptime = 0L
    private var pendingSave: Pair<FrameStats, Long>? = null
    private var frameSave: Pair<FrameStats, Long>? = null

    override fun onSurfaceCreated() {
        val context = EGL14.eglGetCurrentContext()
        check(context != EGL14.EGL_NO_CONTEXT) { "Renderer creation requires a current context" }
        if (surfaceCreated && ownerContext == context) {
            requireOwnerContext()
            return
        }
        // GLSurfaceView can recreate a context without a destruction callback. Old names
        // must be forgotten, never deleted in this new context where names may be reused.
        onContextLost()
        ownerContext = context
        ownerThread = Thread.currentThread()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        // Let the owning thread report and clean up partial initialization failures.
        program = ShimmerProgram()
        fillProgram = FillProgram()
        transitionProgram = TransitionProgram()
        surfaceCreated = true
        val maxSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        maxTextureSize = maxSize[0]

        // Restore current image texture if it was already set; its placement is kept on the layer.
        animationController.targetRenderState.let { state ->
            if (!state.imageSet.original.isRecycled) {
                currentLayer.image.load(state.imageSet)
                viewportManager.setCurrentImageAspectRatio(state.imageSet.aspectRatio)
            }
        }
        // A new context has no previous texture. Restore the current image fully visible.
        viewportManager.setImageTransitionState(null)
        animationController.imageTransitionAnimator.reset()
        animationController.imageTransitionAnimator.currentValue = 1f

        pendingImage?.let { setImage(it.imageSet, it.transform, it.spec); pendingImage = null }
        callbacks.onRendererReady()
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        requireOwnerContext()
        GLES30.glViewport(0, 0, width, height)
        surfaceDimensions = SurfaceDimensions(width, height)
        viewportManager.setSurfaceDimensions(surfaceDimensions)
        callbacks.onSurfaceDimensionsChanged(width, height)
    }

    override fun onDrawFrame() {
        if (!surfaceCreated) return
        requireOwnerContext()
        val activeProgram = checkNotNull(program)
        val activeFill = checkNotNull(fillProgram)
        val activeTransitionProgram = checkNotNull(transitionProgram)
        val stats = frameStats
        val drawStart = System.nanoTime()

        pendingParallaxOffset.getAndSet(null)?.let { newParallax ->
            viewportManager.setParallaxTarget(newParallax)
        }

        pendingTouches.getAndSet(null)?.let { newTouches ->
            touchAnimator.setActiveTouches(newTouches)
        }

        frameInputUptime = 0L
        pendingLiveTransform.take()?.let { live ->
            if (live.imageId == currentLayer.imageId) {
                currentLayer.transform = live.transform
                frameInputUptime = live.inputUptimeMillis
            }
        }
        // Keep an unpresented save marker (failed swap) for the next presented frame.
        pendingSave?.let { frameSave = it }
        pendingSave = null

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val isAnimating = animationController.tick()
        val viewportAnimating = viewportManager.tick()
        val touchAnimating = touchAnimator.tick()
        val state = animationController.currentRenderState
        // Linear 0 → 1; each effect applies its own easing (P5 plan "效果定义").
        val progress = animationController.imageTransitionAnimator.currentValue
        val frameNow = SystemClock.elapsedRealtime()

        val (mvp, prevMvp) = viewportManager.getMvpMatrices()

        val blurPercent = state.blurPercent.coerceIn(0f, 1f)
        currentLayer.image.prepareMediaFrame(blurPercent)
        previousLayer.image.prepareMediaFrame(blurPercent)
        val effectiveDuotone = state.duotone.copy(
            opacity = if (state.duotoneAlwaysOn) state.duotone.opacity
                     else (state.duotone.opacity * blurPercent)
        )

        val grainCounts = if (state.grain.enabled) {
            val grainSizePx = GrainSettings.GRAIN_SIZE_MIN_IMAGE_PX +
                (GrainSettings.GRAIN_SIZE_MAX_IMAGE_PX - GrainSettings.GRAIN_SIZE_MIN_IMAGE_PX) * state.grain.scale
            (state.imageSet.width.toFloat() / grainSizePx) to (state.imageSet.height.toFloat() / grainSizePx)
        } else 0f to 0f

        val (touchPointsArray, touchIntensitiesArray) = touchAnimator.getTouchPointArrays()
        val aspectRatio = surfaceDimensions.aspectRatio
        val timeSeconds = frameNow / 1000f
        val dimAmount = state.dimAmount * blurPercent
        val viewWidth = surfaceDimensions.width
        val viewHeight = surfaceDimensions.height

        // One composite = a layer's fill plus its image, each with its own placement. The fill is
        // clipped out under its image, so a layer's fill never shows through its own image.
        fun drawComposite(layer: Layer, legacyMvp: FloatArray?, alpha: Float) {
            val layerMvp = if (layer.transform != null) layer.foregroundMatrix(viewWidth, viewHeight) else legacyMvp
            if (layerMvp == null || layer.image.isEmpty) return
            layer.drawFill(activeFill, viewWidth, viewHeight, dimAmount, alpha)
            layer.image.draw(
                activeProgram.handles, layerMvp, blurPercent, alpha,
                effectiveDuotone, dimAmount, state.grain, grainCounts,
                touchPointsArray, touchIntensitiesArray, aspectRatio, timeSeconds,
            )
        }

        val transition = activeTransition
        if (transition == null || progress >= 1f) {
            drawComposite(currentLayer, mvp, 1f)
        } else {
            drawTransitionFrame(transition, progress, viewWidth, viewHeight, activeTransitionProgram,
                drawOld = { drawComposite(previousLayer, prevMvp, 1f) },
                drawNew = { alpha -> drawComposite(currentLayer, mvp, alpha) })
        }

        if (isAnimating || viewportAnimating || touchAnimating) {
            callbacks.requestRender()
        }
        val drawNanos = System.nanoTime() - drawStart
        stats?.recordDraw(drawNanos, SystemClock.uptimeMillis())
        if (transition != null) {
            transition.recorder?.frame(drawNanos, drawStart)
            val animator = animationController.imageTransitionAnimator
            if (!animator.isRunning) {
                finishTransition(transition, interrupted = false, actualMillis = frameNow - animator.startTime)
            }
        } else if (!previousLayer.image.isEmpty) {
            // Guard for X5: an outgoing image must never outlive its transition.
            transitionStats?.recordStaleFrame()
            previousLayer.image.release()
        }
    }

    /** Draws one frame of [transition] at linear [progress] (P5 plan "效果定义"). */
    private inline fun drawTransitionFrame(
        transition: ActiveTransition, progress: Float, viewWidth: Int, viewHeight: Int,
        program: TransitionProgram, drawOld: () -> Unit, drawNew: (alpha: Float) -> Unit,
    ) {
        val frame = TransitionTimeline.frame(transition.spec.effect, progress)
        if (transition.spec.effect == TransitionEffect.SLIDE) {
            // Both composites are drawn into viewports shifted by whole pixels, edge to edge.
            val offsets = TransitionTimeline.slideOffsets(transition.spec.direction, frame.slide, viewWidth, viewHeight)
            GLES30.glViewport(offsets.oldX, offsets.oldY, viewWidth, viewHeight)
            drawOld()
            GLES30.glViewport(offsets.newX, offsets.newY, viewWidth, viewHeight)
            drawNew(1f)
            GLES30.glViewport(0, 0, viewWidth, viewHeight)
        } else {
            if (frame.drawOld) drawOld()
            if (frame.drawNew) {
                if (!frame.needsTarget) {
                    drawNew(1f)
                } else if (transitionTarget.ensure(viewWidth, viewHeight)) {
                    // Fade / scale the incoming composite as one layer.
                    transitionTarget.begin()
                    drawNew(1f)
                    transitionTarget.end(viewWidth, viewHeight)
                    program.drawTexture(transitionTarget.texture, frame.newAlpha, frame.newScale)
                } else {
                    // fbo_fallback: per-pass alpha without zoom scale, as before P5.
                    if (!transition.fallbackRecorded) {
                        transition.fallbackRecorded = true
                        transition.stats?.recordTargetFallback()
                    }
                    drawNew(frame.newAlpha)
                }
            }
            if (frame.blackOverlay > 0f) program.drawBlack(frame.blackOverlay)
        }
        transition.peakLayers = maxOf(transition.peakLayers, heldLayers())
        transition.peakTargets = maxOf(transition.peakTargets, if (transitionTarget.isAllocated) 1 else 0)
    }

    private fun heldLayers() = (if (currentLayer.image.isEmpty) 0 else 1) + (if (previousLayer.image.isEmpty) 0 else 1)

    /**
     * Ends [transition]: releases the outgoing image and the offscreen target before anything
     * else is drawn, then reports the outcome to [TransitionStats] when attached.
     */
    private fun finishTransition(transition: ActiveTransition, interrupted: Boolean, actualMillis: Long) {
        activeTransition = null
        previousLayer.image.release()
        transitionTarget.release()
        viewportManager.setImageTransitionState(null)
        if (interrupted) {
            animationController.imageTransitionAnimator.reset()
            animationController.imageTransitionAnimator.currentValue = 1f
        }
        val recorder = transition.recorder ?: return
        recorder.paused = transition.startEpoch != hiddenEpoch
        recorder.finish(
            interrupted = interrupted,
            actualMillis = actualMillis,
            residual = residualObjects(),
            peakLayers = transition.peakLayers,
            peakTargets = transition.peakTargets,
            nativeHeapBytes = android.os.Debug.getNativeHeapAllocatedSize(),
            imageId = currentLayer.imageId,
        )
    }

    /** GL objects of the outgoing image and the offscreen target still held (X4: must be 0). */
    private fun residualObjects() = previousLayer.image.textureCount +
        (if (previousLayer.image.hasBackground) 1 else 0) + transitionTarget.objectCount

    override fun onFramePresented() {
        if (activeTransition == null && currentLayer.image.presentationReady) {
            callbacks.onImagePresented(currentLayer.imageId, currentLayer.presentationToken)
            callbacks.onMediaFrameState(currentLayer.imageId, currentLayer.presentationToken, currentLayer.image.mediaState)
        }
        val now = SystemClock.uptimeMillis()
        if (frameInputUptime > 0L) {
            frameStats?.recordInputLatency(now - frameInputUptime)
            frameInputUptime = 0L
        }
        frameSave?.let { (stats, saveUptime) -> stats.recordSave(now - saveUptime) }
        frameSave = null
    }

    fun updateSettings(
        blurAmount: Float, dimAmount: Float, duotone: DuotoneSettings, grain: GrainSettings,
        chromatic: ChromaticAberrationSettings, transitionDuration: Long,
    ) {
        setEffectTransitionDuration(transitionDuration.toInt())
        setUserDimAmount(dimAmount)
        setDuotoneSettings(duotone.enabled, duotone.alwaysOn,
            Duotone(duotone.lightColor, duotone.darkColor,
                   if (duotone.enabled) 1f else 0f, duotone.blendMode))
        setGrainSettings(grain.enabled, grain.amount, grain.scale)
        setChromaticAberrationSettings(chromatic.enabled, chromatic.intensity,
                                      chromatic.fadeDurationMillis.toInt())
    }

    /**
     * Switches to [imageSet] with [spec] (P5). [transform] is its P3 placement; null keeps the
     * legacy aspect-fill framing with parallax (P0, splash and settings previews).
     */
    fun setImage(imageSet: ImageSet, transform: ImageTransform? = null, spec: TransitionSpec = defaultTransition) {
        if (!surfaceCreated) {
            pendingImage = PendingImage(imageSet, transform, spec)
            return
        }
        requireOwnerContext()

        // TRIPLE-BUFFER PREVENTION / MEMORY MANAGEMENT:
        // If a transition was already in progress (e.g., A is leaving, B is entering) and we
        // receive a new image C, A is released now and C starts from B (P5 interrupt rule).
        //
        // Technical Note: glDeleteTextures is asynchronous. When we call release() on A,
        // the texture ID is invalidated for the CPU, but the OpenGL driver keeps the
        // texture data alive in VRAM until the GPU finishes executing all pending
        // draw commands that reference it. This prevents "tripling" our memory usage
        // during rapid image changes while avoiding a crash mid-draw.
        activeTransition?.let { finishTransition(it, interrupted = true, actualMillis = 0L) }
        previousLayer.image.release()

        val previousAspect = currentLayer.image.aspectRatio
        previousLayer = currentLayer
        previousLayer.image.freezeMedia()
        currentLayer = Layer.of(imageSet, transform, newImageRenderer()).apply {
            image.load(imageSet)
        }

        viewportManager.setCurrentImageAspectRatio(imageSet.aspectRatio)
        viewportManager.setImageTransitionState(previousAspect)

        val animator = animationController.imageTransitionAnimator
        animator.durationMillis = if (spec.animated) spec.durationMillis else 0
        animationController.updateTargetState(
            animationController.targetRenderState.copy(imageSet = imageSet)
        )
        val stats = transitionStats
        if (spec.animated && animator.isRunning) {
            activeTransition = ActiveTransition(spec, stats?.open(spec.effect, spec.durationMillis), stats, hiddenEpoch)
        } else {
            // Instant switch: the outgoing image is released before the next frame.
            previousLayer.image.release()
            viewportManager.setImageTransitionState(null)
            animator.reset()
            animator.currentValue = 1f
            stats?.open(TransitionEffect.NONE, 0)?.finish(
                interrupted = false, actualMillis = 0L, residual = residualObjects(),
                peakLayers = heldLayers(), peakTargets = 0,
                nativeHeapBytes = android.os.Debug.getNativeHeapAllocatedSize(), imageId = imageSet.id,
            )
        }
        callbacks.requestRender()
    }

    /**
     * Swaps in a finer decode of the image already on screen, keeping its placement and without
     * a crossfade. Returns false when that image is no longer current; the caller then still
     * owns [imageSet]. On true the renderer owns it and the caller may recycle the old set.
     */
    fun replaceImage(imageSet: ImageSet): Boolean {
        pendingImage?.let { pending ->
            if (pending.imageSet.id != imageSet.id) return false
            pendingImage = PendingImage(imageSet, pending.transform, pending.spec)
            return true
        }
        if (imageSet.id != currentLayer.imageId) return false
        currentLayer.sourceWidth = imageSet.sourceWidth
        currentLayer.sourceHeight = imageSet.sourceHeight
        if (surfaceCreated) {
            requireOwnerContext()
            currentLayer.image.load(imageSet)
            viewportManager.setCurrentImageAspectRatio(imageSet.aspectRatio)
        }
        animationController.replaceImageInPlace(imageSet)
        callbacks.requestRender()
        return true
    }

    /** Applies a committed placement to [imageId] if it is (or is about to be) displayed. */
    fun setLayerTransform(imageId: String, transform: ImageTransform?) {
        // A live placement queued before this one is older and must not overwrite it.
        pendingLiveTransform.discard(imageId)
        val pending = pendingImage
        if (pending != null) {
            if (pending.imageSet.id == imageId) pendingImage = PendingImage(pending.imageSet, transform, pending.spec)
        } else if (currentLayer.imageId == imageId) {
            currentLayer.transform = transform
        }
        callbacks.requestRender()
    }

    /** Thread-safe drag path: only the newest placement reaches the render thread. */
    fun setLiveTransform(imageId: String, transform: ImageTransform, inputUptimeMillis: Long) {
        pendingLiveTransform.offer(imageId, transform, inputUptimeMillis)
        callbacks.requestRender()
    }

    /** Render thread: the next presented frame, after everything queued before, closes a save. */
    fun markApplied(stats: FrameStats, saveUptimeMillis: Long) {
        pendingSave = stats to saveUptimeMillis
        callbacks.requestRender()
    }

    /** Effect fade duration; also the crossfade used by callers that pass no transition spec. */
    fun setEffectTransitionDuration(ms: Int) {
        animationController.setDuration(ms)
        defaultTransition = if (ms > 0) TransitionSpec.crossfade(ms) else TransitionSpec.NONE
    }

    /** Main thread: the engine became invisible; a transition spanning this is marked paused. */
    fun markHidden() {
        hiddenEpoch++
    }

    fun setUserDimAmount(amount: Float) {
        animationController.updateTargetState(
            animationController.targetRenderState.copy(dimAmount = amount)
        )
    }

    fun setDuotoneSettings(enabled: Boolean, alwaysOn: Boolean, duotone: Duotone) {
        animationController.updateTargetState(
            animationController.targetRenderState.copy(duotone = duotone, duotoneAlwaysOn = alwaysOn)
        )
    }

    fun setGrainSettings(enabled: Boolean, amount: Float, scale: Float) {
        animationController.updateTargetState(
            animationController.targetRenderState.copy(grain = GrainSettings(enabled, amount, scale))
        )
    }

    fun setChromaticAberrationSettings(enabled: Boolean, intensity: Float, fadeMs: Int) {
        val newSettings = ChromaticAberrationSettings(
            enabled = enabled,
            intensity = intensity.coerceIn(0f, 1f),
            fadeDurationMillis = fadeMs.toLong()
        )
        animationController.updateTargetState(
            animationController.targetRenderState.copy(chromaticAberration = newSettings)
        )
        touchAnimator.setSettings(newSettings)
        callbacks.requestRender()
    }

    fun setTouchPoints(touches: List<TouchData>) {
        pendingTouches.set(touches)
        callbacks.requestRender()
    }

    fun enableBlur(enabled: Boolean, immediate: Boolean) {
        val target = if (enabled) 1f else 0f
        val newState = animationController.targetRenderState.copy(blurPercent = target)
        if (immediate) {
            animationController.setRenderStateImmediately(newState)
            animationController.blurAmountAnimator.reset()
        } else {
            animationController.updateTargetState(newState)
        }
        callbacks.requestRender()
    }

    fun onVisibilityChanged() {
        // Reset parallax to target without animation
        // viewportManager.resetParallax(animationController.targetRenderState.parallaxOffset)
    }

    fun setParallaxOffset(offset: Float) {
        pendingParallaxOffset.set(offset)
        callbacks.requestRender()
    }

    override fun onSurfaceDestroyed() {
        if (ownerContext == null) return
        requireOwnerContext()
        program?.release()
        program = null
        fillProgram?.release()
        fillProgram = null
        transitionProgram?.release()
        transitionProgram = null
        transitionTarget.release()
        previousLayer.image.release()
        currentLayer.image.release()
        onContextLost()
    }

    override fun onContextLost() {
        callbacks.onPresentationInvalidated()
        // CPU-only: the driver reclaims the previous context's GL objects. Placements survive.
        // A transition cut off here is dropped without stats; the next context starts settled.
        program = null
        fillProgram = null
        transitionProgram = null
        transitionTarget.forget()
        activeTransition = null
        previousLayer.image.abandon()
        currentLayer.image.abandon()
        previousLayer = Layer(image = newImageRenderer())
        currentLayer.image = newImageRenderer()
        surfaceCreated = false
        ownerContext = null
        ownerThread = null
    }

    override fun resourceSummary(): String {
        val programs = (if (program != null) 1 else 0) + (if (fillProgram != null) 1 else 0) +
            (if (transitionProgram != null) 1 else 0)
        val textures = currentLayer.image.textureCount + previousLayer.image.textureCount
        val fills = (if (currentLayer.image.hasBackground) 1 else 0) + (if (previousLayer.image.hasBackground) 1 else 0)
        val targets = if (transitionTarget.isAllocated) 1 else 0
        val videos = currentLayer.image.videoTextureCount + previousLayer.image.videoTextureCount
        return "programs=$programs textures=$textures fill_textures=$fills transition_targets=$targets video_textures=$videos"
    }

    private fun requireOwnerContext() {
        check(Thread.currentThread() === ownerThread && ownerContext != null &&
            EGL14.eglGetCurrentContext() == ownerContext) { "GL access outside owning thread/context" }
    }

    fun isBlurred() = animationController.currentRenderState.blurPercent > 0.01f

    fun isAnimating() = animationController.blurAmountAnimator.isRunning ||
                       animationController.imageTransitionAnimator.isRunning
}
