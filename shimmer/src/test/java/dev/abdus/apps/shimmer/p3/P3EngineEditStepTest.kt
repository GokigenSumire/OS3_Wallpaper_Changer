package dev.abdus.apps.shimmer.p3

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.service.wallpaper.WallpaperService
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageSet
import dev.abdus.apps.shimmer.ShimmerWallpaperService
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Real private Engine session and preference writes; no Surface/EGL or GPU presentation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [P3EngineEditStepTest.FormalEngineShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class P3EngineEditStepTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = ShimmerDatabase.getInstance(context)
    private val store = PlaybackSnapshotStore(context)
    private val dispatcher = QueuedDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var service: ServiceController<ShimmerWallpaperService>
    private lateinit var engine: ShimmerWallpaperService.ShimmerWallpaperEngine
    private val one = "file:///edit-step-one.png"
    private lateinit var session: LiveEditBridge.EditSession
    private val done = mutableListOf<LiveEditBridge.StepResult?>()

    @Before fun open() {
        runBlocking {
            val dao = db.p1SourceDao()
            dao.registerSource(P1SourceEntity("source", "file:///edit-step", "DIRECTORY_TREE"))
            dao.commitScan("source", listOf(P1ImageRecordEntity("one", one, "one.png", "one.png")),
                "APP_OWNED", "2026-10-12T00:00:00Z")
        }
        store.save(PlaybackSnapshot(currentId = one, historyIds = listOf(one), historyCursor = 0))
        service = Robolectric.buildService(ShimmerWallpaperService::class.java).create()
        engine = service.get().onCreateEngine() as ShimmerWallpaperService.ShimmerWallpaperEngine
        val original = field<CoroutineScope>("scope")
        original.cancel()
        waitUntil { original.coroutineContext[Job]!!.isCompleted }
        set("scope", scope)
        set("rendererReady", true)
        set("engineVisible", true)
        set("currentImageUri", Uri.parse(one))
        set("cachedImageSet", ImageSet(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), id = one))
        val type = engine.javaClass.declaredClasses.single { it.simpleName == "EngineEditSession" }
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        session = constructor.newInstance(engine, LiveEditBridge.EditImage(
            one, 100, 200, 100, 200, ImageTransform(), P3Defaults(), true,
        )) as LiveEditBridge.EditSession
        set("editSession", session)
    }

    @After fun close() {
        if (::engine.isInitialized) engine.onDestroy()
        scope.cancel()
        while (dispatcher.runOne()) Unit
        shadowOf(Looper.getMainLooper()).idle()
        if (::service.isInitialized) service.destroy()
        db.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }

    @Test fun saveResetAndStepActuallyRemovesStoredConfigurationBeforeDone() {
        store.commitImageTransform(one, ImageTransform(userScale = 2f))
        session.step(true, true, null) { done.add(it) }
        waitUntil { done.isNotEmpty() }
        assertNull(store.loadImageTransform(one))
        assertEquals(one, store.load()!!.currentId)
        assertEquals(listOf(one), store.load()!!.historyIds)
        assertTrue(session.active)
        assertEquals(LiveEditBridge.StepResult.NoCandidate, done.single())
    }

    @Test fun saveAndStepActuallyPersistsDraftBeforeAttemptingTheNextCandidate() {
        val draft = ImageTransform(userScale = 1.7f, offsetX = 0.3f)
        session.step(false, true, draft) { done.add(it) }
        waitUntil { done.isNotEmpty() }
        assertEquals(draft, store.loadImageTransform(one))
        assertEquals(one, session.image.imageId)
        assertEquals(listOf(one), store.load()!!.historyIds)
    }

    @Test fun discardAndStepDoesNotDeleteTheSavedImageConfiguration() {
        val saved = ImageTransform(userScale = 1.4f)
        store.commitImageTransform(one, saved)
        session.step(true, false, null) { done.add(it) }
        waitUntil { done.isNotEmpty() }
        assertEquals(saved, store.loadImageTransform(one))
    }

    @Test fun cancelBeforeQueuedStepStartsCannotWriteResetOrAdvanceHistory() {
        val saved = ImageTransform(userScale = 1.4f)
        store.commitImageTransform(one, saved)
        val before = store.load()
        session.step(true, true, null) { done.add(it) }
        val task = sessionField<Job>("stepJob")
        val generation = field<Long>("loadGeneration")
        session.cancel()
        waitUntil { task.isCompleted }
        assertTrue(task.isCancelled)
        assertFalse(session.active)
        assertTrue(field<Long>("loadGeneration") > generation)
        assertEquals(saved, store.loadImageTransform(one))
        assertEquals(before, store.load())
    }

    @Test fun inFlightStepRejectsAnotherStepSaveApplyAndPreviewButKeepsCancelReachable() {
        val saved = ImageTransform(userScale = 1.4f)
        store.commitImageTransform(one, saved)
        session.step(true, false, null) { done.add(it) }
        val second = mutableListOf<LiveEditBridge.StepResult?>()
        session.step(false, true, null) { second.add(it) }
        session.save(null, 0)
        session.apply(null)
        session.preview(ImageTransform(userScale = 3f), 0)
        assertEquals(listOf(null), second)
        assertTrue(session.active)
        assertNull(sessionField<ImageTransform?>("draft"))
        assertEquals(saved, store.loadImageTransform(one))
        session.cancel()
        assertFalse(session.active)
    }

    @Test fun cancelWhileCoordinatorWaitsPreventsTheOldStepFromCommitting() {
        val lock = field<Mutex>("playbackMutex")
        runBlocking { lock.lock() }
        val before = store.load()
        val shown = field<ImageSet>("cachedImageSet")
        session.step(true, false, null) { done.add(it) }
        val task = sessionField<Job>("stepJob")
        assertTrue(dispatcher.runOne())
        // Complete the IO catalog read, then run its Main continuation into the held mutex.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (dispatcher.count() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(dispatcher.runOne())
        assertTrue(task.isActive)
        session.cancel()
        lock.unlock()
        waitUntil { task.isCompleted }
        assertTrue(task.isCancelled)
        assertEquals(before, store.load())
        assertSame(shown, field<ImageSet>("cachedImageSet"))
        assertFalse(session.active)
    }

    @Test fun invalidatingTheSessionCancelsItsOwnedStepAndRejectsNewRequests() {
        session.step(true, false, null) { done.add(it) }
        val task = sessionField<Job>("stepJob")
        session.javaClass.getDeclaredMethod("invalidate").apply { isAccessible = true }.invoke(session)
        waitUntil { task.isCompleted }
        assertFalse(session.active)
        assertTrue(task.isCancelled)
        val rejected = mutableListOf<LiveEditBridge.StepResult?>()
        session.step(true, true, null) { rejected.add(it) }
        assertEquals(listOf(null), rejected)
    }

    @Test fun cancellingWhileRealPreferenceLockIsHeldCannotPersistTheWaitingReset() {
        val saved = ImageTransform(userScale = 1.4f)
        store.commitImageTransform(one, saved)
        val before = store.load()
        val lock = PlaybackSnapshotStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) {
                holding.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        holder.start()
        assertTrue(holding.await(10, TimeUnit.SECONDS))
        try {
            session.step(true, true, null) { done.add(it) }
            val task = sessionField<Job>("stepJob")
            assertTrue(dispatcher.runOne())
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && Thread.getAllStackTraces().none { (thread, stack) ->
                thread.state == Thread.State.BLOCKED && stack.any { it.methodName == "commitImageTransformIfAllowed" }
            }) Thread.sleep(5)
            assertTrue("真实IO必须停在偏好提交锁上，不用排队前取消冒此窗口", Thread.getAllStackTraces().any { (thread, stack) ->
                thread.state == Thread.State.BLOCKED && stack.any { it.methodName == "commitImageTransformIfAllowed" }
            })
            session.cancel()
            release.countDown()
            holder.join(10_000)
            waitUntil { task.isCompleted }
            assertTrue(task.isCancelled)
            assertEquals(saved, store.loadImageTransform(one))
            assertEquals(before, store.load())
        } finally {
            release.countDown()
            holder.join(10_000)
        }
    }

    @Test fun closedSessionRejectsPendingCandidateCasInsideTheSameRealPreferenceLock() {
        val before = store.load()!!
        val candidate = before.copy(currentId = "file:///candidate.png", historyIds = before.historyIds + "file:///candidate.png", historyCursor = 1)
        val lock = checkNotNull(PlaybackSnapshotStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null))
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { synchronized(lock) { holding.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        var accepted: Boolean? = null
        val task = Job()
        val writer = Thread { accepted = store.compareAndSaveIfAllowed(before, candidate) { task.isActive && session.active } }
        holder.start()
        assertTrue(holding.await(10, TimeUnit.SECONDS))
        try {
            writer.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.BLOCKED, writer.state)
            session.cancel()
            task.cancel()
            release.countDown()
            holder.join(10_000)
            writer.join(10_000)
            assertFalse(writer.isAlive)
            assertEquals(false, accepted)
            assertEquals(before, store.load())
        } finally {
            release.countDown()
            holder.join(10_000)
            writer.join(10_000)
        }
    }

    @Test fun admittedCandidateCasStillRequiresExactLatestSnapshotAndKeepsOrdinaryCasContract() {
        val before = store.load()!!
        val candidate = before.copy(currentId = "file:///candidate.png", historyIds = before.historyIds + "file:///candidate.png", historyCursor = 1)
        assertFalse(store.compareAndSaveIfAllowed(before, candidate) { false })
        assertEquals(before, store.load())
        assertTrue(store.compareAndSaveIfAllowed(before, candidate) { session.active })
        assertEquals(candidate, store.load())
        assertFalse(store.compareAndSaveIfAllowed(before, before) { true })
        assertEquals(candidate, store.load())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            while (dispatcher.runOne()) Unit
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        assertTrue("可控真实Engine步骤应在等待内结束", condition())
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(name: String): T =
        engine.javaClass.getDeclaredField(name).run { isAccessible = true; get(engine) as T }
    @Suppress("UNCHECKED_CAST") private fun <T> sessionField(name: String): T =
        session.javaClass.getDeclaredField(name).run { isAccessible = true; get(session) as T }
    private fun set(name: String, value: Any?) { engine.javaClass.getDeclaredField(name).apply { isAccessible = true; set(engine, value) } }
    @Implements(WallpaperService.Engine::class) class FormalEngineShadow {
        @Implementation fun isPreview(): Boolean = false
    }
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runOne(): Boolean { val next = queue.poll() ?: return false; next.run(); return true }
        fun count(): Int = queue.size
    }
}
