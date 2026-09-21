package dev.abdus.apps.shimmer

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES30
import android.os.SystemClock
import dev.abdus.apps.shimmer.gl.GLWallpaperService
import dev.abdus.apps.shimmer.gl.ShimmerProgram
import java.util.concurrent.atomic.AtomicReference

class ShimmerRenderer(private val callbacks: Callbacks) : GLWallpaperService.Renderer {

    interface Callbacks {
        fun requestRender()
        fun onRendererReady()
        fun onReadyForNextImage()
        fun onSurfaceDimensionsChanged(width: Int, height: Int)
    }

    companion object {
        private const val TAG = "ShimmerRenderer"
    }

    private var currentImage = ImageRenderer()
    private var previousImage = ImageRenderer()
    private var pendingImageSet: ImageSet? = null
    private val pendingParallaxOffset = AtomicReference<Float?>(null)
    private val pendingTouches = AtomicReference<List<TouchData>?>(null)

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
    private var ownerContext: EGLContext? = null
    private var ownerThread: Thread? = null

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
        surfaceCreated = true

        // Restore current image texture if it was already set
        animationController.targetRenderState.let { state ->
            if (!state.imageSet.original.isRecycled) {
                currentImage.load(state.imageSet)
                viewportManager.setCurrentImageAspectRatio(state.imageSet.aspectRatio)
            }
        }
        // A new context has no previous texture. Restore the current image fully visible.
        viewportManager.setImageTransitionState(null)
        animationController.imageTransitionAnimator.reset()
        animationController.imageTransitionAnimator.currentValue = 1f

        pendingImageSet?.let { setImage(it); pendingImageSet = null }
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

        pendingParallaxOffset.getAndSet(null)?.let { newParallax ->
            viewportManager.setParallaxTarget(newParallax)
        }

        pendingTouches.getAndSet(null)?.let { newTouches ->
            touchAnimator.setActiveTouches(newTouches)
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val isAnimating = animationController.tick()
        val viewportAnimating = viewportManager.tick()
        val touchAnimating = touchAnimator.tick()
        val state = animationController.currentRenderState
        val imageAlpha = animationController.imageTransitionAnimator.currentValue

        val (mvp, prevMvp) = viewportManager.getMvpMatrices()

        val blurPercent = state.blurPercent.coerceIn(0f, 1f)
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
        val timeSeconds = SystemClock.elapsedRealtime() / 1000f

        if (imageAlpha < 1f && prevMvp != null) {
            previousImage.draw(
                activeProgram.handles, prevMvp, blurPercent, 1f,
                effectiveDuotone, state.dimAmount * blurPercent, state.grain, grainCounts,
                touchPointsArray, touchIntensitiesArray, aspectRatio, timeSeconds,
            )
        }

        currentImage.draw(
            activeProgram.handles, mvp, blurPercent, imageAlpha,
            effectiveDuotone, state.dimAmount * blurPercent, state.grain, grainCounts,
            touchPointsArray, touchIntensitiesArray, aspectRatio, timeSeconds,
        )

        if (!animationController.imageTransitionAnimator.isRunning && imageAlpha >= 1f) {
            previousImage.release()
        }

        if (isAnimating || viewportAnimating || touchAnimating) {
            callbacks.requestRender()
        }
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

    fun setImage(imageSet: ImageSet) {
        if (!surfaceCreated) {
            pendingImageSet = imageSet
            return
        }
        requireOwnerContext()

        val previousAspect = currentImage.aspectRatio

        // TRIPLE-BUFFER PREVENTION / MEMORY MANAGEMENT:
        // If a transition was already in progress (e.g., A is fading out, B is fading in)
        // and we receive a new image C, we must release A immediately.
        //
        // Technical Note: glDeleteTextures is asynchronous. When we call release() on A,
        // the texture ID is invalidated for the CPU, but the OpenGL driver keeps the
        // texture data alive in VRAM until the GPU finishes executing all pending
        // draw commands that reference it. This prevents "tripling" our memory usage
        // during rapid image changes while avoiding a crash mid-draw.
        if (animationController.imageTransitionAnimator.isRunning) {
            previousImage.release()
        }
        previousImage = currentImage
        currentImage = ImageRenderer().apply {
            load(imageSet)
        }

        viewportManager.setCurrentImageAspectRatio(imageSet.aspectRatio)
        viewportManager.setImageTransitionState(previousAspect)

        animationController.updateTargetState(
            animationController.targetRenderState.copy(imageSet = imageSet)
        )
        callbacks.requestRender()
    }

    fun setEffectTransitionDuration(ms: Int) = animationController.setDuration(ms)

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
        previousImage.release()
        currentImage.release()
        onContextLost()
    }

    override fun onContextLost() {
        // CPU-only: the driver reclaims the previous context's GL objects.
        program = null
        previousImage = ImageRenderer()
        currentImage = ImageRenderer()
        surfaceCreated = false
        ownerContext = null
        ownerThread = null
    }

    override fun resourceSummary(): String =
        "programs=${if (program != null) 1 else 0} textures=${currentImage.textureCount + previousImage.textureCount}"

    private fun requireOwnerContext() {
        check(Thread.currentThread() === ownerThread && ownerContext != null &&
            EGL14.eglGetCurrentContext() == ownerContext) { "GL access outside owning thread/context" }
    }

    fun isBlurred() = animationController.currentRenderState.blurPercent > 0.01f

    fun isAnimating() = animationController.blurAmountAnimator.isRunning ||
                       animationController.imageTransitionAnimator.isRunning
}
