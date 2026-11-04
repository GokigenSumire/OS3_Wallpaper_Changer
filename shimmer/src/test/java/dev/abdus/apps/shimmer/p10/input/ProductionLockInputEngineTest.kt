package dev.abdus.apps.shimmer.p10.input

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceView
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.AnimationController
import dev.abdus.apps.shimmer.ImageSet
import dev.abdus.apps.shimmer.ShimmerRenderer
import dev.abdus.apps.shimmer.ShimmerWallpaperService
import dev.abdus.apps.shimmer.SurfaceDimensions
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p10.LockWallpaperService
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p10.editor.LockEditorBridge
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p3.EditHoldGate
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.recycleAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import personal.wallpaper.protocol.lock.LockProtocol
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Real Engine/registry/bridge/controller adapter, without onCreate/setRenderer/Surface/EGL.
 * Presented callbacks below simulate the already successful swap edge; they do not prove GPU
 * presentation or phone keyguard reachability. Private authentication UI has separate tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ProductionLockInputEngineTest.FlagsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProductionLockInputEngineTest {
    @Test fun lostContextRevokesPresentationAndRejectsItsQueuedFrameReceipt() {
        set(engine, "lastPresentedLockToken", null)
        engine.onImagePresented(A, initial)
        engine.onPresentationInvalidated()
        dispatcher.drain()
        assertEquals("UNKNOWN_STATE", target.state().reason)
        assertNull(field<Any?>(engine, "lastPresentedLockToken"))
        engine.onImagePresented(A, initial)
        dispatcher.drain()
        assertTrue(target.state().ready)
    }

    @Test fun presentationDiagnosticsDistinguishDirectionFromUnchangedImage() {
        val ledger = dev.abdus.apps.shimmer.p10.editor.LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val cases = listOf(
            Triple(LockProtocol.NEXT, B, dev.abdus.apps.shimmer.p10.editor.LockEditDiagnosticCause.NAV_NEXT_PRESENTED),
            Triple(LockProtocol.PREVIOUS, B, dev.abdus.apps.shimmer.p10.editor.LockEditDiagnosticCause.NAV_PREVIOUS_PRESENTED),
            Triple(LockProtocol.NEXT, A, dev.abdus.apps.shimmer.p10.editor.LockEditDiagnosticCause.NAV_UNCHANGED),
        )
        cases.forEachIndexed { index, (action, original, expected) ->
            val diagnostic = ledger.begin("owner", "request-$index")!!
            val results = mutableListOf<String>()
            dev.abdus.apps.shimmer.p10.editor.LockEditDiagnostics.withRequest(diagnostic) {
                nativeRequest("request-$index", field(engine, "loadGeneration"), initial, results)
            }
            val request = field<Any>(engine, "nativeLockRequest")
            set(request, "action", action); set(request, "originalImage", original)
            assertEquals(index, ledger.read("owner", 0)!!.rows.size)
            engine.onImagePresented(A, initial)
            dispatcher.drain()
            assertEquals(listOf("COMMITTED"), results)
            assertEquals(expected, ledger.read("owner", 0)!!.rows.last().cause)
        }
        assertFalse(ledger.read("owner", 0).toString().contains(A))
    }

    @Test fun formalLockRawTouchesCannotCreateASecondNavigationOwner() {
        val before = store.load()
        val generation = field<Long>(engine, "loadGeneration")
        val queued = dispatcher.pending()
        val start = android.os.SystemClock.uptimeMillis()
        repeat(3) { tap ->
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val time = start + tap * 80L + if (action == android.view.MotionEvent.ACTION_UP) 20L else 0L
                val event = android.view.MotionEvent.obtain(start + tap * 80L, time, action, 50f, 100f, 0)
                try { engine.onTouchEvent(event) } finally { event.recycle() }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700L))
        assertEquals(generation, field<Long>(engine, "loadGeneration"))
        assertEquals(queued, dispatcher.pending())
        assertNull(field<Job?>(engine, "imageCycleJob"))
        assertEquals(before, store.load())
        assertTrue(target.state().ready)
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var service: ServiceController<LockWallpaperService>
    private lateinit var engine: ShimmerWallpaperService.ShimmerWallpaperEngine
    private lateinit var target: LockEngineHostRegistry.Target
    private lateinit var id: String
    private val dispatcher = QueueDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
    private val home = PlaybackSnapshotStore(context, WallpaperTarget.HOME)
    private val images = mutableListOf<ImageSet>()
    private lateinit var initial: ImageSet
    private var destroyed = false
    private val replacements = mutableListOf<ShimmerWallpaperService.ShimmerWallpaperEngine>()
    private val requestAuthorized = AtomicBoolean(true)

    @Before fun bindRealEngineToControlledMainQueueWithoutCreatingGl() {
        FlagsShadow.flags = 2
        service = Robolectric.buildService(LockWallpaperService::class.java).create()
        engine = service.get().onCreateEngine() as ShimmerWallpaperService.ShimmerWallpaperEngine
        field<CoroutineScope>(engine, "scope").cancel()
        set(engine, "scope", scope)
        // Direct private-Target calls below stand in for an already authenticated Host lease;
        // they do not fabricate a successful cross-UID Binder connection in the unit host.
        set(engine, "lockRequestAuthorization", { _: String -> requestAuthorized.get() })
        set(engine, "diagnosticAlive", true)
        set(engine, "routeAllowed", true)
        set(engine, "observedFlags", 2)
        set(engine, "rendererReady", true)
        set(engine, "engineVisible", true)
        set(engine, "surfaceDimensions", SurfaceDimensions(100, 200))
        initial = image(A)
        set(engine, "cachedImageSet", initial)
        set(engine, "currentImageUri", Uri.parse(A))
        set(engine, "lockPresentationToken", initial)
        set(engine, "lastPresentedLockToken", initial)
        set(engine, "displayedTransform", ImageTransform())
        id = field(engine, "diagnosticId")
        target = field(engine, "lockHostTarget")
        store.save(PlaybackSnapshot(currentId = A, historyIds = listOf(A), historyCursor = 0))
        home.save(PlaybackSnapshot(currentId = "HOME", historyIds = listOf("HOME"), historyCursor = 0))
        // Register the real listeners explicitly, without initializeRuntime's GL thread.
        field<WallpaperPreferences>(engine, "preferences").registerListener(field(engine, "preferenceListener"))
        field<PlaybackSnapshotStore>(engine, "playbackSnapshotStore").registerListener(field(engine, "playbackSnapshotListener"))
        LockEngineHostRegistry.register(id, target)
    }

    @After fun closeOnlyHostFixtures() {
        if (::engine.isInitialized && !destroyed) engine.onDestroy()
        replacements.forEach { it.onDestroy() }
        scope.cancel()
        dispatcher.drain()
        shadowOf(Looper.getMainLooper()).idle()
        images.forEach(ImageSet::recycleAll)
        if (::service.isInitialized) service.destroy()
        listOf("INSTANCE", "LOCK_INSTANCE").forEach { name ->
            ShimmerDatabase::class.java.getDeclaredField(name).apply {
                isAccessible = true; (get(null) as? ShimmerDatabase)?.close(); set(null, null)
            }
        }
    }

    private fun image(uri: String) = ImageSet(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        id = uri, sourceWidth = 100, sourceHeight = 200).also { images += it }
    private fun hold() = field<EditHoldGate>(engine, "editHold").holding
    private fun requestEdit(requestId: String = "new-edit"): Pair<String, LockHostResult> {
        var result: LockHostResult? = null
        target.command(LockProtocol.EDIT, target.state().ticket, requestId) { result = it }
        assertEquals("EDIT_READY", result!!.status)
        assertTrue(hold())
        val token = shadowOf(result!!.editLaunch!!).savedIntent.getStringExtra(LockEditorBridge.EXTRA_TOKEN)!!
        return token to result!!
    }
    private fun authenticate(token: String): LockEditorBridge.Session {
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertTrue(LockEditorBridge.systemSucceeded(token, false))
        return LockEditorBridge.consumeAuthenticated(token)!!
    }

    @Test fun stateRequiresActuallyPresentedTokenAndExactStoredCurrent() {
        assertTrue(target.state().ready)
        val unpresented = image(A)
        set(engine, "lockPresentationToken", unpresented)
        assertEquals("UNKNOWN_STATE", target.state().reason)
        engine.onImagePresented(A, unpresented)
        dispatcher.drain()
        assertTrue(target.state().ready)
        store.update { it!!.copy(currentId = B) }
        assertEquals("STALE_TICKET", target.state().reason)
    }

    @Test fun realEditHoldSurvivesExpectedHiddenSurfaceAndAuthentication() {
        val beforeHome = home.load()
        val (token) = requestEdit()
        engine.onVisibilityChanged(false)
        engine.onSurfaceDestroyed(SurfaceView(context).holder)
        assertFalse(field<Boolean>(engine, "rendererReady"))
        assertTrue(hold())
        val session = authenticate(token)
        assertTrue(session.active)
        assertEquals(A, session.previewUri.toString())
        assertEquals(beforeHome, home.load())
        session.cancel()
        assertFalse(hold())
    }

    @Test fun oldRequestIdCannotRevokeNewPendingOrAuthenticatedEditor() {
        val (token) = requestEdit("new-edit")
        target.cancel("old-edit")
        assertTrue(hold())
        val session = authenticate(token)
        target.cancel("old-edit")
        assertTrue(session.active)
        assertTrue(hold())
        target.cancel("new-edit")
        assertFalse(session.active)
        assertFalse(hold())
    }

    @Test fun nativeOwnCurrentDisableDoesNotRevokeAuthenticatedLockSessionOrHome() {
        val session = authenticate(requestEdit().first)
        val preferences = field<WallpaperPreferences>(engine, "preferences")
        preferences.setImageEnabled(A, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.active)
        preferences.setImageEnabled(A, true)
        assertTrue(session.active)
        assertTrue(WallpaperPreferences.create(context).getDisabledImages().isEmpty())
        assertNull(field<Job?>(engine, "playbackSnapshotRefreshJob"))
        session.cancel()
    }

    @Test fun exactFlagsRevocationCancelsPendingAndCannotOpenOtherDomain() {
        val homeBefore = home.load()
        val (token) = requestEdit()
        FlagsShadow.flags = 1
        engine.onWallpaperFlagsChanged(1)
        assertFalse(hold())
        assertFalse(LockEditorBridge.beginAuthentication(token))
        assertEquals(homeBefore, home.load())
        assertFalse(target.state().ready)
    }

    @Test fun destroyRevokesAuthenticatedSessionWithoutSchedulingNewPlayback() {
        val session = authenticate(requestEdit().first)
        engine.onDestroy()
        destroyed = true
        dispatcher.drain()
        assertFalse(session.active)
        assertFalse(hold())
        assertNull(field<Job?>(engine, "playbackSnapshotRefreshJob"))
        assertFalse(field<Boolean>(engine, "diagnosticAlive"))
    }

    @Test fun wrongTicketCannotAcquireEditHoldOrProduceCapability() {
        var result: LockHostResult? = null
        target.command(LockProtocol.EDIT, "wrong", "request") { result = it }
        assertEquals("STALE_TICKET", result!!.status)
        assertNull(result!!.editLaunch)
        assertFalse(hold())
    }

    @Test fun oldRequestIdCannotCancelNewNativeOperationButExactIdCan() {
        val homeBefore = home.load()
        val results = mutableListOf<String>()
        target.command(LockProtocol.NEXT, target.state().ticket, "new-native") { results += it.status }
        assertNotNull(field<Any?>(engine, "nativeLockRequest"))
        target.cancel("old-native")
        assertNotNull(field<Any?>(engine, "nativeLockRequest"))
        assertTrue(results.isEmpty())
        target.cancel("new-native")
        dispatcher.drain()
        assertNull(field<Any?>(engine, "nativeLockRequest"))
        assertEquals(listOf("CANCELLED"), results)
        assertEquals(homeBefore, home.load())
        assertEquals(A, store.load()!!.currentId)
    }

    @Test fun sameUriOldQueuedFrameCannotCompleteNewNativeRequest() {
        val results = mutableListOf<String>()
        // The old successful frame is already in Main's queue when a same-URI new operation wins.
        engine.onImagePresented(A, initial)
        val next = image(A)
        set(engine, "cachedImageSet", next)
        set(engine, "lockPresentationToken", next)
        set(engine, "loadGeneration", 2L)
        nativeRequest("new-native", 2L, next, results)
        dispatcher.drain()
        assertTrue(results.isEmpty())
        assertNotSame(next, field<Any?>(engine, "lastPresentedLockToken"))
        engine.onImagePresented(A, next)
        dispatcher.drain()
        assertEquals(listOf("COMMITTED"), results)
        assertNull(field<Any?>(engine, "nativeLockRequest"))
    }

    @Test fun currentFrameCannotCompleteRequestBoundToDifferentSubmissionToken() {
        val results = mutableListOf<String>()
        val next = image(A)
        set(engine, "lockPresentationToken", next)
        set(engine, "loadGeneration", 3L)
        nativeRequest("native", 3L, initial, results)
        engine.onImagePresented(A, next)
        dispatcher.drain()
        assertTrue(results.isEmpty())
        assertNotNull(field<Any?>(engine, "nativeLockRequest"))
    }

    @Test fun staleGenerationCannotReturnCommittedEvenWithMatchingUriAndToken() {
        val results = mutableListOf<String>()
        set(engine, "loadGeneration", 5L)
        nativeRequest("native", 4L, initial, results)
        engine.onImagePresented(A, initial)
        dispatcher.drain()
        assertTrue(results.isEmpty())
    }

    @Test fun nonNativeAnimationEndRefreshesCachedBusyStateToReady() {
        val renderer = ShimmerRenderer(object : ShimmerRenderer.Callbacks {
            override fun requestRender() = Unit
            override fun onRendererReady() = Unit
            override fun onReadyForNextImage() = Unit
            override fun onSurfaceDimensionsChanged(width: Int, height: Int) = Unit
        })
        set(engine, "renderer", renderer)
        val animation = field<AnimationController>(renderer, "animationController")
        animation.imageTransitionAnimator.start(0f, 1f)
        LockEngineHostRegistry.refresh()
        // Presentation was already committed. A visual transition alone must not close input admission.
        assertTrue(LockEngineHostRegistry.snapshot().ready)
        animation.imageTransitionAnimator.reset()
        assertNull(field<Any?>(engine, "nativeLockRequest"))
        engine.onImagePresented(A, initial)
        dispatcher.drain()
        assertTrue(LockEngineHostRegistry.snapshot().ready)
    }

    @Test fun committedFrameCanAcceptOneNavigationWhileOnlyTransitionStillRuns() {
        val renderer = ShimmerRenderer(object : ShimmerRenderer.Callbacks {
            override fun requestRender() = Unit
            override fun onRendererReady() = Unit
            override fun onReadyForNextImage() = Unit
            override fun onSurfaceDimensionsChanged(width: Int, height: Int) = Unit
        })
        set(engine, "renderer", renderer)
        val animation = field<AnimationController>(renderer, "animationController")
        animation.imageTransitionAnimator.start(0f, 1f)
        assertTrue(target.state().ready)
        val results = mutableListOf<String>()
        target.command(LockProtocol.NEXT, target.state().ticket, "animated-navigation") { results.add(it.status) }
        assertTrue(results.isEmpty())
        assertNotNull(field<Any?>(engine, "nativeLockRequest"))
        // Genuine edit/load ownership still prevents a second competing command.
        assertEquals("BUSY_EDIT", target.state().reason)
    }

    @Test fun deferredNativeNavigationKeepsItsAuthorityAndCannotReplayAfterRevocation() {
        val renderer = ShimmerRenderer(object : ShimmerRenderer.Callbacks {
            override fun requestRender() = Unit
            override fun onRendererReady() = Unit
            override fun onReadyForNextImage() = Unit
            override fun onSurfaceDimensionsChanged(width: Int, height: Int) = Unit
        })
        set(engine, "renderer", renderer)
        val animation = field<AnimationController>(renderer, "animationController")
        animation.imageTransitionAnimator.start(0f, 1f)
        val results = mutableListOf<String>()
        target.command(LockProtocol.NEXT, target.state().ticket, "deferred-authority") { results.add(it.status) }
        assertSame(field<Any?>(engine, "nativeLockRequest"), field<Any?>(engine, "pendingNativeLockRequest"))
        assertTrue(results.isEmpty())
        requestAuthorized.set(false)
        animation.imageTransitionAnimator.reset()
        engine.onReadyForNextImage()
        dispatcher.drain()
        assertEquals(listOf("CANCELLED"), results)
        assertNull(field<Any?>(engine, "nativeLockRequest"))
        assertNull(field<Any?>(engine, "pendingNativeLockRequest"))
        assertEquals(A, store.load()!!.currentId)
        assertEquals("HOME", home.load()!!.currentId)
    }

    @Test fun transitionEndReplaysTheExactNativeRequestInsteadOfAnOrdinaryGesture() {
        val renderer = ShimmerRenderer(object : ShimmerRenderer.Callbacks {
            override fun requestRender() = Unit
            override fun onRendererReady() = Unit
            override fun onReadyForNextImage() = Unit
            override fun onSurfaceDimensionsChanged(width: Int, height: Int) = Unit
        })
        set(engine, "renderer", renderer)
        val animation = field<AnimationController>(renderer, "animationController")
        animation.imageTransitionAnimator.start(0f, 1f)
        val results = mutableListOf<String>()
        target.command(LockProtocol.PREVIOUS, target.state().ticket, "same-native-request") { results.add(it.status) }
        val request = field<Any>(engine, "nativeLockRequest")
        assertSame(request, field<Any?>(engine, "pendingNativeLockRequest"))
        animation.imageTransitionAnimator.reset()
        engine.onReadyForNextImage()
        dispatcher.runOne()
        assertSame(request, field<Any?>(engine, "nativeLockRequest"))
        assertNull(field<Any?>(engine, "pendingNativeLockRequest"))
        assertTrue(field<Long>(request, "generation") >= 0L)
        assertTrue(results.isEmpty()) // callback belongs to actual presentation, not the replay edge.
    }

    @Test fun finerCacheIdentityDoesNotInvalidateOriginalSubmissionReceipt() {
        val results = mutableListOf<String>()
        val finer = image(A)
        set(engine, "cachedImageSet", finer)
        // Refine retains the already submitted layer's token, rather than cache identity.
        set(engine, "loadGeneration", 6L)
        nativeRequest("native", 6L, initial, results)
        engine.onImagePresented(A, initial)
        dispatcher.drain()
        assertEquals(listOf("COMMITTED"), results)
        assertTrue(target.state().ready)
    }

    @Test fun hiddenFrameDoesNotAcknowledgeNativeOperationOrMarkReady() {
        val results = mutableListOf<String>()
        set(engine, "engineVisible", false)
        nativeRequest("native", field(engine, "loadGeneration"), initial, results)
        engine.onImagePresented(A, initial)
        dispatcher.drain()
        assertTrue(results.isEmpty())
        assertFalse(target.state().ready)
    }

    @Test fun newFormalEngineRevokesOldPendingEvenWhenSameImageSnapshotDidNotChange() {
        val homeBefore = home.load()
        val (oldToken) = requestEdit()
        val next = replacement()
        assertFalse(hold())
        assertFalse(LockEditorBridge.beginAuthentication(oldToken))
        assertFalse(field<Boolean>(engine, "routeAllowed"))
        assertTrue(field<Boolean>(engine, "routeRevoked"))
        assertFalse(field<CoroutineScope>(engine, "scope").isActive)
        assertTrue(field<LockEngineHostRegistry.Target>(next, "lockHostTarget").state().ready)
        assertEquals(homeBefore, home.load())
        assertEquals(A, store.load()!!.currentId)
    }

    @Test fun oldEngineDestroyDoesNotRevokeNewEnginesAuthenticationOrRegistration() {
        requestEdit()
        val next = replacement()
        val nextTarget = field<LockEngineHostRegistry.Target>(next, "lockHostTarget")
        var result: LockHostResult? = null
        nextTarget.command(LockProtocol.EDIT, nextTarget.state().ticket, "new-authority-edit") { result = it }
        assertEquals("EDIT_READY", result!!.status)
        val token = shadowOf(result!!.editLaunch!!).savedIntent.getStringExtra(LockEditorBridge.EXTRA_TOKEN)!!
        engine.onDestroy()
        destroyed = true
        assertTrue(field<EditHoldGate>(next, "editHold").holding)
        val session = authenticate(token)
        assertTrue(session.active)
        assertEquals(A, session.previewUri.toString())
        session.cancel()
        assertTrue(LockEngineHostRegistry.snapshot().ready)
        assertEquals(nextTarget.state().ticket, LockEngineHostRegistry.snapshot().ticket)
    }

    @Test fun sameEngineRepeatedRegistrationDoesNotRetireExpectedAuthenticationHold() {
        val (token) = requestEdit()
        LockEngineHostRegistry.register(id, target)
        assertTrue(hold())
        assertTrue(field<Boolean>(engine, "routeAllowed"))
        val session = authenticate(token)
        assertTrue(session.active)
        session.cancel()
    }

    @Test fun synchronousLeaseRevocationRejectsAuthenticationBeforePostedMainCancel() {
        val homeBefore = home.load()
        val (token) = requestEdit("closing-edit")
        assertTrue(LockEditorBridge.beginAuthentication(token))
        // Binder CLOSE already revoked the lease; its resource-cleanup Main message has not run.
        requestAuthorized.set(false)
        assertFalse(LockEditorBridge.systemSucceeded(token, false))
        assertNull(LockEditorBridge.consumeAuthenticated(token))
        assertEquals(A, store.load()!!.currentId)
        assertEquals(homeBefore, home.load())
        assertFalse(hold())
    }

    @Test fun synchronousLeaseRevocationPreventsQueuedEditorSaveBeforeMainCleanup() {
        val session = authenticate(requestEdit("closing-save").first)
        val homeBefore = home.load()
        session.save(ImageTransform(rotationDegrees = 90f), 1L)
        assertTrue("The save coroutine has not run on controlled Main yet", hold())
        requestAuthorized.set(false)
        dispatcher.drain()
        assertNull(store.loadImageTransform(A))
        assertEquals(homeBefore, home.load())
        assertFalse(hold())
    }

    @Test fun synchronousLeaseRevocationRejectsQueuedPresentedReceiptWithoutWaitingForCancel() {
        val results = mutableListOf<String>()
        set(engine, "loadGeneration", 8L)
        nativeRequest("closing-native", 8L, initial, results)
        engine.onImagePresented(A, initial)
        requestAuthorized.set(false)
        dispatcher.drain()
        assertFalse(results.contains("COMMITTED"))
        assertEquals(A, store.load()!!.currentId)
    }

    private fun replacement(): ShimmerWallpaperService.ShimmerWallpaperEngine {
        val next = service.get().onCreateEngine() as ShimmerWallpaperService.ShimmerWallpaperEngine
        replacements += next
        field<CoroutineScope>(next, "scope").cancel()
        set(next, "scope", CoroutineScope(SupervisorJob() + dispatcher))
        set(next, "lockRequestAuthorization", { _: String -> requestAuthorized.get() })
        set(next, "diagnosticAlive", true)
        set(next, "observedFlags", 2)
        set(next, "routeAllowed", true)
        set(next, "rendererReady", true)
        set(next, "engineVisible", true)
        set(next, "surfaceDimensions", SurfaceDimensions(100, 200))
        val samePicture = image(A)
        set(next, "cachedImageSet", samePicture)
        set(next, "currentImageUri", Uri.parse(A))
        set(next, "lockPresentationToken", samePicture)
        set(next, "lastPresentedLockToken", samePicture)
        set(next, "displayedTransform", ImageTransform())
        LockEngineHostRegistry.register(field(next, "diagnosticId"), field(next, "lockHostTarget"))
        return next
    }

    private fun nativeRequest(requestId: String, generation: Long, token: Any, results: MutableList<String>) {
        val type = Class.forName("dev.abdus.apps.shimmer.NativeLockRequest")
        val callback: (LockHostResult) -> Unit = { results += it.status }
        val request = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(requestId, callback)
        set(request, "generation", generation)
        set(request, "expectedImage", A)
        set(request, "expectedToken", token)
        set(engine, "nativeLockRequest", request)
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(value: Any, name: String): T =
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as T
    private fun set(value: Any, name: String, data: Any?) {
        value.javaClass.getDeclaredField(name).apply { isAccessible = true; set(value, data) }
    }

    @Implements(WallpaperService.Engine::class)
    class FlagsShadow {
        companion object { var flags = 2 }
        @Implementation fun isPreview() = false
        @Implementation fun getWallpaperFlags() = flags
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        fun pending() = queue.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runOne() { checkNotNull(queue.poll()) { "Expected queued Engine work" }.run() }
        fun drain() {
            var count = 0
            while (true) {
                val next = queue.poll() ?: return
                check(count++ < 200) { "Unexpected unbounded host work" }
                next.run()
            }
        }
    }

    private companion object {
        const val A = "content://production-lock/A"
        const val B = "content://production-lock/B"
    }
}
