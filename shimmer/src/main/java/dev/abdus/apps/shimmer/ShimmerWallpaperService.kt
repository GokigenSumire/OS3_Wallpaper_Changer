package dev.abdus.apps.shimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import dev.abdus.apps.shimmer.gl.GLWallpaperService
import dev.abdus.apps.shimmer.p0.P0GestureDetector
import dev.abdus.apps.shimmer.p0.P0GestureMachine
import dev.abdus.apps.shimmer.p0.P0Diagnostics
import dev.abdus.apps.shimmer.p0.EngineDiagnosticState
import dev.abdus.apps.shimmer.p0.DiagnosticToken
import dev.abdus.apps.shimmer.p2.PlaybackCoordinator
import dev.abdus.apps.shimmer.p2.PlaybackDirection
import dev.abdus.apps.shimmer.p2.PlaybackImageResolver
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p2.PlaybackStepTiming
import dev.abdus.apps.shimmer.p2.PlaybackWorkEvidence
import dev.abdus.apps.shimmer.p2.PlaybackWorkScope
import dev.abdus.apps.shimmer.p2.PlaybackCatalogTiming
import dev.abdus.apps.shimmer.p2.CatalogRefreshDemand
import dev.abdus.apps.shimmer.p2.PlaybackSelectionBridge
import dev.abdus.apps.shimmer.p2.PlaybackTimingResult
import dev.abdus.apps.shimmer.p2.PlaybackTimingStage
import dev.abdus.apps.shimmer.p2.PlaybackStateTimingStage
import dev.abdus.apps.shimmer.p2.PlaybackImageHandoff
import dev.abdus.apps.shimmer.p2.currentEntryTransform
import dev.abdus.apps.shimmer.p2.forPersistence
import dev.abdus.apps.shimmer.p2.sourceRestoreFollowUp
import dev.abdus.apps.shimmer.p3.DecodeGate
import dev.abdus.apps.shimmer.p3.EditHoldGate
import dev.abdus.apps.shimmer.p3.FitMode
import dev.abdus.apps.shimmer.p3.FrameStats
import dev.abdus.apps.shimmer.p3.HeldAction
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p3.P3Defaults
import dev.abdus.apps.shimmer.p3.P3EditActivity
import dev.abdus.apps.shimmer.p3.ResolvedTransform
import dev.abdus.apps.shimmer.p3.TransformResolution
import dev.abdus.apps.shimmer.p4.ScheduleCoordinator
import dev.abdus.apps.shimmer.p4.ScheduleDispatch
import dev.abdus.apps.shimmer.p4.ScheduleTarget
import dev.abdus.apps.shimmer.p4.ScheduledSwitchPolicy
import dev.abdus.apps.shimmer.p4.SwitchOutcome
import dev.abdus.apps.shimmer.p5.TransitionPlanner
import dev.abdus.apps.shimmer.p5.TransitionSettingsStore
import dev.abdus.apps.shimmer.p5.TransitionSpec
import dev.abdus.apps.shimmer.p5.TransitionStats
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p10.ProductionRoutePolicy
import dev.abdus.apps.shimmer.p10.editor.LockEditorBridge
import dev.abdus.apps.shimmer.p10.editor.LockEditDiagnostics
import dev.abdus.apps.shimmer.p10.editor.LockEditDiagnosticCause
import dev.abdus.apps.shimmer.p10.editor.LockEngineEditController
import dev.abdus.apps.shimmer.p10.input.LockEngineHostRegistry
import dev.abdus.apps.shimmer.p10.input.LockCommandHostService
import dev.abdus.apps.shimmer.p10.input.LockHostState
import dev.abdus.apps.shimmer.p10.input.LockHostResult
import personal.wallpaper.protocol.lock.LockProtocol
import java.util.UUID
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest

/** Result of one playback step, handed back to the main thread for renderer work. */
private sealed interface PlaybackOutcome {
    /** Nothing readable in the catalog; the displayed image is kept. */
    object NoCandidate : PlaybackOutcome

    /** A newer request or an invisible engine superseded this step. */
    object Dropped : PlaybackOutcome

    /** Another engine or the page published first; reload from the stored snapshot. */
    object Stale : PlaybackOutcome

    class Committed(
        val image: ImageSet, val uri: Uri, val transform: ImageTransform,
        val sourceRestored: Boolean = false,
    ) : PlaybackOutcome
}

private class NativeLockRequest(val id: String, val complete: (LockHostResult) -> Unit) {
    val diagnostic = LockEditDiagnostics.currentRequest()
    var action = 0
    var originalImage: String? = null
    var generation = -1L
    var expectedImage: String? = null
    var expectedToken: Any? = null
}

open class ShimmerWallpaperService : GLWallpaperService() {
    open val wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME
    companion object {
        const val TAG = "ShimmerWallpaperService"

        /** Collapses the burst of snapshot and catalog notifications into one refresh. */
        private const val SNAPSHOT_REFRESH_DEBOUNCE_MILLIS = 120L

        /** A finer decode after zooming waits for the fingers to settle. */
        private const val REFINE_DEBOUNCE_MILLIS = 400L
    }

    override fun onCreateEngine(): Engine = ShimmerWallpaperEngine()

