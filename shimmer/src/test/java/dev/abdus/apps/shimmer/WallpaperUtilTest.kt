package dev.abdus.apps.shimmer

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.Activity
import android.app.WallpaperManager
import android.app.WallpaperInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWallpaperManager
import dev.abdus.apps.shimmer.p10.LockWallpaperService
import dev.abdus.apps.shimmer.p10.WallpaperTarget

/**
 * Verifies the system-entry fallback chain without requiring a device or an OEM
 * wallpaper picker.  The target phone still needs the P2 handoff tests because
 * the final activity is supplied by HyperOS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WallpaperUtilTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetStatusQueryShadow() {
        ActiveStatusWallpaperManagerShadow.flags.clear()
        ActiveStatusWallpaperManagerShadow.homeQueries = 0
        ActiveStatusWallpaperManagerShadow.failure = null
    }

    @Test fun lockPreviewTargetsOnlyTheIndependentLockComponent() {
        val context = RecordingContext(base, failures = 0)
        assertEquals(WallpaperUtil.PickerLaunchResult.DIRECT_PREVIEW, WallpaperUtil.openWallpaperPicker(context, WallpaperTarget.LOCK))
        assertEquals(ComponentName(context, LockWallpaperService::class.java),
            context.started.single().getParcelableExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName::class.java))
        assertEquals(LockWallpaperService::class.java, WallpaperUtil.componentOf(WallpaperTarget.LOCK))
        assertEquals(ShimmerWallpaperService::class.java, WallpaperUtil.componentOf(WallpaperTarget.HOME))
    }

    @Test
    @Config(shadows = [ActiveStatusWallpaperManagerShadow::class])
    fun lockStatusUsesThePublicLockFlagAndAbsentInfoIsNotActive() {
        assertFalse(WallpaperUtil.isActiveWallpaper(base, WallpaperTarget.LOCK))
        assertEquals(listOf(WallpaperManager.FLAG_LOCK), ActiveStatusWallpaperManagerShadow.flags)
        assertEquals(0, ActiveStatusWallpaperManagerShadow.homeQueries)
    }

    @Test
    @Config(sdk = [33], shadows = [ActiveStatusWallpaperManagerShadow::class])
    fun oldApiLockStatusDoesNotCallAnUnavailableFlaggedMethodOrFallBackToHome() {
        assertFalse(WallpaperUtil.isActiveWallpaper(base, WallpaperTarget.LOCK))
        assertTrue(ActiveStatusWallpaperManagerShadow.flags.isEmpty())
        assertEquals(0, ActiveStatusWallpaperManagerShadow.homeQueries)
    }

    @Test
    @Config(shadows = [ActiveStatusWallpaperManagerShadow::class])
    fun rejectedLockStatusQueryIsFalseRatherThanCrashingOrClaimingActivation() {
        ActiveStatusWallpaperManagerShadow.failure = SecurityException("synthetic denial")
        assertFalse(WallpaperUtil.isActiveWallpaper(base, WallpaperTarget.LOCK))
        assertEquals(listOf(WallpaperManager.FLAG_LOCK), ActiveStatusWallpaperManagerShadow.flags)
        assertEquals(0, ActiveStatusWallpaperManagerShadow.homeQueries)
    }

    @Test
    @Config(shadows = [ActiveStatusWallpaperManagerShadow::class])
    fun unavailableWallpaperServiceCannotReportTheLockComponentAsActive() {
        ActiveStatusWallpaperManagerShadow.failure = RuntimeException("synthetic service failure")
        assertFalse(WallpaperUtil.isActiveWallpaper(base, WallpaperTarget.LOCK))
        assertEquals(listOf(WallpaperManager.FLAG_LOCK), ActiveStatusWallpaperManagerShadow.flags)
    }

    @Test
    @Config(shadows = [ActiveStatusWallpaperManagerShadow::class])
    fun homeStatusQueryKeepsItsOriginalApiAndFailsClosedOnServiceDenial() {
        ActiveStatusWallpaperManagerShadow.failure = SecurityException("synthetic denial")
        assertFalse(WallpaperUtil.isActiveWallpaper(base))
        assertEquals(1, ActiveStatusWallpaperManagerShadow.homeQueries)
        assertTrue(ActiveStatusWallpaperManagerShadow.flags.isEmpty())
    }

    @Implements(WallpaperManager::class)
    class ActiveStatusWallpaperManagerShadow : ShadowWallpaperManager() {
        @Implementation
        override fun getWallpaperInfo(): WallpaperInfo? {
            homeQueries++
            failure?.let { throw it }
            return null
        }

        @Implementation(minSdk = 34)
        fun getWallpaperInfo(which: Int): WallpaperInfo? {
            flags += which
            failure?.let { throw it }
            return null
        }

        companion object {
            val flags = mutableListOf<Int>()
            var homeQueries = 0
            var failure: RuntimeException? = null
        }
    }

    @Test
    fun directPreviewTargetsTheP2Service() {
        val context = RecordingContext(base, failures = 0)

        val result = WallpaperUtil.openWallpaperPicker(context)

        assertEquals(WallpaperUtil.PickerLaunchResult.DIRECT_PREVIEW, result)
        assertEquals(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, context.started[0].action)
        assertEquals(
            ComponentName(context, ShimmerWallpaperService::class.java),
            context.started[0].getParcelableExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName::class.java,
            ),
        )
        assertTrue(context.started[0].flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun chooserIsUsedWhenDirectPreviewIsUnavailable() {
        val context = RecordingContext(base, failures = 1)

        val result = WallpaperUtil.openWallpaperPicker(context)

        assertEquals(WallpaperUtil.PickerLaunchResult.LIVE_WALLPAPER_CHOOSER, result)
        assertEquals(2, context.started.size)
        assertEquals(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER, context.started[1].action)
    }

    @Test
    fun publicSetWallpaperActionIsFinalFallback() {
        val context = RecordingContext(base, failures = 2)

        val result = WallpaperUtil.openWallpaperPicker(context)

        assertEquals(WallpaperUtil.PickerLaunchResult.WALLPAPER_SETTINGS, result)
        assertEquals(Intent.ACTION_SET_WALLPAPER, context.started[2].action)
    }

    @Test
    fun failedLaunchIsReportedAfterAllRoutesFail() {
        val context = RecordingContext(base, failures = 3)

        val result = WallpaperUtil.openWallpaperPicker(context)

        assertEquals(WallpaperUtil.PickerLaunchResult.FAILED, result)
        assertEquals(3, context.started.size)
    }

    @Test
    fun securityRejectionFallsBackToGenericChooser() {
        val context = RecordingContext(base, failures = 1, failure = { SecurityException("rejected") })

        val result = WallpaperUtil.openWallpaperPicker(context)

        assertEquals(WallpaperUtil.PickerLaunchResult.LIVE_WALLPAPER_CHOOSER, result)
        assertEquals(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER, context.started.last().action)
    }

    @Test
    fun activityDoesNotReceiveNewTaskFlag() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()

            WallpaperUtil.openWallpaperPicker(activity)

            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, intent.action)
            assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun explicitChooserEntryBypassesDirectPreview() {
        val context = RecordingContext(base, failures = 0)

        val result = WallpaperUtil.openSystemWallpaperChooser(context)

        assertEquals(WallpaperUtil.PickerLaunchResult.LIVE_WALLPAPER_CHOOSER, result)
        assertEquals(listOf(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER), context.started.map { it.action })
        assertFalse(context.started.single().hasExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT))
    }

    @Test
    fun explicitChooserReportsFailureWhenBothPublicRoutesAreRejected() {
        val context = RecordingContext(base, failures = 2)

        val result = WallpaperUtil.openSystemWallpaperChooser(context)

        assertEquals(WallpaperUtil.PickerLaunchResult.FAILED, result)
        assertEquals(
            listOf(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER, Intent.ACTION_SET_WALLPAPER),
            context.started.map { it.action },
        )
    }

    @Test
    fun appDetailsTargetsTheCurrentPackage() {
        val context = RecordingContext(base, failures = 0)

        assertTrue(WallpaperUtil.openAppDetails(context))

        val intent = context.started.single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package", intent.data?.scheme)
        assertEquals(base.packageName, intent.data?.schemeSpecificPart)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun appDetailsReportsSecurityRejection() {
        val context = RecordingContext(base, failures = 1, failure = { SecurityException("rejected") })

        assertFalse(WallpaperUtil.openAppDetails(context))
        assertEquals(1, context.started.size)
    }

    @Test(expected = IllegalStateException::class)
    fun unexpectedProgrammingFailureIsNotSwallowed() {
        val context = RecordingContext(base, failures = 1, failure = { IllegalStateException("unexpected") })

        WallpaperUtil.openWallpaperPicker(context)
    }

    private class RecordingContext(
        base: Context,
        private val failures: Int,
        private val failure: () -> RuntimeException = { ActivityNotFoundException() },
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            started += intent
            if (started.size <= failures) throw failure()
        }
    }
}
