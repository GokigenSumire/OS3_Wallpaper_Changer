package dev.abdus.apps.shimmer.p0

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageSet
import dev.abdus.apps.shimmer.ShimmerWallpaperService
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p3.EditHoldGate
import dev.abdus.apps.shimmer.p3.ImageTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
import org.robolectric.annotation.RealObject
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.time.Duration

/** Runs actual Service Engine callbacks; only Android attach fields are hosted. No EGL/frame claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [P0InputObservationTest.EngineHostShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class P0InputObservationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val playbackEngines = mutableListOf<ShimmerWallpaperService.ShimmerWallpaperEngine>()
    private val probes = mutableListOf<P0WallpaperService.ProbeEngine>()
    private lateinit var playback: ServiceController<ShimmerWallpaperService>
    private lateinit var probe: ServiceController<P0WallpaperService>
    private val surfaceTexture = SurfaceTexture(0)
    private val surface = Surface(surfaceTexture)
    private val holder: SurfaceHolder = Proxy.newProxyInstance(
        SurfaceHolder::class.java.classLoader, arrayOf(SurfaceHolder::class.java),
    ) { _, method, _ -> when (method.name) {
        "getSurface" -> surface
        "getSurfaceFrame" -> Rect(0, 0, 1080, 2400)
        "isCreating" -> false
        else -> null
    } } as SurfaceHolder

    @Before fun startRun() {
        EngineHostShadow.clear()
        P0Diagnostics.resetAndStart()
        playback = Robolectric.buildService(ShimmerWallpaperService::class.java).create()
    }

    @After fun closeHosts() {
        playbackEngines.forEach { it.onDestroy() }
        probes.forEach { it.onDestroy() }
        if (::probe.isInitialized) probe.destroy()
        playback.destroy()
        P0Diagnostics.setEnabled(false)
        // Cancel owned callbacks before idling; never turn these host tests into GL work.
        shadowOf(Looper.getMainLooper()).idle()
        val db = ShimmerDatabase.getInstance(context)
        db.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        surface.release()
        surfaceTexture.release()
        EngineHostShadow.clear()
    }

    private fun engine(preview: Boolean = false): ShimmerWallpaperService.ShimmerWallpaperEngine {
        val engine = playback.get().onCreateEngine() as ShimmerWallpaperService.ShimmerWallpaperEngine
        playbackEngines.add(engine)
        EngineHostShadow.previews[engine] = preview
        // Constructor catalog initialization is genuine, but this test deliberately prevents
        // business jobs and renderer setup from turning a host callback into a GPU assertion.
        field<CoroutineScope>(engine, "scope").cancel()
        engine.onCreate(holder)
        return engine
    }

    private fun probeEngine(): P0WallpaperService.ProbeEngine {
        probe = Robolectric.buildService(P0WallpaperService::class.java).create()
        return (probe.get().onCreateEngine() as P0WallpaperService.ProbeEngine).also {
            probes.add(it); it.onCreate(holder)
        }
    }

    private fun scope(engine: ShimmerWallpaperService.ShimmerWallpaperEngine): String = field(engine, "diagnosticId")
    private fun snapshot(engine: ShimmerWallpaperService.ShimmerWallpaperEngine): DiagnosticEngineSnapshot =
        P0Diagnostics.snapshot().engines.single { it.state.scope == scope(engine) }
    private fun touch(engine: WallpaperService.Engine, action: Int = MotionEvent.ACTION_CANCEL) {
        MotionEvent.obtain(10, 20, action, 24f, 40f, 0).also { engine.onTouchEvent(it); it.recycle() }
    }

    @Test fun actualEditHoldEarlyReturnStillCountsReceivedAndIgnoredWithoutForwarding() {
        val engine = engine()
        field<EditHoldGate>(engine, "editHold").begin()
        touch(engine)
        val counts = snapshot(engine).counts
        assertEquals(1L, counts.received)
        assertEquals(0L, counts.forwarded)
        assertEquals(1L, counts.ignored)
        assertEquals(1L, counts.ignoredEditHold)
        assertEquals(1, EngineHostShadow.touchCalls[engine])
        assertTrue(P0Diagnostics.export().contains("reason=EDIT_HOLD"))
    }

    @Test fun normalActualCallbackForwardsOnceAndKeepsOriginalSuperCall() {
        val engine = engine()
        touch(engine)
        val counts = snapshot(engine).counts
        assertEquals(1L, counts.received)
        assertEquals(1L, counts.forwarded)
        assertEquals(0L, counts.ignored)
        assertEquals(1, EngineHostShadow.touchCalls[engine])
        assertEquals(1, P0Diagnostics.snapshot().events.count { it.contains("scope=${scope(engine)} ") && it.contains("event=TOUCH_ROUTE") })
        assertTrue(P0Diagnostics.export().contains("reason=P2_GESTURE"))
    }

    @Test fun formalAndPreviewCallbacksHaveSeparateEngineCountersAndIdentity() {
        val formal = engine()
        val preview = engine(preview = true)
        touch(formal); touch(preview); touch(preview)
        assertEquals(false, snapshot(formal).state.isPreview)
        assertEquals(true, snapshot(preview).state.isPreview)
        assertEquals("PLAYBACK", snapshot(formal).state.serviceType)
        assertEquals(1L, snapshot(formal).counts.received)
        assertEquals(2L, snapshot(preview).counts.received)
        assertNotEquals(scope(formal), scope(preview))
    }

    @Test fun tapSecondaryAndOtherCommandsKeepSuperResultAndDoNotTouchPlaybackOrEditor() {
        val engine = engine()
        val store = PlaybackSnapshotStore(context)
        val original = PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = listOf("one", "two"),
            currentId = "one", historyIds = listOf("one"), historyCursor = 0)
        store.save(original)
        val generation = field<Long>(engine, "loadGeneration")
        val extras = Bundle().apply { putString("private_fixture", "content://private/never-export-this") }
        val commands = listOf("android.wallpaper.tap", "android.wallpaper.secondaryTap", "android.wallpaper.drop")
        commands.forEach {
            assertSame(EngineHostShadow.result, engine.onCommand(it, 1, 2, 3, extras, true))
        }
        val counts = snapshot(engine).counts
        assertEquals(1L, counts.tapCommands); assertEquals(1L, counts.secondaryTapCommands); assertEquals(1L, counts.otherCommands)
        assertEquals(0L, counts.received)
        assertEquals(3, EngineHostShadow.commandCalls[engine])
        assertEquals(original, store.load())
        assertEquals(generation, field<Long>(engine, "loadGeneration"))
        assertFalse(field<EditHoldGate>(engine, "editHold").holding)
        assertNull(field<Any?>(engine, "editSession"))
        assertNull(field<Any?>(engine, "imageCycleJob"))
        assertFalse(P0Diagnostics.export().contains("never-export-this"))
        assertFalse(P0Diagnostics.export().contains("private_fixture"))
    }

    @Test fun stoppedDiagnosticsLeaveActualTouchAndCommandBusinessFlowButDoNotCount() {
        val engine = engine()
        P0Diagnostics.setEnabled(false)
        val before = snapshot(engine).counts
        touch(engine)
        engine.onCommand("android.wallpaper.tap", 0, 0, 0, null, false)
        assertEquals(before, snapshot(engine).counts)
        assertEquals(1, EngineHostShadow.touchCalls[engine])
        assertEquals(1, EngineHostShadow.commandCalls[engine])
    }

    @Test fun alreadyExistingActualEngineGetsNewRunBaselineWithoutFakeCreateOrRebuild() {
        val engine = engine()
        touch(engine)
        val id = scope(engine)
        val first = P0Diagnostics.captureToken()
        val next = P0Diagnostics.resetAndStart()
        assertNotEquals(first, next)
        val state = snapshot(engine)
        assertEquals(id, state.state.scope)
        assertEquals(true, state.state.alive)
        assertEquals(true, state.state.touchRequested)
        assertEquals(0L, state.counts.received)
        assertTrue(P0Diagnostics.export().contains("RESET_BASELINE"))
        assertFalse(P0Diagnostics.export().contains("P2_ENGINE_CREATE"))
        touch(engine)
        assertEquals(1L, snapshot(engine).counts.received)
    }

    @Test fun actualSurfaceAndVisibilityCallbacksPublishKnownValuesWithoutClaimingEgl() {
        val engine = engine()
        assertNull(snapshot(engine).state.surfaceValid)
        assertNull(snapshot(engine).state.width)
        // Observe visibility with no GL holder attached, then keep the real GL owner hidden
        // throughout Surface callbacks. This is lifecycle metadata, not EGL availability.
        engine.onVisibilityChanged(true)
        assertEquals(true, snapshot(engine).state.visible)
        engine.onVisibilityChanged(false)
        engine.onSurfaceCreated(holder)
        engine.onSurfaceChanged(holder, 0, 1080, 2400)
        val shown = snapshot(engine).state
        assertEquals(false, shown.visible)
        assertEquals(surface.isValid, shown.surfaceValid)
        assertEquals(1080, shown.width); assertEquals(2400, shown.height)
        assertEquals(false, shown.rendererReady)
        engine.onSurfaceDestroyed(holder)
        assertEquals(false, snapshot(engine).state.visible)
        assertEquals(false, snapshot(engine).state.surfaceValid)
        assertEquals(false, snapshot(engine).state.rendererReady)
    }

    @Test fun actualDestroyedEngineIsNotSampledAsAliveInLaterBaseline() {
        val engine = engine()
        val id = scope(engine)
        engine.onDestroy(); playbackEngines.remove(engine)
        assertEquals(false, snapshot(engine).state.alive)
        P0Diagnostics.resetAndStart()
        assertFalse(P0Diagnostics.snapshot().engines.any { it.state.scope == id })
    }

    @Test fun diagnosisResetPreservesRealManualHistoryTransformAndActualHold() {
        val engine = engine()
        val store = PlaybackSnapshotStore(context)
        val original = PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = listOf("one", "two"),
            currentId = "two", historyIds = listOf("one", "two"), historyCursor = 1,
            sourcePositions = mapOf("root" to "two"))
        store.save(original)
        store.commitImageTransform("two", ImageTransform(userScale = 2f))
        val before = store.load()
        field<EditHoldGate>(engine, "editHold").begin()
        val generation = field<Long>(engine, "loadGeneration")
        val renderer = field<Any?>(engine, "renderer")
        P0Diagnostics.resetAndStart()
        assertTrue(field<EditHoldGate>(engine, "editHold").holding)
        assertEquals(before, store.load())
        assertEquals(ImageTransform(userScale = 2f), store.loadImageTransform("two"))
        assertEquals(generation, field<Long>(engine, "loadGeneration"))
        assertSame(renderer, field<Any?>(engine, "renderer"))
    }

    @Test fun actualBeginAndCancelEditPublishHoldStateWithoutDiagnosticResetChangingSession() {
        val engine = engine()
        set(engine, "rendererReady", true)
        engine.onSurfaceDimensionsChanged(1080, 2400)
        set(engine, "cachedImageSet", ImageSet(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), id = "one"))
        val session = engine.beginEdit()!!
        assertEquals(true, snapshot(engine).state.editHold)
        P0Diagnostics.resetAndStart()
        assertEquals(true, snapshot(engine).state.editHold)
        assertTrue(session.active)
        session.cancel()
        assertEquals(false, snapshot(engine).state.editHold)
        assertFalse(session.active)
    }

    @Test fun p0ProbeIsClearlySeparateAndUsesItsActualExistingVisibilityGate() {
        val engine = probeEngine()
        val id = field<String>(engine, "scopeId")
        touch(engine)
        val ignored = P0Diagnostics.snapshot().engines.single { it.state.scope == id }
        assertEquals("P0_PROBE", ignored.state.serviceType)
        assertEquals(1L, ignored.counts.received)
        assertEquals(1L, ignored.counts.ignored)
        engine.onVisibilityChanged(true)
        touch(engine)
        val forwarded = P0Diagnostics.snapshot().engines.single { it.state.scope == id }
        assertEquals(2L, forwarded.counts.received)
        assertEquals(1L, forwarded.counts.forwarded)
        assertTrue(P0Diagnostics.export().contains("reason=P0_GESTURE"))
    }

    @Test fun p0ProbeCommandsAreObservedButDoNotLaunchEditorOrChangeFixture() {
        val engine = probeEngine()
        val id = field<String>(engine, "scopeId")
        val generation = field<Int>(engine, "loadGeneration")
        assertSame(EngineHostShadow.result, engine.onCommand("android.wallpaper.secondaryTap", 4, 5, 6, null, false))
        val observed = P0Diagnostics.snapshot().engines.single { it.state.scope == id }
        assertEquals(1L, observed.counts.secondaryTapCommands)
        assertEquals(0L, observed.counts.received)
        assertEquals(generation, field<Int>(engine, "loadGeneration"))
        assertNull(shadowOf(probe.get()).nextStartedActivity)
    }

    @Test fun delayedP0RendererSampleFromOldRunCannotAppendToNewRunButOwnerCacheStaysCurrent() {
        val engine = probeEngine()
        val id = field<String>(engine, "scopeId")
        engine.onRendererReady()
        P0Diagnostics.resetAndStart()
        shadowOf(Looper.getMainLooper()).idle()
        val observed = P0Diagnostics.snapshot().engines.single { it.state.scope == id }
        assertEquals(true, observed.state.rendererReady)
        assertFalse(P0Diagnostics.snapshot().events.any { it.contains("source=OWNER_RENDERER_READY") })
    }

    @Test fun delayedP0RendererSampleCannotResurrectDestroyedEngine() {
        val engine = probeEngine()
        val id = field<String>(engine, "scopeId")
        engine.onRendererReady()
        engine.onDestroy(); probes.remove(engine)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, P0Diagnostics.snapshot().engines.single { it.state.scope == id }.state.alive)
        P0Diagnostics.resetAndStart()
        assertFalse(P0Diagnostics.snapshot().engines.any { it.state.scope == id })
    }

    @Test fun actualQueuedP0ScreenSamplesCannotCrossResetIntoTheNewDiagnosticRun() {
        probeEngine()
        val receiver = field<BroadcastReceiver>(probe.get(), "screenSignal")
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertTrue(P0Diagnostics.export().contains("signal=SCREEN_ON"))
        P0Diagnostics.resetAndStart()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertFalse(P0Diagnostics.export().contains("signal=SCREEN_ON"))
        assertFalse(P0Diagnostics.export().contains("SCREEN_ON_AFTER"))
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(engine: Any, name: String): T =
        engine.javaClass.getDeclaredField(name).run { isAccessible = true; get(engine) as T }
    private fun set(engine: Any, name: String, value: Any?) {
        engine.javaClass.getDeclaredField(name).apply { isAccessible = true; set(engine, value) }
    }

    /** Android host attach facts/super return only; no application callbacks are replaced. */
    @Implements(WallpaperService.Engine::class)
    class EngineHostShadow {
        @RealObject private lateinit var engine: WallpaperService.Engine
        @Implementation fun isPreview(): Boolean = previews[engine] ?: false
        // These callbacks exercise an attached HOME Engine. Modern route admission must receive
        // the real attach fact rather than accept Robolectric's unattached default/unknown flag.
        @Implementation(minSdk = 34) fun getWallpaperFlags(): Int = WallpaperManager.FLAG_SYSTEM
        @Implementation fun isVisible(): Boolean = false
        @Implementation fun setTouchEventsEnabled(enabled: Boolean) = Unit
        @Implementation fun onTouchEvent(event: MotionEvent) { touchCalls[engine] = (touchCalls[engine] ?: 0) + 1 }
        @Implementation fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle {
            commandCalls[engine] = (commandCalls[engine] ?: 0) + 1
            return result
        }
        companion object {
            val previews = IdentityHashMap<WallpaperService.Engine, Boolean>()
            val touchCalls = IdentityHashMap<WallpaperService.Engine, Int>()
            val commandCalls = IdentityHashMap<WallpaperService.Engine, Int>()
            val result = Bundle().apply { putInt("host_result", 7) }
            fun clear() { previews.clear(); touchCalls.clear(); commandCalls.clear() }
        }
    }
}
