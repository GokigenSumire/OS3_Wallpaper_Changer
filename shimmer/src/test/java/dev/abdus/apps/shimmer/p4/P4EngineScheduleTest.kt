package dev.abdus.apps.shimmer.p4

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.service.wallpaper.WallpaperService
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageSet
import dev.abdus.apps.shimmer.ShimmerWallpaperService
import dev.abdus.apps.shimmer.SurfaceDimensions
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p3.EditHoldGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * The real formal Engine behind a test scheduler: every dispatch it receives is reported exactly
 * once. No Surface or EGL is created, so nothing here stands in for rendering or device lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [P4EngineScheduleTest.FormalEngineShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class P4EngineScheduleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = ShimmerDatabase.getInstance(context)
    private val store = ScheduleStore(context)
    private val env = FakeEnv()
    private val dispatcher = StepDispatcher()
    private val testScope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var service: ServiceController<ShimmerWallpaperService>
    private lateinit var engine: ShimmerWallpaperService.ShimmerWallpaperEngine
    private lateinit var coordinator: ScheduleCoordinator
    private var destroyed = false

    @Before
    fun seedCatalog() {
        context.getSharedPreferences(ScheduleStore.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        resetScheduleSingleton()
        runBlocking {
            val dao = database.p1SourceDao()
            dao.registerSource(P1SourceEntity("source", "file:///source", "DIRECTORY_TREE"))
            dao.commitScan("source", listOf("one", "two").map { name ->
                P1ImageRecordEntity(name, uri(name), "$name.png", "$name.png")
            }, "APP_OWNED", "2026-09-25T00:00:00Z")
        }
    }

    @After
    fun closeEngineAndDatabase() {
        try {
            if (::engine.isInitialized && !destroyed) engine.onDestroy()
        } finally {
            testScope.cancel()
            while (dispatcher.runOne()) Unit
            shadowOf(Looper.getMainLooper()).idle()
            if (::service.isInitialized) service.destroy()
            database.close()
            ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply {
                isAccessible = true
                set(null, null)
            }
            resetScheduleSingleton()
        }
    }

    @Test
    fun aSwitchTheEngineCannotShowIsDroppedAndRetriedAtMostTwice() {
        start(ScheduleSettings(onUnlock = true))
        // The scheduler sees a visible wallpaper, but the Engine's own flag is already false.
        coordinator.onVisibility(diagnosticId(), true)
        unlock()
        (1L..3L).forEach { seq ->
            assertEquals(listOf("DROPPED，壁纸不可见或渲染未就绪"), outcomes(seq))
        }
        assertTrue(store.loadLog().any { it.kind == EventKind.RETRY && it.detail == "连续 2 次未提交，放弃本次切换" })
        assertEquals(3L, store.loadState().seq)
    }

    @Test
    fun aSwitchParkedForTheSurfaceIsCancelledOnceWhenTheEngineIsDestroyed() {
        start(ScheduleSettings(onUnlock = true))
        setField("rendererReady", false)
        showEngine()
        unlock()
        assertNotNull(field<ScheduleDispatch?>("parkedDispatch"))
        assertTrue(outcomes(1L).isEmpty())

        engine.onDestroy()
        destroyed = true
        idle()
        assertEquals(listOf("CANCELLED，Engine 已销毁"), outcomes(1L))
        assertNull(field<ScheduleDispatch?>("parkedDispatch"))
        // The retry finds no formal Engine and waits for the next visible one.
        assertEquals(listOf("DROPPED，没有可见的正式 Engine"), outcomes(2L))
        assertEquals(setOf(ScheduleReason.UNLOCK), store.loadState().pending)
    }

    @Test
    fun aNewerScheduledSwitchWhileEditingReplacesTheHeldOneWhichRunsOnceAfter() {
        start(ScheduleSettings(onUnlock = true))
        showEngine()
        val session = requireNotNull(engine.beginEdit())
        unlock()
        assertEquals(listOf("HELD_EDIT，编辑结束后执行"), outcomes(1L))
        unlock()
        assertEquals(listOf("HELD_EDIT，编辑结束后执行", "SUPERSEDED，编辑中被更新的切换取代"), outcomes(1L))
        assertEquals(listOf("HELD_EDIT，编辑结束后执行"), outcomes(2L))

        session.cancel()
        assertFalse(field<EditHoldGate>("editHold").holding)
        assertTrue(store.loadLog().any { it.kind == EventKind.EDIT_END })
        // The held switch replays as a normal playback step that still owes seq 2's outcome.
        var steps = 0
        while (field<ScheduleDispatch?>("activeDispatch") == null && steps++ < 10) dispatcher.runOne()
        assertEquals(2L, field<ScheduleDispatch?>("activeDispatch")?.seq)
        assertEquals(listOf("HELD_EDIT，编辑结束后执行"), outcomes(2L))
    }

    @Test
    fun anIntervalDueWhileEditingIsDroppedNotHeld() {
        start(ScheduleSettings(interval = IntervalSettings(enabled = true, value = 10, unit = IntervalUnit.SECONDS)))
        showEngine()
        requireNotNull(engine.beginEdit())
        advance(10_000)
        assertEquals(listOf("DROPPED_EDIT"), outcomes(1L))
        assertNull(field<EditHoldGate>("editHold").deferredSwitch)
        assertTrue(store.loadLog().none { it.kind == EventKind.RETRY })
    }

    /** A formal Engine as after onCreate and a ready surface, registered with a test scheduler. */
    private fun start(settings: ScheduleSettings) {
        // Saved first: the app-wide scheduler the Engine constructs never sees a later change.
        store.saveSettings(settings)
        service = Robolectric.buildService(ShimmerWallpaperService::class.java).create()
        engine = service.get().onCreateEngine() as ShimmerWallpaperService.ShimmerWallpaperEngine
        val originalScope = field<CoroutineScope>("scope")
        originalScope.cancel()
        awaitCondition { originalScope.coroutineContext[Job]!!.isCompleted }
        setField("scope", testScope)
        setField("rendererReady", true)
        setField("engineVisible", false)
        setField("currentImageUri", Uri.parse(uri("one")))
        setField("cachedImageSet", ImageSet(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), id = uri("one")))
        setField("surfaceDimensions", SurfaceDimensions(1080, 2400))
        // Engine.onCreate would start the GL thread; register the way it does instead.
        coordinator = ScheduleCoordinator(context, env)
        setField("schedule", coordinator)
        coordinator.register(diagnosticId(), engine)
    }

    /** Visible without onVisibilityChanged, whose refresh would start a real catalog read. */
    private fun showEngine() {
        setField("engineVisible", true)
        coordinator.onVisibility(diagnosticId(), true)
    }

    private fun unlock() {
        env.advance(5_000)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
    }

    /** Moves both clocks together in small steps so the scheduler's tick runs at its own time. */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(100L, left)
            env.advance(step)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun outcomes(seq: Long): List<String> =
        store.loadLog().filter { it.kind == EventKind.OUTCOME && it.seq == seq }.map { it.detail }

    private fun diagnosticId(): String = field("diagnosticId")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            idle()
            Thread.sleep(5)
        }
        assertTrue("构造时的原作用域应在限时内结束", condition())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = engine.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(engine) as T
    }

    private fun setField(name: String, value: Any?) {
        engine.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(engine, value)
        }
    }

    private fun resetScheduleSingleton() {
        ScheduleCoordinator::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun uri(name: String) = "file:///$name.png"

    private class FakeEnv : ScheduleEnvironment {
        var elapsed = 7_000_000L
        var wall = 1_790_000_000_000L
        override fun now() = ScheduleNow(elapsed, wall, ZoneId.of("Asia/Shanghai"), 2, interactive = true, locked = false)
        fun advance(ms: Long) {
            elapsed += ms
            wall += ms
        }
    }

    /** Only replaces the formal/preview flag the system sets on attach; no app method is replaced. */
    @Implements(WallpaperService.Engine::class)
    class FormalEngineShadow {
        @Implementation
        fun isPreview(): Boolean = false
    }

    private class StepDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun runOne(): Boolean {
            val next = queue.poll() ?: return false
            next.run()
            return true
        }
    }
}
