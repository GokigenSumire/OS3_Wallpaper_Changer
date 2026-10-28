package dev.abdus.apps.shimmer.p10.input

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p2.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实偏好存储往返，不把宿主状态机通过冒充手机GPU或物理手势通过。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockHistoryRoundTripTest {
    @Test fun allFourOrdersKeepTheCursorAcrossRepeatedRestoreAndDirectionChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
        val home = PlaybackSnapshotStore(context, WallpaperTarget.HOME)
        val homeOriginal = PlaybackSnapshot(currentId = "HOME", historyIds = listOf("HOME"), historyCursor = 0)
        home.save(homeOriginal)
        val items = (1..5).map { PlaybackItem("LOCK-$it", "$it.png", sourceId = "source") }
        for (order in PlaybackOrder.entries) {
            lock.save(PlaybackSnapshot(order = order, queueIds = if (order == PlaybackOrder.MANUAL) items.reversed().map { it.id } else emptyList()))
            fun step(previous: Boolean): PlaybackSnapshot {
                val stored = lock.load()!!
                val coordinator = PlaybackCoordinator(42L)
                coordinator.setItemsAndRestore(items, stored)
                val request = if (previous) coordinator.requestPrevious() else coordinator.requestNext()
                assertNotNull(request.item)
                assertTrue(coordinator.commit(request.version))
                assertTrue(lock.compareAndSave(stored, coordinator.snapshot()))
                return lock.load()!!
            }
            repeat(12) { step(false) }
            val original = lock.load()!!
            assertEquals(12, original.historyIds.size)
            for (cursor in original.historyCursor - 1 downTo 0) {
                val returned = step(true)
                assertEquals("$order cursor=$cursor", original.historyIds[cursor], returned.currentId)
                assertEquals(cursor, returned.historyCursor)
                assertEquals(original.historyIds, returned.historyIds)
            }
            val boundary = step(true)
            assertEquals(0, boundary.historyCursor)
            assertEquals(original.historyIds[0], boundary.currentId)
            for (cursor in 1..original.historyCursor) {
                val forwarded = step(false)
                assertEquals(original.historyIds[cursor], forwarded.currentId)
                assertEquals(cursor, forwarded.historyCursor)
                assertEquals(original.historyIds, forwarded.historyIds)
            }
            assertEquals(homeOriginal, home.load())
        }
    }
}
