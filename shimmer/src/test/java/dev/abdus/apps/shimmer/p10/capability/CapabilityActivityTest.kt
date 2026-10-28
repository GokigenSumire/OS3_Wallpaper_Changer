package dev.abdus.apps.shimmer.p10.capability

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p4.ScheduleStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CapabilityActivityTest {
    @Test fun openingCapabilityPageDoesNotLaunchSystemOrChangeProductionState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val beforeHome = PlaybackSnapshotStore(context).load()
        val beforeLock = PlaybackSnapshotStore(context, WallpaperTarget.LOCK).load()
        val beforeSchedule = ScheduleStore(context).loadSettings()
        val controller = Robolectric.buildActivity(CapabilityActivity::class.java).setup()
        try {
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            assertEquals(beforeHome, PlaybackSnapshotStore(context).load())
            assertEquals(beforeLock, PlaybackSnapshotStore(context, WallpaperTarget.LOCK).load())
            assertEquals(beforeSchedule, ScheduleStore(context).loadSettings())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun intentExtrasCannotAutoApplyPreviewExportOrEdit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, CapabilityActivity::class.java)
            .putExtra("cmd", "DAILY_ON")
            .putExtra("component", "LOCK")
            .putExtra("export", true)
            .putExtra("edit", true)
        val controller = Robolectric.buildActivity(CapabilityActivity::class.java, intent).setup()
        try { assertNull(shadowOf(controller.get()).nextStartedActivity) }
        finally { controller.pause().stop().destroy() }
    }
}
