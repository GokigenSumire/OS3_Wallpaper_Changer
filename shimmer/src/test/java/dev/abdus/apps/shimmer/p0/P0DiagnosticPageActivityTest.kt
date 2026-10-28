package dev.abdus.apps.shimmer.p0

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p3.ImageTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P0DiagnosticPageActivityTest {
    class OwnedApplicationViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application)
    private fun open() = Robolectric.buildActivity(P0DiagnosticsActivity::class.java).setup()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(activity: P0DiagnosticsActivity, text: String) =
        views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == text }

    @Test fun cancellingResetPreservesRunCountsRecordsAndEnabledState() {
        val controller = open(); val activity = controller.get()
        P0Diagnostics.resetAndStart()
        P0Diagnostics.event("APP", "TEST_MARK", "value=1")
        val before = P0Diagnostics.snapshot("SUMMARY_CACHE")
        button(activity, "清空并开始新诊断").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val after = P0Diagnostics.snapshot("SUMMARY_CACHE")
        assertEquals(before.token, after.token)
        assertEquals(before.events, after.events)
        assertEquals(before.enabled, after.enabled)
        assertEquals(before.engines, after.engines)
        controller.pause().stop().destroy()
    }

    @Test fun confirmationImmediatelyShowsNewRunAndOnlyClearsDiagnostics() {
        val controller = open(); val activity = controller.get()
        val store = PlaybackSnapshotStore(activity)
        val state = PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = listOf("a", "hidden", "b"),
            currentId = "a", historyIds = listOf("a"), historyCursor = 0)
        store.save(state)
        store.commitImageTransform("a", ImageTransform(userScale = 2f))
        val playbackBefore = store.load()
        P0Diagnostics.resetAndStart(); P0Diagnostics.event("APP", "OLD_DIAGNOSTIC_MARK")
        val old = P0Diagnostics.captureToken()
        button(activity, "清空并开始新诊断").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val snapshot = P0Diagnostics.snapshot("SUMMARY_CACHE")
        assertNotEquals(old, snapshot.token)
        assertTrue(snapshot.enabled)
        assertTrue(snapshot.events.any { it.contains("RUN_START") })
        assertFalse(snapshot.events.any { it.contains("OLD_DIAGNOSTIC_MARK") })
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains(snapshot.runId) })
        assertEquals(playbackBefore, store.load())
        assertEquals(2f, store.loadImageTransform("a")!!.userScale)
        controller.pause().stop().destroy()
    }

    @Test fun reopeningAndStoppingThePageNeverAutomaticallyResetOrEnable() {
        val first = open(); val activity = first.get()
        P0Diagnostics.resetAndStart()
        button(activity, "停止诊断").performClick()
        val stopped = P0Diagnostics.snapshot("SUMMARY_CACHE")
        first.pause().stop().destroy()
        val second = open()
        assertFalse(P0Diagnostics.enabled)
        assertEquals(stopped.token, P0Diagnostics.captureToken())
        assertEquals(stopped.events, P0Diagnostics.snapshot("SUMMARY_CACHE").events)
        second.pause().stop().destroy()
    }

    @Test fun exportPickerUsesPinnedRunFileNameAndDoesNotEnableOrClear() {
        val controller = open(); val activity = controller.get()
        P0Diagnostics.resetAndStart(); P0Diagnostics.setEnabled(false)
        val before = P0Diagnostics.snapshot("EXPORT_METADATA")
        button(activity, "导出当前诊断摘要与事件").performClick()
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        val pinned = androidx.lifecycle.ViewModelProvider(activity)[DiagnosticPageSession::class.java].takeExport()!!
        assertEquals(pinned.fileName, intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
        assertEquals(before.token, pinned.token)
        assertTrue(pinned.content.contains("diagnosticRunId=${before.runId}"))
        assertFalse(P0Diagnostics.enabled)
        assertEquals(before.token, P0Diagnostics.captureToken())
        assertEquals(before.events, P0Diagnostics.snapshot("SUMMARY_CACHE").events)
        controller.pause().stop().destroy()
    }

    @Test fun retainedExportSurvivesConfigurationChangeAndOldUiResultsAreRejected() {
        val controller = open(); val activity = controller.get()
        P0Diagnostics.resetAndStart()
        button(activity, "导出当前诊断摘要与事件").performClick()
        val page = androidx.lifecycle.ViewModelProvider(activity)[DiagnosticPageSession::class.java]
        assertTrue(page.hasPendingExport())
        controller.recreate()
        val recreated = controller.get()
        assertSame(page, androidx.lifecycle.ViewModelProvider(recreated)[DiagnosticPageSession::class.java])
        val oldToken = P0Diagnostics.captureToken()
        val version = page.displayVersion()
        P0Diagnostics.resetAndStart(); page.invalidateDisplay()
        assertFalse(page.acceptsDisplay(version, oldToken))
        assertEquals(oldToken, page.takeExport()!!.token)
        controller.pause().stop().destroy()
    }

    @Test fun cancellingTheActualFilePickerPreservesStoppedRunAndAllowsAnotherExport() {
        val controller = open(); val activity = controller.get()
        P0Diagnostics.resetAndStart(); P0Diagnostics.setEnabled(false)
        val before = P0Diagnostics.snapshot("EXPORT_METADATA")
        button(activity, "导出当前诊断摘要与事件").performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        activity.activityResultRegistry.dispatchResult(request.requestCode, android.app.Activity.RESULT_CANCELED, null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val page = androidx.lifecycle.ViewModelProvider(activity)[DiagnosticPageSession::class.java]
        assertFalse(page.hasPendingExport())
        assertEquals(before.token, P0Diagnostics.captureToken())
        assertFalse(P0Diagnostics.enabled)
        assertEquals(before.events, P0Diagnostics.snapshot("SUMMARY_CACHE").events)
        button(activity, "导出当前诊断摘要与事件").performClick()
        assertTrue(page.hasPendingExport())
        controller.pause().stop().destroy()
    }

    @Test fun singletonFactoryRetainsAnOldHostApplicationWhileOwnedFactoryUsesTheRequestedApplication() {
        val first = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val second = android.app.Application()
        val cached = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(first)
        assertSame(cached, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(second))
        val oldStore = androidx.lifecycle.ViewModelStore()
        val newStore = androidx.lifecycle.ViewModelStore()
        try {
            val stale = androidx.lifecycle.ViewModelProvider(oldStore, cached)[OwnedApplicationViewModel::class.java]
            assertNotSame(second, stale.getApplication<android.app.Application>())
            val owned = androidx.lifecycle.ViewModelProvider(newStore, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(second))[OwnedApplicationViewModel::class.java]
            assertSame(second, owned.getApplication<android.app.Application>())
        } finally { oldStore.clear(); newStore.clear() }
    }
}