    inner class ShimmerWallpaperEngine : GLEngine(), ShimmerRenderer.Callbacks, LiveEditBridge.Target, ScheduleTarget {

        private var renderer: ShimmerRenderer? = null
        @Volatile private var rendererReady = false
        @Volatile private var engineVisible = true
        @Volatile private var routeAllowed = true
        private var runtimeInitialized = false
        private var routeRevoked = false
        @Volatile private var observedFlags: Int? = null
        private var lockRelayBinding: Closeable? = null
        private val lockInputSceneId = UUID.randomUUID().toString()
        private var lockImageTicket = LockProtocol.sceneTicket(lockInputSceneId, UUID.randomUUID().toString())
        @Volatile private var activeLockEditRequestId: String? = null
        @Volatile private var nativeLockRequest: NativeLockRequest? = null
        @Volatile private var lastPresentedLockToken: Any? = null
        private var lockPresentationToken: Any? = null
        private val diagnosticId = P0Diagnostics.newId("P2")
        // Main owns these detached observations; export never touches the Engine or EGL.
        @Volatile private var diagnosticAlive = false
        private var diagnosticPreview: Boolean? = null
        private var diagnosticTouchRequested: Boolean? = null
        private var diagnosticVisible: Boolean? = null
        private var diagnosticSurfaceValid: Boolean? = null
        private var diagnosticWidth: Int? = null
        private var diagnosticHeight: Int? = null

        private fun diagnosticState(source: String = "OWNER_CACHE") = EngineDiagnosticState(
            scope = diagnosticId, serviceType = "PLAYBACK", isPreview = diagnosticPreview,
            alive = diagnosticAlive, touchRequested = diagnosticTouchRequested,
            visible = diagnosticVisible, surfaceValid = diagnosticSurfaceValid,
            width = diagnosticWidth, height = diagnosticHeight,
            rendererReady = rendererReady, editHold = editHold.holding, source = source,
        )

        private fun publishDiagnosticState(source: String, token: DiagnosticToken? = null) {
            if (!diagnosticAlive) return
            val state = diagnosticState(source)
            // A delayed diagnostic sample cannot enter a later run, but the live owner cache
            // remains truthful and is available to the next baseline or export metadata.
            if (token != null) P0Diagnostics.updateEngineCache(state)
            P0Diagnostics.updateEngineState(state, token)
        }

        override fun requestRender() {
            if (engineVisible && routeAllowed) super.requestRender()
        }

        // Renderer and image-set ownership stays on Main. Queue/history work runs on
        // Dispatchers.Default under playbackMutex, and provider access and decoding use IO.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, failure ->
                Log.e(TAG, "P2 background operation failed; retaining the displayed image", failure)
                P0Diagnostics.event(diagnosticId, "P2_ERROR", "type=${failure.javaClass.simpleName}")
            })
        private val preferences = WallpaperPreferences.create(this@ShimmerWallpaperService, wallpaperTarget)
        private val folderRepository = ImageFolderRepository(this@ShimmerWallpaperService, scope, wallpaperTarget)
        private val favoritesRepository = FavoritesRepository(this@ShimmerWallpaperService, preferences, folderRepository)
        private val imageLoader = ImageLoader(contentResolver, resources)

        /** Holds switches while this Engine's image is in the P3 editor. */
        private val editHold = EditHoldGate()
        private val decodeGate = DecodeGate()
        @Volatile private var editSession: EngineEditSession? = null

        /** Placement of the image on screen; the inheritance source for the next fresh advance. */
        private var displayedTransform: ImageTransform? = null
        private var refineJob: kotlinx.coroutines.Job? = null
        /** Sample size the running refine decodes at, so a repeat request does not restart it. */
        private var refineSample = 0
        /** The refine job currently inside its decode, which a restart would waste. */
        private var refineDecodingJob: kotlinx.coroutines.Job? = null
        private var transformRefreshJob: kotlinx.coroutines.Job? = null

        /** P4: the app-wide scheduler; this Engine executes its switches while it is the executor. */
        private val schedule = ScheduleCoordinator.get(this@ShimmerWallpaperService, wallpaperTarget)
        /** Scheduled switch whose playback job is running; its outcome is still owed. */
        private var activeDispatch: ScheduleDispatch? = null
        /** Scheduled switch waiting for the running transition, beside [pendingPlaybackDirection]. */
        private var pendingPlaybackDispatch: ScheduleDispatch? = null
        /** At most one already-authenticated native command waits for this Engine's visual transition. */
        private var pendingNativeLockRequest: NativeLockRequest? = null
        /** Scheduled switch received before the renderer was ready; replayed in [onRendererReady]. */
        private var parkedDispatch: ScheduleDispatch? = null
        /** P2 business gestures reuse the P0-tested delayed double/triple state machine. */
        private val p2GestureDetector = P0GestureDetector(this@ShimmerWallpaperService) { action ->
            P0Diagnostics.event(diagnosticId, "P2_GESTURE", "preview=$isPreview action=$action hold=${editHold.holding}")
            if (routeAllowed && editHold.admitGesture()) {
                when (action) {
                    P0GestureMachine.DOUBLE_NEXT -> requestImageCycle()
                    P0GestureMachine.TRIPLE_PREV -> requestPlayback(PlaybackDirection.PREVIOUS)
                    // REQ-GES-02: the formal wallpaper always opens the editor; previews never do.
                    P0GestureMachine.TWO_FINGER_EDIT ->
                        if (isPreview) handleGesture(TapGesture.TWO_FINGER_DOUBLE_TAP) else openEditor()
                }
            }
        }
        private val playbackCoordinator = PlaybackCoordinator(randomSeed = 0x504C41594241434BL)
        private val playbackSnapshotStore = PlaybackSnapshotStore(this@ShimmerWallpaperService, wallpaperTarget)
        // P5: the settings are read at each switch, so a change applies to the next switch.
        private val transitionStore = TransitionSettingsStore(this@ShimmerWallpaperService, wallpaperTarget)
        private val transitionPlanner = TransitionPlanner()

        /** Binder revocation is visible to IO before its asynchronous Main cleanup arrives. */
        private var lockRequestAuthorization: (String) -> Boolean = LockCommandHostService::isRequestAuthorized

        private val lockEditorDelegate = lazy {
            LockEngineEditController(this@ShimmerWallpaperService, scope, folderRepository,
                preferences, playbackSnapshotStore,
                capture = { captureLockImage() },
                isEligible = { diagnosticAlive && !isPreview && routeAllowed &&
                    wallpaperTarget == WallpaperTarget.LOCK && observedFlags == 2 &&
                    (activeLockEditRequestId?.let(lockRequestAuthorization) != false) },
                acquireHold = { acquireLockHold() },
                releaseHold = { releaseLockHold() },
                decodeInfo = { uri, transform ->
                    val decoded = decodeImage(uri, transform)
                    if (decoded == null) null else try {
                        LockEngineEditController.DecodedInfo(decoded.sourceWidth, decoded.sourceHeight)
                    } finally { decoded.recycleAll() }
                },
                publicationGuard = { write ->
                    val requestId = activeLockEditRequestId
                    if (requestId == null) write()
                    else LockCommandHostService.withRequestAuthorization(requestId, write) ?: false
                })
        }
        private val lockEditor get() = lockEditorDelegate.value
        private val lockHostTarget = object : LockEngineHostRegistry.Target {
            override fun retire() {
                routeAllowed = false
                routeRevoked = true
                engineVisible = false
                this@ShimmerWallpaperEngine.onVisibilityChanged(false)
                rendererReady = false
                loadGeneration++
                renderer?.markHidden()
                nativeLockRequest?.let { finishNativeLock(it, "CANCELLED") }
                LockEditorBridge.unregister(diagnosticId)
                if (lockEditorDelegate.isInitialized()) lockEditor.invalidate()
                imageLoadingJob?.cancel()
                cancelPlaybackStep(SwitchOutcome.CANCELLED)
                scope.cancel()
            }
            override fun state(): LockHostState {
                val reason = when {
                    !diagnosticAlive || isPreview || !routeAllowed || wallpaperTarget != WallpaperTarget.LOCK || observedFlags != 2 -> "NO_ENGINE"
                    cachedImageSet == null || currentImageUri == null -> "UNKNOWN_STATE"
                    lastPresentedLockToken !== lockPresentationToken -> "UNKNOWN_STATE"
                    playbackSnapshotStore.load()?.currentId != currentImageUri?.toString() -> "STALE_TICKET"
                    editHold.holding || nativeLockRequest != null || imageCycleJob?.isActive == true -> "BUSY_EDIT"
                    !engineVisible || !rendererReady -> "UNKNOWN_STATE"
                    else -> "READY"
                }
                return LockHostState(reason == "READY", if (reason == "NO_ENGINE") "" else lockImageTicket, reason)
            }
            override fun command(action: Int, ticket: String, requestId: String, complete: (LockHostResult) -> Unit) {
                if (!lockRequestAuthorization(requestId)) { complete(LockHostResult("CANCELLED")); return }
                if (ticket != lockImageTicket) { complete(LockHostResult("STALE_TICKET")); return }
                val state = state()
                if (!state.ready) { complete(LockHostResult(state.reason)); return }
                if (action == LockProtocol.EDIT) {
                    val pending = lockEditor.prepareLockEdit()
                    if (pending == null) { complete(LockHostResult("BUSY_EDIT")); return }
                    activeLockEditRequestId = requestId
                    val launch = LockEditorBridge.createPreparedPendingIntent(this@ShimmerWallpaperService, pending, diagnosticId)
                    LockEngineHostRegistry.refresh()
                    complete(if (launch == null) LockHostResult("FAILED") else LockHostResult("EDIT_READY", launch))
                } else if (action == LockProtocol.NEXT || action == LockProtocol.PREVIOUS) {
                    val request = NativeLockRequest(requestId, complete).also {
                        it.action = action
                        it.originalImage = currentImageUri?.toString()
                    }
                    nativeLockRequest = request
                    startPlayback(if (action == LockProtocol.NEXT) PlaybackDirection.NEXT else PlaybackDirection.PREVIOUS,
                        lockRequest = request)
                    LockEngineHostRegistry.refresh()
                } else complete(LockHostResult("UNKNOWN_STATE"))
            }
            override fun cancel(requestId: String) {
                nativeLockRequest?.takeIf { it.id == requestId }?.let {
                    loadGeneration++
                    cancelPlaybackStep(SwitchOutcome.CANCELLED)
                    finishNativeLock(it, "CANCELLED")
                }
                if (activeLockEditRequestId == requestId) {
                    LockEditorBridge.unregister(diagnosticId)
                    if (lockEditorDelegate.isInitialized()) lockEditor.invalidate()
                    if (diagnosticAlive && routeAllowed) LockEditorBridge.register(diagnosticId, lockEditor)
                }
            }
        }

        private fun captureLockImage(): LockEngineEditController.Captured? {
            val shown = cachedImageSet ?: return null
            val uri = currentImageUri ?: return null
            if (shown.id != uri.toString() || surfaceDimensions.width <= 0 || surfaceDimensions.height <= 0) return null
            val defaults = preferences.getP3Defaults().adaptedTo(shown.sourceWidth, shown.sourceHeight,
                surfaceDimensions.width, surfaceDimensions.height)
            return LockEngineEditController.Captured(LiveEditBridge.EditImage(shown.id,
                shown.sourceWidth, shown.sourceHeight, surfaceDimensions.width, surfaceDimensions.height,
                displayedTransform ?: defaults.toTransform(), defaults, shown.id !in preferences.getDisabledImages()), uri)
        }

        private fun acquireLockHold(): Boolean {
            if (editHold.holding || !routeAllowed || isPreview || wallpaperTarget != WallpaperTarget.LOCK) return false
            editHold.begin()
            loadGeneration++
            cancelPlaybackStep(SwitchOutcome.CANCELLED)
            imageLoadingJob?.cancel()
            playbackSnapshotRefreshJob?.cancel()
            transformRefreshJob?.cancel()
            p2GestureDetector.reset()
            editHold.admitRefresh()
            LockEngineHostRegistry.refresh()
            return true
        }

        private fun releaseLockHold() {
            activeLockEditRequestId = null
            val deferred = editHold.release()
            if (!isPreview) schedule.onEditEnded(diagnosticId)
            if (routeAllowed && diagnosticAlive) {
                refreshPlaybackStateFromSnapshot()
                refreshDisplayedTransform()
                scope.launch {
                    playbackSnapshotRefreshJob?.join()
                    val step = deferred.lastOrNull { it is HeldAction.Step } as? HeldAction.Step
                    step?.let { requestPlayback(it.direction, it.dispatch) }
                }
            }
            LockEngineHostRegistry.refresh()
        }

        private fun finishNativeLock(request: NativeLockRequest, status: String) {
            if (nativeLockRequest !== request) return
            if (pendingNativeLockRequest === request) {
                pendingNativeLockRequest = null
                pendingPlaybackDirection = null
                pendingPlaybackDispatch = null
            }
            nativeLockRequest = null
            if (status == "COMMITTED") request.diagnostic?.record(when {
                request.expectedImage == request.originalImage -> LockEditDiagnosticCause.NAV_UNCHANGED
                request.action == LockProtocol.NEXT -> LockEditDiagnosticCause.NAV_NEXT_PRESENTED
                else -> LockEditDiagnosticCause.NAV_PREVIOUS_PRESENTED
            })
            request.complete(LockHostResult(status))
            LockEngineHostRegistry.refresh()
        }

        private val presentationEpoch = java.util.concurrent.atomic.AtomicLong()
        override fun onPresentationInvalidated() {
            presentationEpoch.incrementAndGet()
            lastPresentedLockToken = null
            if (wallpaperTarget == WallpaperTarget.LOCK && !isPreview) scope.launch { LockEngineHostRegistry.refresh() }
        }

        override fun onMediaFrameState(imageId: String, presentationToken: Any, state: dev.abdus.apps.shimmer.media.MediaFrameState?) {
            val set = cachedImageSet ?: return
            if (isPreview || set !== presentationToken || set.id != imageId) return
            val entry = dev.abdus.apps.shimmer.media.MediaPlaybackStatus.Entry(
                set.mediaClip?.kind ?: dev.abdus.apps.shimmer.media.MediaKind.IMAGE, state)
            val store = dev.abdus.apps.shimmer.media.MediaPlaybackStatus
            if (store.states.value[wallpaperTarget] != entry) {
                store.record(wallpaperTarget, entry)
                P0Diagnostics.event(diagnosticId, "P11_MEDIA_PRESENTED", "kind=${entry.kind} state=${state ?: "IMAGE"}")
            }
        }

        override fun onImagePresented(imageId: String, presentationToken: Any) {
            if (wallpaperTarget != WallpaperTarget.LOCK || !diagnosticAlive || isPreview) return
            val epoch = presentationEpoch.get()
            if (nativeLockRequest == null && lastPresentedLockToken === presentationToken &&
                LockEngineHostRegistry.snapshot().ready) return
            scope.launch {
                if (wallpaperTarget != WallpaperTarget.LOCK || isPreview || !routeAllowed ||
                    !engineVisible || presentationToken !== lockPresentationToken || presentationEpoch.get() != epoch) return@launch
                lastPresentedLockToken = presentationToken
                LockEngineHostRegistry.refresh()
                val request = nativeLockRequest ?: return@launch
                if (!lockRequestAuthorization(request.id)) {
                    finishNativeLock(request, "CANCELLED")
                    return@launch
                }
                if (request.expectedImage == imageId && request.expectedToken === presentationToken &&
                    request.generation == loadGeneration)
                    finishNativeLock(request, "COMMITTED")
            }
        }

        /**
         * Serialises every coordinator and snapshot access so it can leave the main thread.
         *
         * P2 and this service share one process. Sorting a large catalog, parsing the stored
         * snapshot and comparing thousands of items on Main froze whichever page was on screen,
         * which is what showed up as a stalled list and a black wallpaper.
         */
        private val playbackMutex = Mutex()
        private val touchEffectController = TouchEffectController(
            onTouchPointsChanged = { touchPoints ->
                renderer?.setTouchPoints(touchPoints)
            },
        )

        private var cachedImageSet: ImageSet? = null
        private var pendingPlaybackDirection: PlaybackDirection? = null
        private var imageLoadingJob: kotlinx.coroutines.Job? = null
        private var imageCycleJob: kotlinx.coroutines.Job? = null
        private var playbackSnapshotRefreshJob: kotlinx.coroutines.Job? = null
        private var catalogObservationJob: kotlinx.coroutines.Job? = null
        private val catalogRefreshDemand = CatalogRefreshDemand()
        private var pendingSelectedImageId: String? = null
        private var selectionIntentToken: Any = Any()
        private var activeSelectedImageId: String? = null
        private var activeSelectedToken: Any? = null
        private var activeSelectedAttempt: Any? = null
        private val selectionTarget = object : PlaybackSelectionBridge.Target {
            override fun select(itemId: String): PlaybackSelectionBridge.Result {
                if (isPreview || !routeAllowed) return PlaybackSelectionBridge.Result.NO_ENGINE
                if (editHold.holding) return PlaybackSelectionBridge.Result.BUSY_EDIT
                selectionIntentToken = Any()
                parkedDispatch?.let { reportSchedule(it, SwitchOutcome.SUPERSEDED) }
                if (itemId == currentImageUri?.toString() && imageCycleJob?.isActive != true &&
                    imageLoadingJob?.isActive != true && playbackSnapshotStore.load()?.currentId == itemId) {
                    // No decode needed only if the real stored/current picture is already this A.
                    pendingSelectedImageId = null
                    pendingPlaybackDirection = null
                    pendingPlaybackDispatch?.let { reportSchedule(it, SwitchOutcome.SUPERSEDED) }
                    loadGeneration++
                    cancelPlaybackStep(SwitchOutcome.SUPERSEDED)
                    imageLoadingJob?.cancel()
                    return PlaybackSelectionBridge.Result.NOOP
                }
                pendingSelectedImageId = itemId
                pendingPlaybackDirection = null
                pendingPlaybackDispatch?.let { reportSchedule(it, SwitchOutcome.SUPERSEDED) }
                loadGeneration++
                cancelPlaybackStep(SwitchOutcome.SUPERSEDED)
                imageLoadingJob?.cancel()
                val canStart = engineVisible && rendererReady && renderer?.isAnimating() != true
                if (canStart) replaySelectedImage()
                return if (canStart) PlaybackSelectionBridge.Result.ACCEPTED else PlaybackSelectionBridge.Result.QUEUED
            }
        }
        // Read from Dispatchers.Default while a playback step decides whether it is still current.
        @Volatile private var loadGeneration = 0L
        private var currentImageUri: Uri? = null
        private var sessionBlurEnabled = preferences.getBlurAmount() > 0f

        private var surfaceDimensions = SurfaceDimensions(0, 0)

        private var gestureActionMap: Map<TapGesture, GestureAction> = emptyMap()

        private val blurTimeoutHandler = Handler(Looper.getMainLooper())
        private val blurTimeoutRunnable = Runnable {
            if (preferences.isBlurTimeoutEnabled() && engineVisible) {
                sessionBlurEnabled = true
                applyBlurState(immediate = false)
            }
        }


        private val preferenceListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && key in WallpaperPreferences.P3_DEFAULT_KEYS) {
                    refreshDisplayedTransform()
                } else if (key == WallpaperPreferences.KEY_DISABLED_IMAGES) {
                    refreshPlaybackStateFromSnapshot()
                } else {
                    syncRendererSettings(key)
                }
            }

        private val playbackSnapshotListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == PlaybackSnapshotStore.SNAPSHOT_KEY && rendererReady && !isPreview) {
                    refreshPlaybackSnapshotOnly()
                } else if (key != null && key == cachedImageSet?.id?.let(PlaybackSnapshotStore::transformKey)) {
                    // A saved placement for the image on screen, from this or another Engine.
                    refreshDisplayedTransform()
                }
            }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            diagnosticAlive = true
            diagnosticPreview = isPreview
            diagnosticVisible = isVisible
            P0Diagnostics.registerEngine(diagnosticState("OWNER_CREATE"))
            P0Diagnostics.event(diagnosticId, "P2_ENGINE_CREATE", "preview=$isPreview")
            routeAllowed = ProductionRoutePolicy.allowed(wallpaperTarget, attachedFlags(), isPreview,
                legacyHome = Build.VERSION.SDK_INT < 34)
            P0Diagnostics.event(diagnosticId, "P10_ROUTE", "target=$wallpaperTarget flags=${observedFlags ?: "UNKNOWN"} preview=$isPreview allowed=$routeAllowed")
            if (routeAllowed) initializeRuntime()
            else P0Diagnostics.event(diagnosticId, "P10_ROUTE_REJECTED", "target=$wallpaperTarget")
        }

        private fun attachedFlags(): Int? = (if (Build.VERSION.SDK_INT >= 34) {
            runCatching { wallpaperFlags }.getOrNull()
        } else null).also { observedFlags = it }

        /** Only a fixed role with actual matching flags may create the production LOCK runtime. */
        private fun initializeRuntime() {
            if (runtimeInitialized || routeRevoked || !routeAllowed) return
            runtimeInitialized = true
            Log.d(TAG, "Engine onCreate: initializing renderer and resources")
            renderer = ShimmerRenderer(this, this@ShimmerWallpaperService.applicationContext)
            sampleMediaKeyguard()
            // Transition diagnostics come from the home-screen engine only, never from previews.
            if (!isPreview) renderer?.transitionStats = if (wallpaperTarget == WallpaperTarget.HOME) TransitionStats.shared else TransitionStats()
            setEGLContextClientVersion(3)
            setRenderer(renderer!!)
            setRenderMode(RENDERMODE_WHEN_DIRTY)
            setTouchEventsEnabled(true)
            diagnosticTouchRequested = true
            publishDiagnosticState("OWNER_TOUCH_REQUEST")
            setOffsetNotificationsEnabled(true)

            refreshGestureActionCache()
            preferences.registerListener(preferenceListener)
            playbackSnapshotStore.registerListener(playbackSnapshotListener)
            // Old shortcut actions have no target. They must never control both domains.
            if (!isPreview && wallpaperTarget == WallpaperTarget.HOME) Actions.registerReceivers(this@ShimmerWallpaperService, shortcutReceiver)
            registerReceiver(screenUnlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT).apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
            if (!isPreview) {
                // LOCK cannot use the transparent HOME editor after secure unlock.
                if (wallpaperTarget == WallpaperTarget.HOME) LiveEditBridge.register(diagnosticId, this, wallpaperTarget)
                PlaybackSelectionBridge.register(diagnosticId, selectionTarget, wallpaperTarget)
                schedule.register(diagnosticId, this)
                if (wallpaperTarget == WallpaperTarget.LOCK) {
                    LockCommandHostService.endpoint = LockEngineHostRegistry
                    LockEngineHostRegistry.register(diagnosticId, lockHostTarget)
                    LockEditorBridge.register(diagnosticId, lockEditor)
                    lockRelayBinding = LockCommandHostService.keepRelayAlive(this@ShimmerWallpaperService)
                }
            }

            // P1 is the authoritative catalog. A disabled source is not a request to advance
            // history; refresh the catalog and let the next user gesture skip inactive entries.
            catalogObservationJob = scope.launch {
                dev.abdus.apps.shimmer.database.ShimmerDatabase.getInstance(this@ShimmerWallpaperService, wallpaperTarget)
                    .invalidationTracker.createFlow(
                        "sources", "image_records", "source_image_links", "source_directory_exclusions", emitInitialState = false,
                    ).collectLatest {
                        if (wallpaperTarget == WallpaperTarget.LOCK && lockEditorDelegate.isInitialized()) lockEditor.invalidate()
                        if (rendererReady) refreshPlaybackStateFromSnapshot()
                    }
            }
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            if (Build.VERSION.SDK_INT >= 34) super.onWallpaperFlagsChanged(which)
            if (!diagnosticAlive) return
            val permitted = ProductionRoutePolicy.allowed(wallpaperTarget, attachedFlags(), isPreview,
                legacyHome = Build.VERSION.SDK_INT < 34)
            if (!permitted) {
                routeAllowed = false
                if (wallpaperTarget == WallpaperTarget.LOCK) {
                    nativeLockRequest?.let { finishNativeLock(it, "CANCELLED") }
                    LockEditorBridge.unregister(diagnosticId)
                    if (lockEditorDelegate.isInitialized()) lockEditor.invalidate()
                    LockEngineHostRegistry.unregister(diagnosticId, lockHostTarget)
                    lockRelayBinding?.close(); lockRelayBinding = null
                }
                routeRevoked = true
                engineVisible = false
                super.onVisibilityChanged(false)
                rendererReady = false
                loadGeneration++
                selectionIntentToken = Any()
                pendingSelectedImageId = null
                imageLoadingJob?.cancel()
                cancelPlaybackStep(SwitchOutcome.CANCELLED)
                playbackSnapshotRefreshJob?.cancel()
                transformRefreshJob?.cancel()
                refineJob?.cancel()
                catalogObservationJob?.cancel()
                scope.cancel()
                blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
                p2GestureDetector.reset()
                renderer?.markHidden()
                LiveEditBridge.unregister(diagnosticId)
                editSession?.invalidate()
                editSession = null
                PlaybackSelectionBridge.unregister(diagnosticId)
                schedule.unregister(diagnosticId)
                P0Diagnostics.event(diagnosticId, "P10_ROUTE_REVOKED", "target=$wallpaperTarget")
            } else if (!routeRevoked) {
                routeAllowed = true
                initializeRuntime()
            }
            P0Diagnostics.event(diagnosticId, "P10_ROUTE_CHANGE", "target=$wallpaperTarget flags=${observedFlags ?: "UNKNOWN"} preview=$isPreview allowed=$routeAllowed revoked=$routeRevoked")
        }

        private fun syncRendererSettings(key: String? = null) {
            if (!routeAllowed) return
            val r = renderer ?: return
            if (!rendererReady) return

            val syncEverything = key == null
            val staticTestMode = preferences.isP2StaticTestMode()

            if (key == WallpaperPreferences.KEY_GESTURE_SETTINGS) {
                refreshGestureActionCache()
                return
            }

            if (key == WallpaperPreferences.KEY_BLUR_AMOUNT ||
                key == WallpaperPreferences.KEY_P2_STATIC_TEST_MODE
            ) {
                reBlurCurrentImage()
            }

            // P9-STATIC-SHIMMER-01：会话模糊只在引擎创建时按强度取值，之后强度清零也不会让它跟着关。
            // 强度变化时与初始化同口径重新取值，清零即关闭，调高即打开。
            val blurAmountChanged = key == WallpaperPreferences.KEY_BLUR_AMOUNT
            if (blurAmountChanged) sessionBlurEnabled = preferences.getBlurAmount() > 0f

            queueEvent {
                if (syncEverything ||
                    key == WallpaperPreferences.KEY_DIM_AMOUNT ||
                    key == WallpaperPreferences.KEY_DUOTONE_SETTINGS ||
                    key == WallpaperPreferences.KEY_GRAIN_SETTINGS ||
                    key == WallpaperPreferences.KEY_CHROMATIC_ABERRATION_SETTINGS ||
                    key == WallpaperPreferences.KEY_EFFECT_TRANSITION_DURATION ||
                    key == WallpaperPreferences.KEY_P2_STATIC_TEST_MODE
                ) {
                    r.updateSettings(
                        blurAmount = if (staticTestMode) 0f else preferences.getBlurAmount(),
                        dimAmount = if (staticTestMode) 0f else preferences.getDimAmount(),
                        duotone = preferences.getDuotoneSettings().let {
                            if (staticTestMode) it.copy(enabled = false, alwaysOn = false) else it
                        },
                        grain = preferences.getGrainSettings().let {
                            if (staticTestMode) it.copy(enabled = false) else it
                        },
                        chromatic = preferences.getChromaticAberrationSettings().let {
                            if (staticTestMode) it.copy(enabled = false) else it
                        },
                        transitionDuration = if (staticTestMode) 0L
                        else preferences.getEffectTransitionDurationMillis(),
                    )
                }

                if (syncEverything || blurAmountChanged ||
                    key == WallpaperPreferences.KEY_P2_STATIC_TEST_MODE
                ) {
                    applyBlurState(immediate = true)
                }
            }
        }

        private fun applyBlurState(immediate: Boolean) {
            if (!routeAllowed) return
            val r = renderer ?: return
            val blurOnLock = preferences.isBlurOnScreenLockEnabled()
            val isOnLockScreen =
                (getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                    ?.isKeyguardLocked
                    ?: false
            val isScreenOn =
                (getSystemService(POWER_SERVICE) as? android.os.PowerManager)?.isInteractive
                    ?: true

            val shouldForceBlur = !preferences.isP2StaticTestMode() &&
                blurOnLock && (isOnLockScreen || !isScreenOn)
            val effectiveBlur = if (preferences.isP2StaticTestMode()) false else shouldForceBlur || sessionBlurEnabled

            r.enableBlur(effectiveBlur, immediate || (shouldForceBlur && !isScreenOn))

            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            if (!effectiveBlur && preferences.isBlurTimeoutEnabled()) {
                blurTimeoutHandler.postDelayed(
                    blurTimeoutRunnable,
                    preferences.getBlurTimeoutMillis(),
                )
            }
        }

        override fun onRendererReady() {
            if (!routeAllowed) return
            val diagnosticToken = P0Diagnostics.captureToken()
            scope.launch {
                rendererReady = true
                publishDiagnosticState("OWNER_RENDERER_READY", diagnosticToken)
                renderer?.maxTextureSize?.takeIf { it > 0 }?.let { imageLoader.maxTextureSize = it }
                syncRendererSettings(null)

                val cached = cachedImageSet
                if (cached != null) {
                    if (wallpaperTarget == WallpaperTarget.LOCK) lockPresentationToken = cached
                    // A rebuilt surface keeps the placement, including an unsaved edit draft.
                    // The image is already the one on screen, so it is restored without a transition.
                    val placement = editSession?.draft ?: displayedTransform
                    queueEvent { renderer?.setImage(cached, placement, TransitionSpec.NONE) }
                    refreshPlaybackStateFromSnapshot()
                    // Defaults or a saved placement may have changed while the surface was gone.
                    refreshDisplayedTransform()
                    replayParkedDispatch()
                    return@launch
                }

                val items = folderRepository.getPlaybackItems()
                val lastUri = folderRepository.getCurrentImageUri()
                val current = onPlaybackThread {
                    playbackCoordinator.setItems(items)
                    playbackSnapshotStore.load()?.let(playbackCoordinator::restore)
                    if (playbackCoordinator.current() == null && lastUri != null) {
                        playbackCoordinator.restoreCurrent(lastUri.toString())
                    }
                    playbackCoordinator.current()
                }
                if (replaySelectedImage()) return@launch
                if (current != null) {
                    loadImage(Uri.parse(current.id))
                } else if (!isPreview && onPlaybackThread { playbackCoordinator.sourceRestoreNeeded() }) {
                    startPlayback(PlaybackDirection.NEXT, sourceRestoreOnly = true)
                } else {
                    requestPlayback(PlaybackDirection.NEXT)
                }
                replayParkedDispatch()
            }
        }

        /** A scheduled switch that arrived before the surface was ready runs now, once. */
        private fun replayParkedDispatch() {
            val parked = parkedDispatch ?: return
            parkedDispatch = null
            requestPlayback(PlaybackDirection.NEXT, parked)
        }

        private fun reBlurCurrentImage() {
            currentImageUri?.let(::loadImage)
        }

        /** Reloads an Activity-saved order without forcing a new image or rebuilding the Engine. */
        private fun refreshPlaybackStateFromSnapshot() {
            requestPlaybackRefresh(snapshotOnly = false)
        }

        private fun refreshPlaybackSnapshotOnly() {
            requestPlaybackRefresh(snapshotOnly = true)
        }

        private fun requestPlaybackRefresh(snapshotOnly: Boolean) {
            if (!routeAllowed) return
            // Even while held, a later snapshot notification cannot downgrade a real catalog edit.
            if (!snapshotOnly) catalogRefreshDemand.requestFull()
            // Editing keeps the image; the refresh runs once when the editor closes.
            if (!editHold.admitRefresh()) return
            playbackSnapshotRefreshJob?.cancel()
            playbackSnapshotRefreshJob = scope.launch {
                // Saving an order, a catalog change and this engine's own commit arrive as a
                // burst of notifications. Cancelling the previous job collapses them into one
                // refresh instead of repeating the whole catalog reload per notification.
                delay(SNAPSHOT_REFRESH_DEBOUNCE_MILLIS)
                val ticket = catalogRefreshDemand.capture()
                if (!catalogRefreshDemand.requiresRead()) {
                    val sameStored = onPlaybackThread {
                        val stored = playbackSnapshotStore.load() ?: PlaybackSnapshot()
                        stored == playbackCoordinator.snapshot().forPersistence()
                    }
                    if (sameStored && !catalogRefreshDemand.requiresRead()) {
                        syncDisplayedImage()
                        return@launch
                    }
                }
                val items = folderRepository.getPlaybackItems()
                val snapshot = onPlaybackThread {
                    val stored = playbackSnapshotStore.load() ?: PlaybackSnapshot()
                    // Own save notifications must never restore or cancel the request that saved
                    // it. Both sides are compared in persisted form because a derived queue is
                    // rebuilt on load rather than stored.
                    val unchanged = stored == playbackCoordinator.snapshot().forPersistence() &&
                        items == playbackCoordinator.catalogItems()
                    if (unchanged) {
                        null
                    } else {
                        playbackCoordinator.setItems(items)
                        if (!isPreview) playbackCoordinator.restore(stored)
                        stored
                    }
                }
                catalogRefreshDemand.confirm(ticket)
                if (snapshot == null) {
                    syncDisplayedImage()
                    return@launch
                }
                loadGeneration++
                cancelPlaybackStep(SwitchOutcome.CANCELLED)
                imageLoadingJob?.cancel()
                val cursor = onPlaybackThread { playbackCoordinator.snapshot().historyCursor }
                P0Diagnostics.event(diagnosticId, "P2_REFRESH", "preview=$isPreview items=${items.size} order=${snapshot.order} cursor=$cursor")
                Log.d(
                    TAG,
                    "P2 state refreshed; order=${snapshot.order}, queue=${items.size}, cursor=$cursor",
                )
                syncDisplayedImage()
            }
        }

        /** Runs coordinator and snapshot work off the main thread, one caller at a time. */
        private suspend fun <T> onPlaybackThread(block: suspend () -> T): T =
            playbackMutex.withLock { withContext(Dispatchers.Default) { block() } }

        private suspend fun syncDisplayedImage() {
            if (!routeAllowed || !engineVisible || !rendererReady) return
            // own-save 通知可能在 Default 已提交、Main 尚未接管之间到达。
            // 当前切图 job 仍负责接管时，不另发 loadImage 抢 generation、吞掉待重放手势。
            // 真目录／快照变更的刷新已先取消旧 job，因此仍能走下面的同步／来源恢复。
            if (imageCycleJob?.isActive == true) return
            if (replaySelectedImage()) return
            val current = onPlaybackThread { playbackCoordinator.current() }
            if (current == null) {
                if (!isPreview && onPlaybackThread { playbackCoordinator.sourceRestoreNeeded() }) {
                    // 只恢复该根来源自己的真实位置，不把刷新或重新可见当作下一张。
                    startPlayback(PlaybackDirection.NEXT, sourceRestoreOnly = true)
                } else if (cachedImageSet == null) requestPlayback(PlaybackDirection.NEXT)
            } else if (current.id != currentImageUri?.toString()) {
                loadImage(Uri.parse(current.id))
            }
        }

        override fun onReadyForNextImage() {
            scope.launch {
                if (replaySelectedImage()) return@launch
                val direction = pendingPlaybackDirection ?: return@launch
                val dispatch = pendingPlaybackDispatch
                val lockRequest = pendingNativeLockRequest
                pendingPlaybackDirection = null
                pendingPlaybackDispatch = null
                pendingNativeLockRequest = null
                if (lockRequest != null && nativeLockRequest !== lockRequest) return@launch
                startPlayback(direction, dispatch, lockRequest = lockRequest)
            }
        }

        private fun loadImage(uri: Uri) {
            if (!routeAllowed) return
            imageLoadingJob?.cancel()
            val generation = ++loadGeneration
            imageLoadingJob = scope.launch {
                // Restore, sync, reblur and direct set reach an image without a playback step.
                val defaults = preferences.getP3Defaults()
                val resolved = resolveDisplayPlacement(uri.toString())
                val adaptFit = TransformResolution.smartFitApplies(resolved, defaults)
                val newSet = decodeImage(uri, resolved.transform, adaptFit)

                if (isActive && engineVisible && rendererReady && generation == loadGeneration && newSet != null) {
                    val adapted = TransformResolution.adaptFit(
                        resolved, defaults, newSet.sourceWidth, newSet.sourceHeight,
                        surfaceDimensions.width, surfaceDimensions.height
                    )
                    updateActiveImageSet(newSet, uri, adapted.transform)
                } else {
                    newSet?.recycleAll()
                    if (isActive && engineVisible && rendererReady && generation == loadGeneration && newSet == null) {
                        requestPlayback(PlaybackDirection.NEXT)
                    }
                }
            }
        }

        /**
         * Releases a decode result if cancellation occurs while switching back from IO. The
         * sample size follows [transform], and [decodeGate] keeps this Engine to one decode.
         */
        private suspend fun decodeImage(uri: Uri?, transform: ImageTransform?, adaptFit: Boolean = false): ImageSet? {
            var owned: ImageSet? = null
            try {
                val blur = if (preferences.isP2StaticTestMode()) 0f else preferences.getBlurAmount()
                decodeGate.run {
                    withContext(Dispatchers.IO) {
                        owned = if (uri == null) {
                            imageLoader.loadDefault(blur, transform, adaptFit)
                        } else {
                            imageLoader.loadFromUri(uri, blur, transform, adaptFit)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                return owned
            } catch (failed: Throwable) {
                owned?.recycleAll()
                throw failed
            }
        }

        private fun loadDefaultImage() {
            if (!routeAllowed) return
            // A failed/empty catalog must never replace an already displayed user's image.
            if (cachedImageSet != null) return
            imageLoadingJob?.cancel()
            val generation = ++loadGeneration
            imageLoadingJob = scope.launch {
                val defaults = preferences.getP3Defaults()
                val resolved = resolveDisplayPlacement(ImageLoader.DEFAULT_IMAGE_ID)
                val adaptFit = TransformResolution.smartFitApplies(resolved, defaults)
                val newSet = decodeImage(null, resolved.transform, adaptFit)
                if (isActive && engineVisible && rendererReady && generation == loadGeneration && newSet != null) {
                    val adapted = TransformResolution.adaptFit(
                        resolved, defaults, newSet.sourceWidth, newSet.sourceHeight,
                        surfaceDimensions.width, surfaceDimensions.height
                    )
                    updateActiveImageSet(newSet, null, adapted.transform)
                } else {
                    newSet?.recycleAll()
                }
            }
        }

        /** [direction] is the playback step that chose [newSet]; null for reloads of the same source. */
        private fun updateActiveImageSet(
            newSet: ImageSet, uri: Uri?, transform: ImageTransform, direction: PlaybackDirection? = null,
            onOwnershipAcquired: (() -> Unit)? = null,
        ) {
            if (!routeAllowed) {
                // Take ownership before disposing so the caller's handoff cannot recycle twice.
                onOwnershipAcquired?.invoke()
                newSet.recycleAll()
                return
            }
            val oldSet = cachedImageSet
            cachedImageSet = newSet
            if (!isPreview) dev.abdus.apps.shimmer.media.MediaPlaybackStatus.record(wallpaperTarget,
                dev.abdus.apps.shimmer.media.MediaPlaybackStatus.Entry(
                    newSet.mediaClip?.kind ?: dev.abdus.apps.shimmer.media.MediaKind.IMAGE,
                    newSet.mediaClip?.let { dev.abdus.apps.shimmer.media.MediaFrameState.PREPARING }))
            if (wallpaperTarget == WallpaperTarget.LOCK) lockPresentationToken = newSet
            // From this exact point the Engine owns the image, even if later setup fails.
            onOwnershipAcquired?.invoke()
            currentImageUri = uri
            if (wallpaperTarget == WallpaperTarget.LOCK && !isPreview) {
                lockImageTicket = LockProtocol.sceneTicket(lockInputSceneId, UUID.randomUUID().toString())
                LockEngineHostRegistry.refresh()
            }
            displayedTransform = transform
            // A finer decode of the previous image is no longer wanted.
            refineJob?.cancel()
            val spec = transitionPlanner.plan(
                settings = transitionStore.load(),
                direction = direction,
                sameImage = oldSet?.id == newSet.id,
                staticMode = preferences.isP2StaticTestMode(),
            )

            queueEvent {
                renderer?.setImage(newSet, transform, spec)
                scope.launch(Dispatchers.Default) {
                    oldSet?.recycleAll()
                }
            }
        }

        private fun requestImageCycle() = requestPlayback(PlaybackDirection.NEXT)

        /** [dispatch] is set for a P4 scheduled switch, whose outcome is reported exactly once. */
        private fun requestPlayback(direction: PlaybackDirection, dispatch: ScheduleDispatch? = null) {
            if (!routeAllowed) {
                nativeLockRequest?.let { finishNativeLock(it, "CANCELLED") }
                dispatch?.let { reportSchedule(it, SwitchOutcome.DROPPED, "系统目标不匹配") }
                return
            }
            selectionIntentToken = Any()
            pendingSelectedImageId = null
            // Start at the business request, not when Main eventually starts its coroutine.
            val timing = PlaybackStepTiming(SystemClock::elapsedRealtimeNanos)
            val work = PlaybackWorkEvidence(PlaybackWorkScope.NORMAL)
            scope.launch { startPlayback(direction, dispatch, timing, work = work) }.invokeOnCompletion { failure ->
                if (failure != null) reportPlaybackTiming(timing, direction, dispatch, work,
                    if (failure is CancellationException) PlaybackTimingResult.CANCELLED else PlaybackTimingResult.ERROR)
            }
        }

        /** Only fixed labels and counts go into the existing private, bounded diagnostic ring. */
        private fun reportPlaybackTiming(
            timing: PlaybackStepTiming, direction: PlaybackDirection, dispatch: ScheduleDispatch?, work: PlaybackWorkEvidence, result: PlaybackTimingResult,
        ) {
            val measurement = timing.finish(result) ?: return
            work.finish()?.let { report ->
                P0Diagnostics.event(diagnosticId, "P9_PLAYBACK_WORK", "result=$result preview=$isPreview ${report.diagnosticFields()}")
            }
            if (measurement.selected) {
                P0Diagnostics.event(diagnosticId, "P9_SELECTED_TIMING",
                    "trace=${work.traceId} preview=$isPreview direction=$direction scheduled=${dispatch != null} ${measurement.diagnosticFields()}")
            } else {
                P0Diagnostics.event(diagnosticId, "P9_PLAYBACK_TIMING",
                    "trace=${work.traceId} preview=$isPreview direction=$direction scheduled=${dispatch != null} ${measurement.diagnosticFields()}")
            }
            measurement.catalogParts?.let { parts ->
                if (measurement.selected) {
                    P0Diagnostics.event(diagnosticId, "P9_SELECTED_CATALOG_TIMING",
                        "trace=${work.traceId} preview=$isPreview direction=$direction scheduled=${dispatch != null} result=$result " +
                            "items=${measurement.catalogItems} order=${measurement.order ?: "UNKNOWN"} ${parts.diagnosticFields()}")
                } else {
                    P0Diagnostics.event(diagnosticId, "P9_CATALOG_TIMING",
                        "trace=${work.traceId} preview=$isPreview direction=$direction scheduled=${dispatch != null} result=$result " +
                            "items=${measurement.catalogItems} order=${measurement.order ?: "UNKNOWN"} ${parts.diagnosticFields()}")
                }
            }
        }

        private fun startPlayback(
            direction: PlaybackDirection, dispatch: ScheduleDispatch? = null,
            timing: PlaybackStepTiming = PlaybackStepTiming(SystemClock::elapsedRealtimeNanos),
            sourceRestoreOnly: Boolean = false,
            selectedImageId: String? = null,
            work: PlaybackWorkEvidence = PlaybackWorkEvidence(if (selectedImageId == null) PlaybackWorkScope.NORMAL else PlaybackWorkScope.SELECTED),
            lockRequest: NativeLockRequest? = null,
        ) {
            if (lockRequest == null) nativeLockRequest?.let { finishNativeLock(it, "CANCELLED") }
            if (selectedImageId != null) timing.markSelected()
            if (!routeAllowed || (lockRequest != null && !lockRequestAuthorization(lockRequest.id))) {
                lockRequest?.let { finishNativeLock(it, "CANCELLED") }
                dispatch?.let { reportSchedule(it, SwitchOutcome.DROPPED, "系统目标不匹配") }
                reportPlaybackTiming(timing, direction, dispatch, work, PlaybackTimingResult.NOT_READY)
                return
            }
            if (selectedImageId != null && editHold.holding) {
                reportPlaybackTiming(timing, direction, dispatch, work, PlaybackTimingResult.DROPPED_EDIT)
                return
            }
            // 编辑持有期间保留画面；自动恢复只记待刷新，不占用一个虚构的 NEXT 动作。
            if (sourceRestoreOnly && !editHold.admitRefresh()) {
                reportPlaybackTiming(timing, direction, dispatch, work, PlaybackTimingResult.DROPPED_EDIT)
                return
            }
            // An interval that comes due while editing is dropped; the interval restarts after.
            if (dispatch != null && dispatch.intervalOnly && !editHold.admitTimerTick()) {
                reportSchedule(dispatch, SwitchOutcome.DROPPED_EDIT)
                reportPlaybackTiming(timing, direction, dispatch, work, PlaybackTimingResult.DROPPED_EDIT)
                return
            }
            // Every switch path ends here; editing defers it and keeps only the newest.
            val deferred = editHold.deferredSwitch
            if (!editHold.admitSwitch(HeldAction.Step(direction, dispatch))) {
                supersedeDeferred(deferred, dispatch)
                if (dispatch != null) reportSchedule(dispatch, SwitchOutcome.HELD_EDIT)
                reportPlaybackTiming(timing, direction, dispatch, work, PlaybackTimingResult.HELD_EDIT)
                return
            }
            if (!engineVisible || !rendererReady) {
                lockRequest?.let { finishNativeLock(it, "CANCELLED") }
                if (dispatch != null) reportSchedule(dispatch, SwitchOutcome.DROPPED, "壁纸不可见或渲染未就绪")
                reportPlaybackTiming(timing, direction, dispatch, work, PlaybackTimingResult.NOT_READY)
                return
            }
            if (!sourceRestoreOnly && renderer?.isAnimating() == true) {
                if (selectedImageId != null) {
                    pendingSelectedImageId = selectedImageId
                    reportPlaybackTiming(timing, direction, dispatch, work, PlaybackTimingResult.DEFERRED_TRANSITION)
                    return
                }
                // The newest request waits for the transition; a scheduled one it replaces is over.
                pendingPlaybackDispatch?.takeIf { it !== dispatch }?.let { reportSchedule(it, SwitchOutcome.SUPERSEDED) }
                pendingPlaybackDirection = direction
                pendingPlaybackDispatch = dispatch
                pendingNativeLockRequest = lockRequest
                // This is deferred, not a presented image. Replay owns a new business attempt.
                reportPlaybackTiming(timing, direction, dispatch, work, PlaybackTimingResult.DEFERRED_TRANSITION)
                return
            }

            // A direct restore/reblur load must not race a newer playback request.
            imageLoadingJob?.cancel()
            cancelPlaybackStep(SwitchOutcome.SUPERSEDED)
            activeDispatch = dispatch
            val generation = ++loadGeneration
            lockRequest?.generation = generation
            // Inheritance copies what is on screen now, and defaults are read once per step.
            val previousDisplayed = displayedTransform
            val viewport = surfaceDimensions
            val defaults = preferences.getP3Defaults()
            val selectedToken = selectionIntentToken
            val selectedAttempt = Any()
            activeSelectedImageId = selectedImageId
            activeSelectedToken = if (selectedImageId == null) null else selectedToken
            activeSelectedAttempt = if (selectedImageId == null) null else selectedAttempt
            imageCycleJob = scope.launch {
                val handoff = PlaybackImageHandoff<ImageSet> { it.recycleAll() }
                var timingResult = PlaybackTimingResult.CANCELLED
                var restoreFollowUp: PlaybackDirection? = null
                // Only while this job still owns the dispatch; a newer step may have settled it.
                fun settle(outcome: SwitchOutcome, detail: String = "") {
                    if (dispatch != null && activeDispatch === dispatch) reportSchedule(dispatch, outcome, detail)
                }
                try {
                    val catalog = timing.measure(PlaybackTimingStage.CATALOG) { folderRepository.getPlaybackCatalog(timing.catalogTiming) }
                    val items = catalog.items
                    timing.catalog(items.size)
                    if (dispatch != null) {
                        // Rule changes and a fixed single image do not force a switch.
                        ScheduledSwitchPolicy.decide(dispatch, items.map { it.id }, currentImageUri?.toString())?.let { skip ->
                            settle(skip, "目录 ${items.size} 张")
                            timingResult = PlaybackTimingResult.POLICY_SKIP
                            return@launch
                        }
                    }
                    // One critical section covers load, restore, resolve, commit and save, so a
                    // concurrent refresh can never observe a half-applied catalog or order.
                    val outcome = onPlaybackThread {
                        val (expected, before) = timing.measure(PlaybackTimingStage.STATE_PREPARATION) {
                            P0Diagnostics.event(diagnosticId, "P2_REQUEST", "preview=$isPreview direction=$direction cursor=${playbackCoordinator.snapshot().historyCursor}")
                            val stored = timing.measureState(PlaybackStateTimingStage.SNAPSHOT_READ) { playbackSnapshotStore.load() }
                            if (isPreview) {
                                timing.measureState(PlaybackStateTimingStage.CATALOG_APPLICATION) { playbackCoordinator.setItems(items) }
                            } else {
                                timing.measureCatalogAndRestore { catalogApplied ->
                                    playbackCoordinator.setItemsAndRestore(items, stored ?: PlaybackSnapshot(), catalogApplied).also(work::preparation)
                                }
                            }
                            stored to playbackCoordinator.snapshot().also { timing.order(it.order) }
                        }
                        val restoring = selectedImageId == null && !isPreview && playbackCoordinator.sourceRestoreNeeded()
                        // 自动刷新已安排恢复但目录又变了：不得意外变成一个普通 NEXT。
                        if (sourceRestoreOnly && !restoring) return@onPlaybackThread PlaybackOutcome.Dropped
                        val resolver = PlaybackImageResolver<Pair<ImageSet, ResolvedTransform>>(playbackCoordinator)
                        val loader: suspend (dev.abdus.apps.shimmer.p2.PlaybackRequest, dev.abdus.apps.shimmer.p2.PlaybackItem) -> Pair<ImageSet, ResolvedTransform>? = { request, item ->
                                // Resolved before decoding: the placement decides the sample size.
                                val placement = TransformResolution.resolve(
                                    freshAdvance = request.freshAdvance && selectedImageId == null,
                                    entryTransform = request.entryTransform,
                                    saved = playbackSnapshotStore.loadImageTransform(item.id),
                                    defaults = defaults,
                                    previousDisplayed = previousDisplayed,
                                )
                                val adaptFit = TransformResolution.smartFitApplies(placement, defaults)
                                timing.measure(PlaybackTimingStage.IMAGE_LOAD) {
                                    decodeImage(Uri.parse(item.id), placement.transform, adaptFit)
                                }.also {
                                    if (it == null) {
                                        Log.w(TAG, "P2 skipped unreadable image; direction=$direction")
                                        P0Diagnostics.event(diagnosticId, "P2_SKIP", "direction=$direction")
                                    }
                                }?.let { imageSet ->
                                    val fitted = TransformResolution.fitInherited(placement, imageSet.sourceWidth, imageSet.sourceHeight, viewport.width, viewport.height)
                                    val adapted = TransformResolution.adaptFit(fitted, defaults, imageSet.sourceWidth, imageSet.sourceHeight, viewport.width, viewport.height)
                                    imageSet to adapted
                                }
                            }
                        val loaded = if (selectedImageId != null) resolver.resolveSelected(selectedImageId, loader)
                            else if (restoring) resolver.resolveSourceRestore(loader)
                            else resolver.resolveRequest(direction, loader)
                        if (loaded == null) {
                            Log.d(TAG, "P2 no readable candidate; keeping image and committed history")
                            P0Diagnostics.event(diagnosticId, "P2_KEEP", "items=${items.size} cached=${cachedImageSet != null}")
                            return@onPlaybackThread PlaybackOutcome.NoCandidate
                        }
                        timing.measure(PlaybackTimingStage.COMMIT) {
                            val request = loaded.request
                            val (newSet, placement) = loaded.image
                            handoff.capture(newSet)
                            // Only an inherited placement is recorded on the history entry.
                            val annotation = placement.transform.takeIf { placement.annotateHistory }
                            val stepTask = currentCoroutineContext()[Job]
                            if (!currentCoroutineContext().isActive || !routeAllowed || !engineVisible || !rendererReady ||
                                (lockRequest != null && !lockRequestAuthorization(lockRequest.id)) ||
                                generation != loadGeneration || !playbackCoordinator.commit(request.version, annotation)) {
                                handoff.close()
                                playbackCoordinator.reject(request.version)
                                return@measure PlaybackOutcome.Dropped
                            }
                            val storedCommit = if (isPreview) null else playbackSnapshotStore.compareAndSaveIfAllowedWithWork(expected, playbackCoordinator.snapshot(),
                                { write -> if (lockRequest == null) write()
                                    else LockCommandHostService.withRequestAuthorization(lockRequest.id, write)
                                        ?: dev.abdus.apps.shimmer.p2.StoreCommitResult(false) }) {
                                routeAllowed && engineVisible && rendererReady && generation == loadGeneration &&
                                    stepTask?.isActive == true && scope.isActive &&
                                    (lockRequest == null || lockRequestAuthorization(lockRequest.id))
                            }
                            storedCommit?.let { work.storage(it.work) }
                            if (storedCommit?.accepted == false) {
                                handoff.close()
                                playbackCoordinator.restore(playbackSnapshotStore.load() ?: before)
                                P0Diagnostics.event(diagnosticId, "P2_STALE", "version=${request.version}")
                                return@measure PlaybackOutcome.Stale
                            }
                            P0Diagnostics.event(diagnosticId, "P2_COMMIT", "preview=$isPreview version=${request.version} cursor=${playbackCoordinator.snapshot().historyCursor} history=${playbackCoordinator.snapshot().historyIds.size}")
                            PlaybackOutcome.Committed(newSet, Uri.parse(request.item!!.id), placement.transform, restoring)
                        }
                    }
                    // Renderer and image-set ownership stays on the main thread.
                    when (outcome) {
                        PlaybackOutcome.NoCandidate -> {
                            lockRequest?.let { finishNativeLock(it, "FAILED") }
                            settle(SwitchOutcome.NO_CANDIDATE, "目录 ${items.size} 张")
                            timingResult = PlaybackTimingResult.NO_CANDIDATE
                            loadDefaultImage()
                        }
                        PlaybackOutcome.Dropped -> {
                            lockRequest?.let { finishNativeLock(it, "CANCELLED") }
                            settle(SwitchOutcome.DROPPED)
                            timingResult = PlaybackTimingResult.DROPPED
                        }
                        PlaybackOutcome.Stale -> {
                            lockRequest?.let { finishNativeLock(it, "STALE_TICKET") }
                            settle(SwitchOutcome.STALE)
                            timingResult = PlaybackTimingResult.STALE
                            refreshPlaybackStateFromSnapshot()
                        }
                        is PlaybackOutcome.Committed -> {
                            if (lockRequest != null && !lockRequestAuthorization(lockRequest.id)) {
                                finishNativeLock(lockRequest, "CANCELLED")
                                timingResult = PlaybackTimingResult.CANCELLED
                                return@launch
                            }
                            timing.measure(PlaybackTimingStage.COMMIT) {
                                updateActiveImageSet(outcome.image, outcome.uri, outcome.transform,
                                    direction.takeUnless { outcome.sourceRestored || selectedImageId != null }) {
                                    handoff.transfer(outcome.image)
                                }
                                if (nativeLockRequest === lockRequest) lockRequest?.let {
                                    it.expectedImage = outcome.image.id
                                    it.expectedToken = outcome.image
                                }
                                timingResult = PlaybackTimingResult.COMMITTED
                                // Reported before the next suspension, so a late cancel cannot undo it.
                                if (dispatch != null && !outcome.sourceRestored) {
                                    if (activeDispatch === dispatch) activeDispatch = null
                                    P0Diagnostics.event(diagnosticId, "P4_COMMIT", "seq=${dispatch.seq} items=${items.size}")
                                    schedule.onCommitted(diagnosticId, dispatch.seq, items.size)
                                } else if (!isPreview && !outcome.sourceRestored) {
                                    schedule.onManualCommit(diagnosticId, "手动切换（${if (direction == PlaybackDirection.NEXT) "下一张" else "上一张"}）")
                                }
                                if (!isPreview) folderRepository.commitImageShown(outcome.uri)
                                if (outcome.sourceRestored) {
                                    P0Diagnostics.event(diagnosticId, "P9_SOURCE_RESTORE",
                                        "automatic=$sourceRestoreOnly items=${items.size}")
                                }
                                restoreFollowUp = sourceRestoreFollowUp(direction, outcome.sourceRestored,
                                    sourceRestoreOnly, generation, loadGeneration, isActive, engineVisible, rendererReady)
                            }
                            timingResult = PlaybackTimingResult.COMMITTED
                        }
                    }
                } catch (cancelled: CancellationException) {
                    timingResult = if (timingResult == PlaybackTimingResult.COMMITTED)
                        PlaybackTimingResult.COMMITTED_SAVE_CANCELLED else PlaybackTimingResult.CANCELLED
                    throw cancelled
                } catch (failure: Throwable) {
                    timingResult = if (timingResult == PlaybackTimingResult.COMMITTED)
                        PlaybackTimingResult.COMMITTED_SAVE_ERROR else PlaybackTimingResult.ERROR
                    throw failure
                } finally {
                    if (lockRequest != null && lockRequest.expectedImage == null) finishNativeLock(lockRequest, "CANCELLED")
                    // A visibility/context interruption keeps only this still-latest memory intent.
                    // It is never retried after a newer gesture, selection, schedule or editor action.
                    if (selectedImageId != null && timingResult == PlaybackTimingResult.CANCELLED &&
                        selectedToken === selectionIntentToken && (!engineVisible || !rendererReady) &&
                        !editHold.holding) {
                        pendingSelectedImageId = selectedImageId
                    }
                    if (activeSelectedAttempt === selectedAttempt) {
                        activeSelectedImageId = null
                        activeSelectedToken = null
                        activeSelectedAttempt = null
                    }
                    handoff.close()
                    // Cancelled by visibility, a newer step, the editor or a snapshot refresh.
                    if (restoreFollowUp == null) settle(SwitchOutcome.CANCELLED)
                    reportPlaybackTiming(timing, direction, dispatch, work, timingResult)
                }
                restoreFollowUp?.let { nextDirection ->
                    // 接管完成且没有新请求抢先；结束当前 job 的归属，避免重放时取消自己。
                    if (imageCycleJob === currentCoroutineContext()[Job]) imageCycleJob = null
                    if (activeDispatch === dispatch) activeDispatch = null
                    startPlayback(nextDirection, dispatch)
                }
            }
            // A scope can be cancelled before this launch executes any body/finally at all.
            imageCycleJob?.invokeOnCompletion { failure ->
                if (failure != null) reportPlaybackTiming(timing, direction, dispatch, work,
                    if (failure is CancellationException) PlaybackTimingResult.CANCELLED else PlaybackTimingResult.ERROR)
                if (wallpaperTarget == WallpaperTarget.LOCK) scope.launch { LockEngineHostRegistry.refresh() }
            }
        }

        /** Stops the running playback step; a scheduled switch in it is reported as [outcome]. */
        private fun cancelPlaybackStep(outcome: SwitchOutcome) {
            activeDispatch?.let { reportSchedule(it, outcome) }
            imageCycleJob?.cancel()
        }

        /** Pending UI intent is memory-only; decode/commit use the normal freshest guarded path. */
        private fun replaySelectedImage(): Boolean {
            val selected = pendingSelectedImageId ?: return false
            if (isPreview || !engineVisible || !rendererReady || editHold.holding || renderer?.isAnimating() == true) return false
            pendingSelectedImageId = null
            startPlayback(PlaybackDirection.NEXT, selectedImageId = selected)
            P0Diagnostics.event(diagnosticId, "P9_SELECT_REQUEST", "visible=$engineVisible ready=$rendererReady")
            return true
        }

        /** Capture interruption at the lifecycle boundary, not after a blocked decoder cleans up. */
        private fun rememberSelectedImageForLifecycle() {
            val selected = activeSelectedImageId ?: return
            if (activeSelectedToken === selectionIntentToken && !editHold.holding) pendingSelectedImageId = selected
        }

        /** A deferred scheduled switch replaced by a newer one while editing never runs. */
        private fun supersedeDeferred(previous: HeldAction?, next: ScheduleDispatch?) {
            val old = (previous as? HeldAction.Step)?.dispatch ?: return
            if (old !== next) reportSchedule(old, SwitchOutcome.SUPERSEDED, "编辑中被更新的切换取代")
        }

        /** Reports a non-commit result of [dispatch] and forgets it wherever it waits. */
        private fun reportSchedule(dispatch: ScheduleDispatch, outcome: SwitchOutcome, detail: String = "") {
            if (activeDispatch === dispatch) activeDispatch = null
            if (pendingPlaybackDispatch === dispatch) pendingPlaybackDispatch = null
            if (parkedDispatch === dispatch) parkedDispatch = null
            P0Diagnostics.event(diagnosticId, "P4_OUTCOME", "seq=${dispatch.seq} outcome=$outcome $detail".trim())
            schedule.onOutcome(diagnosticId, dispatch.seq, outcome, detail)
        }

        // ---- P4 schedule target ------------------------------------------------------------------

        override fun onScheduledSwitch(dispatch: ScheduleDispatch) {
            if (!routeAllowed) {
                reportSchedule(dispatch, SwitchOutcome.DROPPED, "系统目标不匹配")
                return
            }
            selectionIntentToken = Any()
            pendingSelectedImageId = null
            P0Diagnostics.event(diagnosticId, "P4_DISPATCH", "seq=${dispatch.seq} reasons=${dispatch.reasons.sorted()}")
            if (!rendererReady) {
                // The surface is being rebuilt; run once it is ready.
                parkedDispatch?.takeIf { it !== dispatch }?.let { reportSchedule(it, SwitchOutcome.SUPERSEDED) }
                parkedDispatch = dispatch
                return
            }
            startPlayback(PlaybackDirection.NEXT, dispatch)
        }

        override fun onScheduleCatalogChanged() {
            if (rendererReady) refreshPlaybackStateFromSnapshot()
        }

        override fun onTouchEvent(event: MotionEvent) {
            // Observe the true entry before the existing hold branch. No MotionEvent escapes.
            val touch = P0Diagnostics.beginTouch(diagnosticId, event, editHold.holding)
            if (!routeAllowed) {
                P0Diagnostics.touchIgnored(touch, "TARGET_REJECTED")
                super.onTouchEvent(event)
                return
            }
            // InputDispatcher可能在SystemUI已接管的同时仍复制事件至壁纸。
            // 正式LOCK动作只能来自已鉴别命令；不能让裸事件再走第二识别器，
            // 更不能跳过SystemUI的认证／通知／指纹区域保护。HOME与预览保持原路径。
            if (wallpaperTarget == WallpaperTarget.LOCK && !isPreview) {
                p2GestureDetector.reset()
                P0Diagnostics.touchIgnored(touch, "LOCK_AUTHENTICATED_INPUT_ONLY")
                super.onTouchEvent(event)
                return
            }
            if (editHold.holding) {
                // The editor's own touches can reach the wallpaper through its window; they
                // are neither taps nor ripples, and skipping them keeps Main free for the drag.
                P0Diagnostics.touchIgnored(touch, "EDIT_HOLD")
                super.onTouchEvent(event)
                return
            }
            touchEffectController.onTouchEvent(
                event,
                surfaceDimensions.width,
                surfaceDimensions.height,
            )

            p2GestureDetector.onTouchEvent(event)
            P0Diagnostics.touchForwarded(touch, "P2_GESTURE")

            if (!preferences.isP2StaticTestMode() && preferences.isBlurTimeoutEnabled()) {
                blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
                if (!sessionBlurEnabled) {
                    blurTimeoutHandler.postDelayed(
                        blurTimeoutRunnable,
                        preferences.getBlurTimeoutMillis(),
                    )
                }
            }

            super.onTouchEvent(event)
        }

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean,
        ): Bundle? {
            // Commands are observations, not gesture substitutes. Never inspect private extras.
            P0Diagnostics.command(diagnosticId, action, x, y, z, resultRequested)
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun refreshGestureActionCache() {
            gestureActionMap = preferences.getGestureSettings().toMap()
        }

        private fun handleGesture(gesture: TapGesture) {
            if (isPreview || !routeAllowed) return
            val action = gestureActionMap[gesture] ?: GestureAction.NONE

            when (action) {
                GestureAction.NEXT_IMAGE -> requestImageCycle()
                GestureAction.TOGGLE_BLUR -> {
                    sessionBlurEnabled = !sessionBlurEnabled
                    applyBlurState(immediate = false)
                }

                GestureAction.RANDOM_DUOTONE -> applyNextDuotone()
                GestureAction.ADD_TO_FAVORITES -> addCurrentImageToFavorites()
                GestureAction.NONE -> {}
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (!visible) renderer?.setMediaPlaybackAllowed(false)
            sampleMediaKeyguard()
            renderer?.setMediaPlaybackAllowed(visible && routeAllowed)
            super.onVisibilityChanged(visible && routeAllowed)
            engineVisible = visible && routeAllowed
            if (!visible) nativeLockRequest?.let { finishNativeLock(it, "CANCELLED") }
            if (wallpaperTarget == WallpaperTarget.LOCK) LockEngineHostRegistry.refresh()
            diagnosticVisible = visible
            publishDiagnosticState("OWNER_VISIBILITY")
            P0Diagnostics.event(diagnosticId, "P2_VISIBILITY", "visible=$visible")
            if (!routeAllowed) {
                renderer?.markHidden()
                return
            }
            // Visibility first, so a switch cancelled below waits for the next visible moment.
            if (!isPreview) schedule.onVisibility(diagnosticId, visible)
            if (!visible) {
                rememberSelectedImageForLifecycle()
                renderer?.markHidden()
                loadGeneration++
                cancelPlaybackStep(SwitchOutcome.CANCELLED)
                imageLoadingJob?.cancel()
                pendingPlaybackDirection = null
                pendingPlaybackDispatch?.let { reportSchedule(it, SwitchOutcome.CANCELLED) }
                parkedDispatch?.let { reportSchedule(it, SwitchOutcome.CANCELLED) }
            }

            if (visible) {
                if (!isPreview) {
                    LiveEditBridge.markVisible(diagnosticId)
                    PlaybackSelectionBridge.markVisible(diagnosticId)
                }
                applyBlurState(immediate = false)
                queueEvent { renderer?.onVisibilityChanged() }
                if (rendererReady) {
                    refreshPlaybackStateFromSnapshot()
                }
            }
        }

        override fun onOffsetsChanged(x: Float, y: Float, xs: Float, ys: Float, xp: Int, yp: Int) {
            // P3 placements are exact and ignore parallax; skip the redundant redraw.
            if (displayedTransform == null) renderer?.setParallaxOffset(x)
        }

        override fun onSurfaceDimensionsChanged(width: Int, height: Int) {
            surfaceDimensions = SurfaceDimensions(width, height)
            imageLoader.setScreenHeight(height)
            imageLoader.setViewportSize(width, height)
        }

        private val shortcutReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Actions.ACTION_NEXT_IMAGE -> requestImageCycle()
                        Actions.ACTION_NEXT_DUOTONE -> applyNextDuotone()
                        Actions.ACTION_REFRESH_FOLDERS -> scope.launch { folderRepository.refreshEnabledFolders() }
                        Actions.ACTION_ADD_TO_FAVORITES -> addCurrentImageToFavorites()
                        Actions.ACTION_SET_BLUR_PERCENT -> {
                            intent.let {
                                Actions.BlurPercentAction.fromIntent(it)?.let { action ->
                                    preferences.setBlurAmount(action.percent)
                                }
                            }
                        }

                        Actions.ACTION_ENABLE_BLUR -> {
                            sessionBlurEnabled = true
                            applyBlurState(false)
                        }

                        Actions.ACTION_SET_IMAGE -> {
                            val uri = intent.getParcelableExtra<Uri>(Actions.EXTRA_IMAGE_URI)
                            if (uri != null) showImageDirect(uri)
                        }
                    }
                }
            }

        private fun showImageDirect(uri: Uri) {
            if (!routeAllowed || wallpaperTarget != WallpaperTarget.HOME) return
            selectionIntentToken = Any()
            pendingSelectedImageId = null
            val deferred = editHold.deferredSwitch
            if (!editHold.admitSwitch(HeldAction.SetImage(uri.toString()))) {
                supersedeDeferred(deferred, null)
                return
            }
            // A direct set wins over a scheduled step in flight (its commit would be dropped).
            activeDispatch?.let { reportSchedule(it, SwitchOutcome.SUPERSEDED, "直接设图") }
            pendingPlaybackDispatch?.let {
                pendingPlaybackDirection = null
                reportSchedule(it, SwitchOutcome.SUPERSEDED, "直接设图")
            }
            scope.launch {
                val items = folderRepository.getPlaybackItems()
                onPlaybackThread {
                    currentCoroutineContext().ensureActive()
                    if (!routeAllowed) return@onPlaybackThread
                    playbackCoordinator.setItems(items)
                    playbackCoordinator.restoreCurrent(uri.toString())
                    if (!isPreview) playbackSnapshotStore.save(playbackCoordinator.snapshot())
                }
                if (!isPreview) {
                    folderRepository.commitImageShown(uri)
                    schedule.onManualCommit(diagnosticId, "直接设图")
                }
                loadImage(uri)
            }
        }

        private val screenUnlockReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) renderer?.setMediaPlaybackAllowed(false)
                    if (intent.action == Intent.ACTION_USER_PRESENT) sampleMediaKeyguard(false)
                    else if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) sampleMediaKeyguard()
                    if (intent.action == Intent.ACTION_USER_PRESENT) {
                        if (preferences.isBlurOnScreenLockEnabled() &&
                            !preferences.isUnblurOnUnlockEnabled()
                        ) {
                            sessionBlurEnabled = true
                        }

                        // Unlock switching belongs to the P4 schedule.
                        applyBlurState(immediate = false)
                    }
                }
            }

        private fun sampleMediaKeyguard(knownLocked: Boolean? = null) {
            if (isPreview || !routeAllowed) return
            val locked = knownLocked ?: getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked ?: return
            renderer?.observeMediaKeyguard(locked)
        }

        private fun applyNextDuotone() {
            val nextIndex = (preferences.getDuotonePresetIndex() + 1) % DUOTONE_PRESETS.size
            val p = DUOTONE_PRESETS[nextIndex]
            preferences.applyDuotonePreset(p.lightColor, p.darkColor, true, nextIndex)
        }

        private fun addCurrentImageToFavorites() {
            if (currentImageUri == null) return

            scope.launch {
                val result = favoritesRepository.saveFavorite(currentImageUri!!)
                if (result.isSuccess) {
                    val saved = result.getOrNull()!!
                    Actions.broadcastFavoriteAdded(this@ShimmerWallpaperService, result = saved)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@ShimmerWallpaperService,
                            getString(R.string.toast_favorite_saved, saved.displayName),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.w(TAG, "addCurrentImageToFavorites: failed for $currentImageUri", result.exceptionOrNull())
                }
            }
        }

        /**
         * The placement [itemId] is shown with when reached without a playback step. The formal
         * Engine reads the committed history entry from the store, because a save rewrites it
         * before this Engine's memory catches up. Previews never record history.
         */
        private suspend fun resolveDisplayTransform(itemId: String): ImageTransform {
            val defaults = preferences.getP3Defaults()
            return withContext(Dispatchers.IO) {
                TransformResolution.resolveCurrent(
                    entryTransform = if (isPreview) null else playbackSnapshotStore.load()?.currentEntryTransform(itemId),
                    saved = playbackSnapshotStore.loadImageTransform(itemId),
                    defaults = defaults,
                )
            }
        }

        /**
         * Resolves the placement with full metadata for post-decode adaptation.
         */
        private suspend fun resolveDisplayPlacement(itemId: String): ResolvedTransform {
            val defaults = preferences.getP3Defaults()
            return withContext(Dispatchers.IO) {
                TransformResolution.resolveCurrentPlacement(
                    entryTransform = if (isPreview) null else playbackSnapshotStore.load()?.currentEntryTransform(itemId),
                    saved = playbackSnapshotStore.loadImageTransform(itemId),
                    defaults = defaults,
                )
            }
        }

        /** Re-resolves the image on screen after its saved placement or a global default changed. */
        private fun refreshDisplayedTransform() {
            if (!routeAllowed || !rendererReady || !editHold.admitRefresh()) return
            transformRefreshJob?.cancel()
            transformRefreshJob = scope.launch {
                val shown = cachedImageSet ?: return@launch
                val defaults = preferences.getP3Defaults()
                val resolved = resolveDisplayPlacement(shown.id)
                val adapted = TransformResolution.adaptFit(
                    resolved, defaults, shown.sourceWidth, shown.sourceHeight,
                    surfaceDimensions.width, surfaceDimensions.height
                )
                val transform = adapted.transform
                if (cachedImageSet !== shown || transform == displayedTransform) return@launch
                displayedTransform = transform
                queueEvent { renderer?.setLayerTransform(shown.id, transform) }
                scheduleRefine(transform, delayMillis = 0L)
            }
        }

        /** Formal wallpaper two-finger double tap. The editor measures entry from [gestureUptime]. */
        private fun openEditor() {
            if (routeAllowed && !isPreview && wallpaperTarget == WallpaperTarget.LOCK) {
                LockEditorBridge.launch(this@ShimmerWallpaperService, diagnosticId)
                return
            }
            if (!routeAllowed || wallpaperTarget != WallpaperTarget.HOME) return
            val gestureUptime = android.os.SystemClock.uptimeMillis()
            P0Diagnostics.event(diagnosticId, "P3_EDIT_REQUEST", "cached=${cachedImageSet != null}")
            try {
                P3EditActivity.launch(this@ShimmerWallpaperService, diagnosticId, gestureUptime)
            } catch (failed: Exception) {
                // A blocked or failed launch must not look like an opened editor.
                Log.w(TAG, "P3 editor launch failed", failed)
                P0Diagnostics.event(diagnosticId, "P3_EDIT_START_EXCEPTION", "type=${failed.javaClass.simpleName}")
            }
        }

        override fun beginEdit(): LiveEditBridge.EditSession? {
            if (!routeAllowed || wallpaperTarget != WallpaperTarget.HOME) return null
            selectionIntentToken = Any()
            pendingSelectedImageId = null
            if (isPreview || !rendererReady) return null
            val shown = cachedImageSet ?: return null
            if (surfaceDimensions.width <= 0 || surfaceDimensions.height <= 0) return null
            editSession?.let { previous ->
                // A second editor window replaces the first; the hold carries over.
                if (previous.active) {
                    previous.invalidate()
                    val restore = displayedTransform
                    queueEvent { renderer?.setLayerTransform(previous.image.imageId, restore) }
                }
            }
            if (!editHold.holding) {
                editHold.begin()
                publishDiagnosticState("OWNER_EDIT_BEGIN")
                p2GestureDetector.reset()
                // Work already in flight would switch the image under the editor. Stop it and
                // resync from the committed state when editing ends.
                val busy = listOf(imageCycleJob, imageLoadingJob, playbackSnapshotRefreshJob, transformRefreshJob)
                    .any { it?.isActive == true }
                if (busy) {
                    loadGeneration++
                    val interrupted = activeDispatch
                    activeDispatch = null
                    imageCycleJob?.cancel()
                    imageLoadingJob?.cancel()
                    playbackSnapshotRefreshJob?.cancel()
                    transformRefreshJob?.cancel()
                    editHold.admitRefresh()
                    // A scheduled switch cut off by the editor is held (or dropped) like a new one.
                    if (interrupted != null) startPlayback(PlaybackDirection.NEXT, interrupted)
                }
            }
            val defaults = preferences.getP3Defaults()
            val adaptedDefaults = defaults.adaptedTo(shown.sourceWidth, shown.sourceHeight, surfaceDimensions.width, surfaceDimensions.height)
            val disabledIds = preferences.getDisabledImages()
            val enabled = if (shown.id == ImageLoader.DEFAULT_IMAGE_ID) null else shown.id !in disabledIds
            val session = EngineEditSession(
                LiveEditBridge.EditImage(
                    imageId = shown.id,
                    sourceWidth = shown.sourceWidth,
                    sourceHeight = shown.sourceHeight,
                    viewWidth = surfaceDimensions.width,
                    viewHeight = surfaceDimensions.height,
                    original = displayedTransform ?: adaptedDefaults.toTransform(),
                    defaults = adaptedDefaults,
                    enabled = enabled,
                ),
            )
            editSession = session
            renderer?.frameStats = FrameStats.shared
            P0Diagnostics.event(diagnosticId, "P3_EDIT_BEGIN", "decoded=${shown.width}x${shown.height} sample=${shown.sampleSize}")
            return session
        }

        private inner class EngineEditSession(override var image: LiveEditBridge.EditImage) : LiveEditBridge.EditSession {
            @Volatile
            private var open = true
            @Volatile
            private var invalidated = false

            // Saving closes the UI before IO completes. Keep a separate, revocable ownership ticket.
            val ticketValid: Boolean get() = !invalidated && routeAllowed && editSession === this

            /** Newest previewed placement; replayed if the surface is rebuilt mid-edit. */
            var draft: ImageTransform? = null
                private set

            override val active: Boolean get() = open && ticketValid

            override fun preview(transform: ImageTransform, inputUptimeMillis: Long) {
                if (!active || stepping) return
                val placement = transform.sanitized()
                draft = placement
                renderer?.setLiveTransform(image.imageId, placement, inputUptimeMillis)
                scheduleRefine(placement, REFINE_DEBOUNCE_MILLIS)
            }

            override fun apply(transform: ImageTransform?) {
                if (!active || stepping) return
                applyEdit(this, transform)
            }

            override fun save(transform: ImageTransform?, saveUptimeMillis: Long) {
                if (!active || stepping) return
                open = false
                saveEdit(this, transform, saveUptimeMillis)
            }

            override fun cancel() {
                if (!active) return
                cancelStep()
                open = false
                cancelEdit(this)
            }

            fun invalidate() {
                invalidated = true
                cancelStep()
                open = false
            }

            override fun statusLine(): String = editStatusLine()

            private var stepping = false
            private var stepJob: Job? = null
            private var stepToken: Any? = null

            private fun cancelStep() {
                stepToken = null
                if (stepping) loadGeneration++
                stepJob?.cancel()
                stepJob = null
                stepping = false
            }

            override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
                if (!active || stepping) {
                    onDone(null)
                    return
                }
                if (!engineVisible || !rendererReady) {
                    onDone(LiveEditBridge.StepResult.Failed("壁纸不可见或渲染未就绪"))
                    return
                }
                stepping = true
                refineJob?.cancel()
                imageLoadingJob?.cancel()
                val token = Any()
                stepToken = token
                val work = PlaybackWorkEvidence(PlaybackWorkScope.EDITOR)
                stepJob = scope.launch {
                    val handoff = PlaybackImageHandoff<ImageSet> { it.recycleAll() }
                    var workResult = "CANCELLED"
                    try {
                        currentCoroutineContext().ensureActive()
                        if (!active || stepToken !== token) return@launch
                        // Apply or discard changes first
                        if (saveFirst) {
                            applyEditAndWait(this@EngineEditSession, transform)
                        } else if (!saveFirst) {
                            val restore = displayedTransform
                            queueEvent { renderer?.setLayerTransform(image.imageId, restore) }
                        }
                        draft = null
                        currentCoroutineContext().ensureActive()
                        if (!active || stepToken !== token) return@launch

                        // Now step through the coordinator
                        val direction = if (forward) PlaybackDirection.NEXT else PlaybackDirection.PREVIOUS
                        val generation = ++loadGeneration
                        val viewport = surfaceDimensions
                        val defaults = preferences.getP3Defaults()

                        val catalogTiming = PlaybackCatalogTiming(SystemClock::elapsedRealtimeNanos)
                        var catalogReadCompleted = false
                        val catalog = try {
                            folderRepository.getPlaybackCatalog(catalogTiming).also { catalogReadCompleted = true }
                        } finally {
                            catalogTiming.finish()?.let { parts ->
                                P0Diagnostics.event(diagnosticId, "P9_CATALOG_TIMING",
                                    "trace=${work.traceId} preview=$isPreview direction=$direction edit=true catalogReadCompleted=$catalogReadCompleted " +
                                        parts.diagnosticFields())
                            }
                        }
                        val items = catalog.items

                        val outcome = onPlaybackThread {
                            P0Diagnostics.event(diagnosticId, "P3_EDIT_STEP", "direction=$direction cursor=${playbackCoordinator.snapshot().historyCursor}")
                            val expected = playbackSnapshotStore.load()
                            if (isPreview) playbackCoordinator.setItems(items)
                            else playbackCoordinator.setItemsAndRestore(items, expected ?: PlaybackSnapshot()).also(work::preparation)

                            val loaded = PlaybackImageResolver<Pair<ImageSet, ResolvedTransform>>(playbackCoordinator)
                                .resolveRequest(direction) { request, item ->
                                    if (item.id == image.imageId) return@resolveRequest null // Same image, skip
                                    val placement = TransformResolution.resolve(
                                        freshAdvance = request.freshAdvance,
                                        entryTransform = request.entryTransform,
                                        saved = playbackSnapshotStore.loadImageTransform(item.id),
                                        defaults = defaults,
                                        previousDisplayed = null,
                                    )
                                    val adaptFit = TransformResolution.smartFitApplies(placement, defaults)
                                    decodeImage(Uri.parse(item.id), placement.transform, adaptFit)?.let { imageSet ->
                                        val fitted = TransformResolution.fitInherited(placement, imageSet.sourceWidth, imageSet.sourceHeight, viewport.width, viewport.height)
                                        val adapted = TransformResolution.adaptFit(fitted, defaults, imageSet.sourceWidth, imageSet.sourceHeight, viewport.width, viewport.height)
                                        imageSet to adapted
                                    }
                                }

                            if (loaded == null) {
                                return@onPlaybackThread PlaybackOutcome.NoCandidate
                            }

                            val request = loaded.request
                            val (newSet, placement) = loaded.image
                            handoff.capture(newSet)
                            val annotation = placement.transform.takeIf { placement.annotateHistory }

                            if (!active || stepToken !== token || !currentCoroutineContext().isActive || !engineVisible || !rendererReady ||
                                generation != loadGeneration || !playbackCoordinator.commit(request.version, annotation)) {
                                handoff.close()
                                playbackCoordinator.reject(request.version)
                                return@onPlaybackThread PlaybackOutcome.Dropped
                            }

                            val stepTask = currentCoroutineContext()[Job]
                            val storedCommit = if (isPreview) null else playbackSnapshotStore.compareAndSaveIfAllowedWithWork(expected, playbackCoordinator.snapshot()) {
                                    stepTask?.isActive == true && active && stepToken === token && generation == loadGeneration
                                }
                            storedCommit?.let { work.storage(it.work) }
                            if (storedCommit?.accepted == false) {
                                handoff.close()
                                playbackCoordinator.restore(playbackSnapshotStore.load() ?: playbackCoordinator.snapshot())
                                P0Diagnostics.event(diagnosticId, "P3_EDIT_STALE", "version=${request.version}")
                                return@onPlaybackThread PlaybackOutcome.Stale
                            }

                            P0Diagnostics.event(diagnosticId, "P3_EDIT_COMMIT", "version=${request.version} cursor=${playbackCoordinator.snapshot().historyCursor}")
                            PlaybackOutcome.Committed(newSet, Uri.parse(request.item!!.id), placement.transform)
                        }

                        currentCoroutineContext().ensureActive()
                        if (!active || stepToken !== token || generation != loadGeneration) {
                            onDone(null)
                            return@launch
                        }
                        when (outcome) {
                            PlaybackOutcome.NoCandidate -> {
                                workResult = "NO_CANDIDATE"
                                stepping = false
                                onDone(LiveEditBridge.StepResult.NoCandidate)
                            }
                            PlaybackOutcome.Dropped -> {
                                workResult = "DROPPED"
                                stepping = false
                                onDone(LiveEditBridge.StepResult.Failed("已取消"))
                            }
                            PlaybackOutcome.Stale -> {
                                workResult = "STALE"
                                stepping = false
                                refreshPlaybackStateFromSnapshot()
                                onDone(LiveEditBridge.StepResult.Failed("目录已变更"))
                            }
                            is PlaybackOutcome.Committed -> {
                                updateActiveImageSet(outcome.image, outcome.uri, outcome.transform, direction) {
                                    handoff.transfer(outcome.image)
                                }
                                workResult = "COMMITTED"
                                if (!isPreview) {
                                    schedule.onManualCommit(diagnosticId, "编辑页切换（${if (forward) "下一张" else "上一张"}）")
                                    folderRepository.commitImageShown(outcome.uri)
                                }

                                val adaptedDefaults = defaults.adaptedTo(
                                    outcome.image.sourceWidth, outcome.image.sourceHeight,
                                    viewport.width, viewport.height
                                )
                                val disabledIds = preferences.getDisabledImages()
                                val enabled = if (outcome.uri.toString() == ImageLoader.DEFAULT_IMAGE_ID) null else outcome.uri.toString() !in disabledIds

                                if (active) {
                                    image = LiveEditBridge.EditImage(
                                        imageId = outcome.uri.toString(),
                                        sourceWidth = outcome.image.sourceWidth,
                                        sourceHeight = outcome.image.sourceHeight,
                                        viewWidth = viewport.width,
                                        viewHeight = viewport.height,
                                        original = outcome.transform,
                                        defaults = adaptedDefaults,
                                        enabled = enabled,
                                    )
                                    stepping = false
                                    onDone(LiveEditBridge.StepResult.Success(image))
                                } else {
                                    stepping = false
                                    onDone(null)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        workResult = if (workResult == "COMMITTED") "COMMITTED_SAVE_CANCELLED" else "CANCELLED"
                        onDone(null)
                        throw cancelled
                    } catch (e: Exception) {
                        workResult = if (workResult == "COMMITTED") "COMMITTED_SAVE_ERROR" else "ERROR"
                        Log.e(TAG, "Editor step failed", e)
                        stepping = false
                        onDone(LiveEditBridge.StepResult.Failed(e.message ?: "未知错误"))
                    } finally {
                        work.finish()?.let { report ->
                            P0Diagnostics.event(diagnosticId, "P9_PLAYBACK_WORK", "result=$workResult preview=$isPreview ${report.diagnosticFields()}")
                        }
                        handoff.close()
                        if (stepToken === token) {
                            stepping = false
                            stepJob = null
                            stepToken = null
                        }
                    }
                }
                // A cancelled launch may never enter its body or finally. Same once-only report.
                stepJob?.invokeOnCompletion { failure ->
                    if (failure != null) work.finish()?.let { report ->
                        val result = if (failure is CancellationException) "CANCELLED" else "ERROR"
                        P0Diagnostics.event(diagnosticId, "P9_PLAYBACK_WORK", "result=$result preview=$isPreview ${report.diagnosticFields()}")
                    }
                }
            }
        }

        private fun applyEdit(session: EngineEditSession, transform: ImageTransform?) {
            scope.launch { applyEditAndWait(session, transform) }
        }

        private suspend fun applyEditAndWait(session: EngineEditSession, transform: ImageTransform?) {
            if (!session.active) return
            val imageId = session.image.imageId
            val final = (transform ?: session.image.defaults.toTransform()).sanitized()
            val saved = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val task = currentCoroutineContext()[Job]
                playbackSnapshotStore.commitImageTransformIfAllowed(imageId, transform?.sanitized()) {
                    task?.isActive == true && routeAllowed && session.active
                }
            }
            currentCoroutineContext().ensureActive()
            if (!saved || !session.active) return
            if (cachedImageSet?.id == imageId) displayedTransform = final
            queueEvent { renderer?.setLayerTransform(imageId, final) }
            refineNow(final)
        }

        private fun saveEdit(session: EngineEditSession, transform: ImageTransform?, saveUptimeMillis: Long) {
            if (!session.ticketValid) return
            val imageId = session.image.imageId
            scope.launch {
                if (!session.ticketValid) return@launch
                // Null resets: the image drops its own config and shows the global default.
                val final = (transform ?: session.image.defaults.toTransform()).sanitized()
                if (cachedImageSet?.id == imageId) displayedTransform = final
                queueEvent { renderer?.setLayerTransform(imageId, final) }
                // One prefs commit holds the config and clears this image's inherited history
                // copies; parsing a long history stays off Main.
                val saved = withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val task = currentCoroutineContext()[Job]
                    playbackSnapshotStore.commitImageTransformIfAllowed(imageId, transform?.sanitized()) {
                        task?.isActive == true && session.ticketValid
                    }
                }
                if (!saved || !session.ticketValid) return@launch
                refineNow(final)
                // Queued after the placement and any swap, so its frame shows the final result.
                queueEvent { renderer?.markApplied(FrameStats.shared, saveUptimeMillis) }
                P0Diagnostics.event(diagnosticId, "P3_EDIT_SAVE", "reset=${transform == null}")
                finishEdit(session)
            }
        }

        private fun cancelEdit(session: EngineEditSession) {
            val restore = displayedTransform
            queueEvent { renderer?.setLayerTransform(session.image.imageId, restore) }
            refineJob?.cancel()
            P0Diagnostics.event(diagnosticId, "P3_EDIT_CANCEL", "")
            finishEdit(session)
        }

        /** Ends the hold: the deferred refresh first, then at most one switch, then a fresh timer. */
        private fun finishEdit(session: EngineEditSession) {
            if (editSession !== session) return
            editSession = null
            renderer?.frameStats = null
            val deferred = editHold.release()
            publishDiagnosticState("OWNER_EDIT_END")
            // The interval restarts; a held scheduled switch below still runs once.
            if (!isPreview) schedule.onEditEnded(diagnosticId)
            if (deferred.isEmpty()) return
            scope.launch {
                if (HeldAction.Refresh in deferred) {
                    refreshPlaybackStateFromSnapshot()
                    refreshDisplayedTransform()
                    // The switch must see the refreshed catalog and order.
                    playbackSnapshotRefreshJob?.join()
                }
                when (val action = deferred.lastOrNull { it != HeldAction.Refresh }) {
                    is HeldAction.Step -> requestPlayback(action.direction, action.dispatch)
                    is HeldAction.SetImage -> showImageDirect(Uri.parse(action.itemId))
                    else -> Unit
                }
            }
        }

        /** Debounced finer decode after zooming in; one runs at a time and is not restarted. */
        private fun scheduleRefine(transform: ImageTransform, delayMillis: Long) {
            val shown = cachedImageSet ?: return
            val decoding = refineDecodingJob?.isActive == true
            if (!imageLoader.needsFinerDecode(shown, transform)) {
                // Zoomed back out before the decode started: it is no longer needed.
                if (!decoding) refineJob?.cancel()
                return
            }
            if (decoding && refineSample <= imageLoader.targetSampleSize(shown, transform)) return
            refineJob?.cancel()
            refineJob = scope.launch {
                delay(delayMillis)
                refine(shown, transform)
            }
        }

        /** Save path: the saved placement is shown at full detail before the save counts as applied. */
        private suspend fun refineNow(transform: ImageTransform) {
            val shown = cachedImageSet ?: return
            if (!imageLoader.needsFinerDecode(shown, transform)) return
            val running = refineDecodingJob
            if (running != null && running.isActive && refineSample <= imageLoader.targetSampleSize(shown, transform)) {
                running.join()
                return
            }
            refineJob?.cancel()
            refine(shown, transform)
        }

        /** Decodes [shown] again at the detail [transform] needs and swaps it in without a crossfade. */
        private suspend fun refine(shown: ImageSet, transform: ImageTransform) {
            if (cachedImageSet !== shown || !imageLoader.needsFinerDecode(shown, transform)) return
            val self = currentCoroutineContext()[kotlinx.coroutines.Job]
            refineSample = imageLoader.targetSampleSize(shown, transform)
            refineDecodingJob = self
            val uri = if (shown.id == ImageLoader.DEFAULT_IMAGE_ID) null else Uri.parse(shown.id)
            val refined = try {
                decodeImage(uri, transform)
            } finally {
                if (refineDecodingJob === self) refineDecodingJob = null
            } ?: return
            if (cachedImageSet !== shown || refined.id != shown.id || refined.sampleSize >= shown.sampleSize) {
                refined.recycleAll()
                return
            }
            cachedImageSet = refined
            val queued = queueEvent {
                val replaced = renderer?.replaceImage(refined) == true
                scope.launch {
                    if (replaced) {
                        shown.recycleAll()
                    } else {
                        // Another image took over first; it owns the screen, this copy is spare.
                        if (cachedImageSet === refined) cachedImageSet = shown
                        refined.recycleAll()
                    }
                }
            }
            if (!queued) {
                cachedImageSet = shown
                refined.recycleAll()
            }
            P0Diagnostics.event(diagnosticId, "P3_REFINE", "sample=${shown.sampleSize}->${refined.sampleSize} queued=$queued")
        }

        /** Engine half of the editor's measurement line. */
        private fun editStatusLine(): String {
            val shown = cachedImageSet
            val decoded = shown?.let {
                val megapixels = String.format(java.util.Locale.ROOT, "%.1f", it.width * it.height / 1_000_000.0)
                val background = it.background?.let { bg -> "${bg.width}×${bg.height}" } ?: "无"
                "前景 ${it.width}×${it.height}（$megapixels MP，采样 ${it.sampleSize}，原图 ${it.sourceWidth}×${it.sourceHeight}），背景 $background"
            } ?: "无图片"
            val last = decodeGate.lastDurationMillis.takeIf { it >= 0 }?.let { "$it ms" } ?: "无"
            return "壁纸可见 ${if (engineVisible) "是" else "否"}；$decoded；纹理上限 ${imageLoader.maxTextureSize}；" +
                "解码 ${decodeGate.completedCount} 次，最近 $last，并发峰值 ${decodeGate.peakConcurrency}"
        }
        override fun onSurfaceCreated(h: SurfaceHolder) {
            super.onSurfaceCreated(h)
            rendererReady = false
            diagnosticSurfaceValid = h.surface.isValid
            publishDiagnosticState("OWNER_SURFACE_CREATE")
        }

        override fun onSurfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(h, format, width, height)
            diagnosticSurfaceValid = h.surface.isValid
            diagnosticWidth = width
            diagnosticHeight = height
            publishDiagnosticState("OWNER_SURFACE_CHANGE")
        }

        override fun onSurfaceDestroyed(h: SurfaceHolder) {
            renderer?.setMediaPlaybackAllowed(false)
            lastPresentedLockToken = null
            rememberSelectedImageForLifecycle()
            rendererReady = false
            diagnosticSurfaceValid = false
            publishDiagnosticState("OWNER_SURFACE_DESTROY")
            loadGeneration++
            imageLoadingJob?.cancel()
            cancelPlaybackStep(SwitchOutcome.CANCELLED)
            super.onSurfaceDestroyed(h)
        }

        override fun onDestroy() {
            renderer?.setMediaPlaybackAllowed(false)
            diagnosticAlive = false
            if (wallpaperTarget == WallpaperTarget.LOCK) {
                nativeLockRequest?.let { finishNativeLock(it, "CANCELLED") }
                LockEditorBridge.unregister(diagnosticId)
                if (lockEditorDelegate.isInitialized()) lockEditor.close()
                LockEngineHostRegistry.unregister(diagnosticId, lockHostTarget)
                lockRelayBinding?.close(); lockRelayBinding = null
            }
            P0Diagnostics.event(diagnosticId, "P2_ENGINE_DESTROY", "preview=$isPreview")
            diagnosticAlive = false
            P0Diagnostics.unregisterEngine(diagnosticId)
            LiveEditBridge.unregister(diagnosticId)
            // Owed scheduled switches end here; after unregistering, this Engine reports nothing.
            listOfNotNull(activeDispatch, pendingPlaybackDispatch, parkedDispatch)
                .forEach { reportSchedule(it, SwitchOutcome.CANCELLED, "Engine 已销毁") }
            (editHold.deferredSwitch as? HeldAction.Step)?.dispatch
                ?.let { reportSchedule(it, SwitchOutcome.CANCELLED, "Engine 已销毁") }
            schedule.unregister(diagnosticId)
            // An open editor sees the session end and closes; nothing unsaved is written.
            editSession?.invalidate()
            editSession = null
            touchEffectController.cleanup()
            p2GestureDetector.close()
            preferences.unregisterListener(preferenceListener)
            playbackSnapshotStore.unregisterListener(playbackSnapshotListener)
            PlaybackSelectionBridge.unregister(diagnosticId)
            selectionIntentToken = Any()
            pendingSelectedImageId = null
            try {
                unregisterReceiver(shortcutReceiver)
            } catch (_: Exception) {
            }
            try {
                unregisterReceiver(screenUnlockReceiver)
            } catch (_: Exception) {
            }
            playbackSnapshotRefreshJob?.cancel()
            catalogObservationJob?.cancel()
            scope.cancel()
            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            super.onDestroy()
            cachedImageSet?.recycleAll()
        }
    }
}
