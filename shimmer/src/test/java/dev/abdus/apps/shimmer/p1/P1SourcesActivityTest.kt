package dev.abdus.apps.shimmer.p1

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class P1SourcesActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = ShimmerDatabase.getInstance(context)
    private var controller: ActivityController<P1SourcesActivity>? = null

    @After
    fun closeActivityAndDatabase() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun exportingSnapshotWhileScanIsRunningDoesNotCancelOrReplaceScan() {
        controller = Robolectric.buildActivity(P1SourcesActivity::class.java).setup()
        val activity = controller!!.get()
        val releaseScan = CompletableDeferred<Unit>()
        var scanStarted = false
        var scanFinished = false
        startOperation(activity) {
            scanStarted = true
            releaseScan.await()
            scanFinished = true
        }
        awaitCondition { scanStarted }
        val scanJob = operation(activity)!!

        // Trigger the real CreateDocument result callback, including asynchronous Room export.
        click(activity, "导出 P1 数据快照（URI 脱敏）")
        val request = shadowOf(activity).nextStartedActivityForResult
        val exported = File.createTempFile("p1-export-during-scan-", ".json", context.cacheDir)
        assertTrue(activity.activityResultRegistry.dispatchResult(
            request.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(exported)),
        ))
        awaitCondition {
            texts(activity).any { it.contains("P1 数据快照已导出") }
        }

        assertTrue("The read-only export must leave the running scan active", scanJob.isActive)
        assertSame("Export must not replace the current scan operation", scanJob, operation(activity))
        assertTrue(exported.readText().contains("\"sources\""))
        releaseScan.complete(Unit)
        awaitCondition { scanFinished && scanJob.isCompleted }
        assertFalse("The original scan must finish normally after export", scanJob.isCancelled)
    }

    @Test
    fun explicitCancelButtonStillCancelsRunningScan() {
        controller = Robolectric.buildActivity(P1SourcesActivity::class.java).setup()
        val activity = controller!!.get()
        val releaseScan = CompletableDeferred<Unit>()
        startOperation(activity) { releaseScan.await() }
        val scanJob = operation(activity)!!

        click(activity, "取消当前扫描")

        awaitCondition { scanJob.isCompleted }
        assertTrue("Explicit cancellation must still cancel the scan", scanJob.isCancelled)
    }

    @Test
    fun subfolderRowsExpandOneLevelAndStoppingAParentCascadesWithoutDeletingImages() = runBlocking {
        val dao = seedDirectorySource(enabled = true)

        controller = Robolectric.buildActivity(P1SourcesActivity::class.java).setup()
        val activity = controller!!.get()
        awaitCondition { folderRows(activity).isNotEmpty() }

        // The added folder itself is the only row at first; its children stay hidden.
        assertEquals(listOf("▶ dir/ · 共 3 张 · 播放中"), rowTexts(activity))
        assertEquals("由来源开关控制", noteOf(folderRows(activity).single()))
        assertEquals(null, stopButtonOrNull(folderRows(activity).single()))
        folderRows(activity).single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("▼ dir/ · 共 3 张 · 播放中", "▶ 1/ · 共 2 张 · 播放中"), rowTexts(activity))
        assertTrue(texts(activity).any { it.startsWith("本层 1 张：") && it.contains("top.png") })
        folderRows(activity)[1].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(
                "▼ dir/ · 共 3 张 · 播放中",
                "▼ 1/ · 共 2 张 · 播放中",
                "▶ 1-1/ · 共 1 张 · 播放中",
                "▶ 1-2/ · 共 1 张 · 播放中",
            ),
            rowTexts(activity),
        )

        // Stopping 1/ cascades to its children, which then offer no control of their own.
        stopButtonOf(folderRows(activity)[1]).performClick()
        awaitCondition { rowTexts(activity)[1].endsWith("已停用") }
        assertEquals(
            listOf(
                "▼ dir/ · 共 3 张 · 播放中",
                "▼ 1/ · 共 2 张 · 已停用",
                "▶ 1-1/ · 共 1 张 · 受上级停用影响",
                "▶ 1-2/ · 共 1 张 · 受上级停用影响",
            ),
            rowTexts(activity),
        )
        folderRows(activity).drop(2).forEach { child ->
            assertEquals(null, stopButtonOrNull(child))
            assertEquals("由上级控制", noteOf(child))
        }
        assertEquals(setOf("1"), P1SourceRepository(context).excludedDirectories("dir"))
        // The exclusion is a playback filter, so the scanned images are still on record.
        assertEquals(3, dao.getSourceImages("dir").size)
    }

    @Test
    fun aChildStopSurvivesStoppingAndResumingItsParent() = runBlocking {
        seedDirectorySource(enabled = true)
        controller = Robolectric.buildActivity(P1SourcesActivity::class.java).setup()
        val activity = controller!!.get()
        awaitCondition { folderRows(activity).isNotEmpty() }
        folderRows(activity)[0].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        folderRows(activity)[1].performClick()
        shadowOf(Looper.getMainLooper()).idle()

        stopButtonOf(folderRows(activity)[2]).performClick()
        awaitCondition { rowTexts(activity)[2].endsWith("已停用") }
        stopButtonOf(folderRows(activity)[1]).performClick()
        awaitCondition { rowTexts(activity)[1].endsWith("已停用") }
        assertEquals("▶ 1-1/ · 共 1 张 · 受上级停用影响（本层也已停用）", rowTexts(activity)[2])
        assertEquals("▶ 1-2/ · 共 1 张 · 受上级停用影响", rowTexts(activity)[3])

        stopButtonOf(folderRows(activity)[1]).performClick()
        awaitCondition { rowTexts(activity)[1].endsWith("播放中") }
        assertEquals(
            listOf("▼ 1/ · 共 2 张 · 播放中", "▶ 1-1/ · 共 1 张 · 已停用", "▶ 1-2/ · 共 1 张 · 播放中"),
            rowTexts(activity).drop(1),
        )
        assertEquals(setOf("1/1-1"), P1SourceRepository(context).excludedDirectories("dir"))
    }

    @Test
    fun aDisabledSourceMarksEveryFolderAsControlledByTheSourceSwitch() = runBlocking {
        seedDirectorySource(enabled = false)
        controller = Robolectric.buildActivity(P1SourcesActivity::class.java).setup()
        val activity = controller!!.get()
        awaitCondition { folderRows(activity).isNotEmpty() }

        assertEquals(listOf("▶ dir/ · 共 3 张 · 来源已停用"), rowTexts(activity))
        folderRows(activity).single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("▼ dir/ · 共 3 张 · 来源已停用", "▶ 1/ · 共 2 张 · 受上级停用影响"), rowTexts(activity))
        assertEquals(null, stopButtonOrNull(folderRows(activity)[1]))
        assertEquals("由上级控制", noteOf(folderRows(activity)[1]))
    }

    private suspend fun seedDirectorySource(enabled: Boolean) = database.p1SourceDao().also { dao ->
        dao.registerSource(P1SourceEntity("dir", "file:///dir", "DIRECTORY_TREE", enabled = enabled))
        dao.commitScan("dir", listOf("1/1-1/a.png", "1/1-2/b.png", "top.png").map { path ->
            P1ImageRecordEntity(
                id = path,
                uri = "file:///dir/$path",
                displayName = path.substringAfterLast('/'),
                relativePath = path,
            )
        }, "APP_OWNED", "2026-09-28T00:00:00Z")
    }

    /** The full-width expand buttons of the source card's folder list. */
    private fun folderRows(activity: P1SourcesActivity): List<Button> =
        views(activity.window.decorView).filterIsInstance<Button>()
            .filter { it.text.startsWith("▶ ") || it.text.startsWith("▼ ") }
            .toList()

    private fun rowTexts(activity: P1SourcesActivity): List<String> = folderRows(activity).map { it.text.toString() }

    private fun stopButtonOrNull(row: Button): Button? =
        views(row.parent as View).filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == "停用" || it.text.toString() == "启用" }

    private fun stopButtonOf(row: Button): Button = requireNotNull(stopButtonOrNull(row))

    /** The plain text shown in place of a stop button, if any. */
    private fun noteOf(row: Button): String? =
        views(row.parent as View).filterIsInstance<TextView>()
            .firstOrNull { it !is Button }?.text?.toString()

    private fun startOperation(activity: P1SourcesActivity, work: suspend () -> Unit) {
        P1SourcesActivity::class.java.declaredMethods.single { it.name == "runOperation" }.apply {
            isAccessible = true
            invoke(activity, "测试中的目录扫描", work)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun operation(activity: P1SourcesActivity): Job? =
        P1SourcesActivity::class.java.getDeclaredField("operation").run {
            isAccessible = true
            get(activity) as Job?
        }

    private fun click(activity: P1SourcesActivity, label: String) {
        views(activity.window.decorView).filterIsInstance<Button>()
            .first { it.text.toString() == label }.performClick()
    }

    private fun texts(activity: P1SourcesActivity): Sequence<String> =
        views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val timeout = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < timeout)
        assertTrue("The expected activity operation did not finish", condition())
    }
}
