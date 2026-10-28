package dev.abdus.apps.shimmer.p4

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchedulePageTargetTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun explicitLockEntryTargetsMatureSchedulePageAndCarriesExactDomain() {
        val intent = SchedulePageTarget.createIntent(app, WallpaperTarget.LOCK)
        assertEquals(ComponentName(app, P4ScheduleActivity::class.java), intent.component)
        assertEquals("LOCK", intent.getStringExtra(SchedulePageTarget.EXTRA_TARGET))
        assertEquals(WallpaperTarget.LOCK, SchedulePageTarget.read(intent))
    }

    @Test fun explicitHomeEntryAndOldUnscopedEntryRemainHome() {
        assertEquals(WallpaperTarget.HOME, SchedulePageTarget.read(SchedulePageTarget.createIntent(app, WallpaperTarget.HOME)))
        assertEquals(WallpaperTarget.HOME, SchedulePageTarget.read(Intent()))
    }

    @Test fun invalidOrEmptyExplicitDomainNeverFallsBackToHome() {
        listOf("", "lock", "SHARED", "UNKNOWN").forEach { value ->
            assertNull(SchedulePageTarget.read(Intent().putExtra(SchedulePageTarget.EXTRA_TARGET, value)))
        }
    }

    @Test fun explicitNullOrWrongTypeDomainIsRejectedWithoutFallback() {
        assertNull(SchedulePageTarget.read(Intent().putExtra(SchedulePageTarget.EXTRA_TARGET, null as String?)))
        assertNull(SchedulePageTarget.read(Intent().putExtra(SchedulePageTarget.EXTRA_TARGET, 2)))
    }
}
