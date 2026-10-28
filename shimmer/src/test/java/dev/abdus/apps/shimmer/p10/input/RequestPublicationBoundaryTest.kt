package dev.abdus.apps.shimmer.p10.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p2.StoreCommitResult
import dev.abdus.apps.shimmer.p3.ImageTransform
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Store + authority-monitor ordering. No test claims SharedPreferences.apply is fsync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestPublicationBoundaryTest {
    private lateinit var context: Context
    private lateinit var store: PlaybackSnapshotStore
    private lateinit var leases: RequestLeaseLedger
    private val before = PlaybackSnapshot(historyIds = listOf("a"), historyCursor = 0, currentId = "a")
    private val after = PlaybackSnapshot(historyIds = listOf("a", "b"), historyCursor = 1, currentId = "b")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PlaybackSnapshotStore.LOCK_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        store.save(before)
        leases = RequestLeaseLedger()
        assertTrue(leases.grant("owner", "request", 15000))
    }

    private fun workGuard(write: () -> StoreCommitResult): StoreCommitResult =
        leases.withAuthorization("request", 1, write) ?: StoreCommitResult(false)

    @Test fun closeBeforePublicationRejectsSnapshotAndTransformWithoutChangingCommittedState() {
        leases.revokeOwner("owner")
        val result = store.compareAndSaveIfAllowedWithWork(before, after, ::workGuard) { true }
        val transformed = store.commitImageTransformIfAllowed("a", ImageTransform(),
            { write -> leases.withAuthorization("request", 1, write) ?: false }) { true }
        assertFalse(result.accepted)
        assertFalse(transformed)
        assertEquals(before, store.load())
        assertNull(store.loadImageTransform("a"))
    }

    @Test fun publicationHoldingLeaseFinishesLogicallyBeforeCloseAcknowledges() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val written = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val order = Collections.synchronizedList(mutableListOf<String>())
        Thread {
            try {
                val result = store.compareAndSaveIfAllowedWithWork(before, after,
                    { write -> leases.withAuthorization("request", 1) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        write().also { order.add("published") }
                    } ?: StoreCommitResult(false) }) { true }
                check(result.accepted)
            } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            finally { written.countDown() }
        }.start()
        val closer = Thread {
            try {
                check(entered.await(5, TimeUnit.SECONDS))
                closing.countDown()
                leases.revokeOwner("owner")
                order.add("close-ack")
            } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            finally { closed.countDown() }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(closed.await(50, TimeUnit.MILLISECONDS))
            assertEquals(Thread.State.BLOCKED, closer.state)
        } finally { release.countDown() }
        assertTrue(written.await(5, TimeUnit.SECONDS))
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals(listOf("published", "close-ack"), order.toList())
        assertEquals(after, store.load())
        assertFalse(leases.authorized("request", 1))
    }

    @Test fun concurrentStoreWorkAndCloseUseOneOrderAndCannotDeadlock() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)
        val failure = AtomicReference<Throwable?>(null)
        val tasks = listOf<() -> Unit>(
            { store.compareAndSaveIfAllowedWithWork(before, after, ::workGuard) { true }; Unit },
            { store.commitImageTransformIfAllowed("a", ImageTransform()) { true }; Unit },
            { leases.revokeOwner("owner") },
        )
        tasks.forEach { task -> Thread {
            try { check(start.await(5, TimeUnit.SECONDS)); task() }
            catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            finally { done.countDown() }
        }.start() }
        start.countDown()
        assertTrue("Store → lease与关闭并发应有界结束", done.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertTrue(store.load() == before || store.load() == after)
        assertEquals(ImageTransform(), store.loadImageTransform("a"))
        assertFalse(leases.authorized("request", 1))
    }

    @Test fun applyIsOnlyLogicalPublicationAndCloseDoesNotEraseAnEarlierCommit() {
        val result = store.compareAndSaveIfAllowedWithWork(before, after, ::workGuard) { true }
        assertTrue(result.accepted)
        assertEquals(after, store.load())
        assertNotNull(context.getSharedPreferences(PlaybackSnapshotStore.LOCK_NAME, Context.MODE_PRIVATE)
            .getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        leases.revokeOwner("owner")
        assertEquals(after, store.load())
        // No filesystem, journal or fsync assertion: delayed disk flush is not rolled back.
    }
}
