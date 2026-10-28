package dev.abdus.apps.shimmer.p4

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

/** P4 page: every change is saved at once and invalid input never reaches the scheduler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class P4ScheduleActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ScheduleStore(context)
    private var controller: ActivityController<P4ScheduleActivity>? = null

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(ScheduleStore.NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun closeActivityAndSingletons() {
        controller?.pause()?.stop()?.destroy()
        idle()
        ShimmerDatabase.getInstance(context).close()
        listOf(ShimmerDatabase::class.java to "INSTANCE", ScheduleCoordinator::class.java to "instance").forEach { (type, name) ->
            type.getDeclaredField(name).apply {
                isAccessible = true
                set(null, null)
            }
        }
    }

    @Test
    fun triggerTogglesAreSavedAtOnceAndReachTheScheduler() {
        val activity = open()
        click(activity, "暂停全部调度：未暂停（点击暂停）")
        assertTrue(store.loadSettings().pauseAll)
        assertTrue(texts(activity).contains("暂停全部调度：已暂停（点击恢复）"))
        // The page reads the coordinator back, so the store listener already applied the change.
        assertTrue(texts(activity).any { it.startsWith("调度：已暂停全部调度") })

        click(activity, "锁屏后切换（下一次可见时）：关")
        click(activity, "解锁时切换：关")
        val saved = store.loadSettings()
        assertTrue(saved.onLock && saved.onUnlock && !saved.onScreenOff)
        assertTrue(texts(activity).contains("锁屏后切换（下一次可见时）：开"))
    }

    @Test
    fun intervalOutsideTenSecondsToTwentyFourHoursIsRejected() {
        val activity = open()
        val value = views(activity.window.decorView).filterIsInstance<EditText>().single { it.hint == "间隔数值" }
        listOf("1441", "0", "").forEach { input ->
            value.setText(input)
            click(activity, "保存间隔")
            assertTrue(input, texts(activity).contains("间隔无效：范围为 10 秒～24 小时。"))
        }
        assertEquals(IntervalSettings(), store.loadSettings().interval)

        // Minutes -> hours -> seconds.
        click(activity, "单位：分钟（点击切换）")
        click(activity, "单位：小时（点击切换）")
        value.setText("5")
        click(activity, "保存间隔")
        assertEquals(IntervalSettings(), store.loadSettings().interval)
        value.setText("10")
        click(activity, "保存间隔")
        assertEquals(IntervalSettings(value = 10, unit = IntervalUnit.SECONDS), store.loadSettings().interval)
        assertEquals("已保存间隔：10 秒，重新计时", ShadowToast.getTextOfLatestToast())

        click(activity, "间隔切换：关（每 10 秒）")
        click(activity, "计时方式：可见累计（点击改为自然时间）")
        assertEquals(
            IntervalSettings(enabled = true, value = 10, unit = IntervalUnit.SECONDS, clock = IntervalClock.WALL),
            store.loadSettings().interval,
        )
    }

    @Test
    fun dailyTimeDialogStaysOpenOnBadInputAndRefusesDuplicates() {
        val activity = open()
        click(activity, "添加每日定时")
        val dialog = latestDialog()
        val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("25:00")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals("格式应为 HH:mm，例如 08:30", input.error?.toString())
        assertTrue(store.loadSettings().dailyTimes.isEmpty())

        input.setText("07:30")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        val daily = store.loadSettings().dailyTimes.single()
        assertEquals(7 * 60 + 30, daily.minuteOfDay)
        assertTrue(daily.enabled && daily.editedAtWall > 0L)
        assertTrue(texts(activity).contains("07:30（启用）"))

        click(activity, "添加每日定时")
        val again = latestDialog()
        views(again.window!!.decorView).filterIsInstance<EditText>().single().setText("7:30")
        again.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, store.loadSettings().dailyTimes.size)
        assertTrue(texts(activity).any { it.startsWith("已有 07:30 的定时，未重复添加。") })
    }

    @Test
    fun ruleEditorWithoutP1SourcesExplainsWhatToDoFirst() {
        val activity = open()
        val before = ShadowAlertDialog.getLatestAlertDialog()
        click(activity, "添加规则")
        assertTrue(texts(activity).contains("还没有 P1 来源；先在 P1 中添加来源，再创建规则。"))
        assertEquals(before, ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun ruleEditorOffersDisabledSourcesAndNeedsAtLeastOne() {
        runBlocking {
            val dao = ShimmerDatabase.getInstance(context).p1SourceDao()
            dao.registerSource(P1SourceEntity("s1", "file:///s1", "DIRECTORY_TREE"))
            dao.registerSource(P1SourceEntity("s2", "file:///s2", "DIRECTORY_TREE"))
            dao.setSourceEnabled("s2", false, null)
        }
        val activity = open()
        click(activity, "添加规则")
        val dialog = latestDialog()
        val root = dialog.window!!.decorView
        val sourceChecks = views(root).filterIsInstance<CheckBox>().filter { it.tag is String }.toList()
        assertEquals(listOf("s1", "s2"), sourceChecks.map { it.tag })
        assertTrue(sourceChecks[1].text.endsWith("（P1 中已停用）"))
        assertFalse(sourceChecks[0].text.contains("已停用"))
        views(root).filterIsInstance<EditText>().first { it.hint == "规则名称（可留空）" }.setText("旧照片")

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertTrue(views(root).filterIsInstance<TextView>().any { it.text.toString() == "至少选择一个来源" })
        assertTrue(store.loadSettings().rules.isEmpty())

        sourceChecks[1].isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        val rule = store.loadSettings().rules.single()
        assertEquals("旧照片", rule.name)
        assertEquals(listOf("s2"), rule.sourceIds)
        assertEquals(RuleRepeat.DAILY, rule.repeat)
        assertNull(rule.windowStart)
        assertTrue(texts(activity).contains("1. 旧照片（启用）"))
    }

    @Test
    fun rulesReorderDisableAndDeleteInPlace() {
        val first = PlaybackRule("r1", name = "甲", sourceIds = listOf("s1"))
        val second = PlaybackRule("r2", name = "乙", sourceIds = listOf("s1"))
        store.saveSettings(ScheduleSettings(rules = listOf(first, second)))
        val activity = open()
        // The first row cannot move up; nothing is saved.
        clickNth(activity, "上移", 0)
        assertEquals(listOf("r1", "r2"), store.loadSettings().rules.map { it.id })
        clickNth(activity, "下移", 0)
        assertEquals(listOf("r2", "r1"), store.loadSettings().rules.map { it.id })
        assertTrue(texts(activity).contains("1. 乙（启用）"))

        clickNth(activity, "停用", 0)
        assertFalse(store.loadSettings().rules.first { it.id == "r2" }.enabled)
        assertTrue(texts(activity).contains("1. 乙（停用）"))

        clickNth(activity, "删除", 1)
        confirm(latestDialog())
        assertEquals(listOf("r2"), store.loadSettings().rules.map { it.id })
    }

    @Test
    fun copyTakesAFreshReadingAndClearingKeepsSettingsAndState() {
        val settings = ScheduleSettings(onUnlock = true)
        store.saveSettings(settings)
        store.commitRun(
            ScheduleState(seq = 3L),
            listOf(ScheduleEvent(1_000L, 2_000L, EventKind.DISPATCH, listOf(ScheduleReason.UNLOCK), seq = 3L)),
            1_000L,
        )
        val activity = open()
        click(activity, "复制诊断")
        awaitCondition { ShadowToast.getTextOfLatestToast() == "已复制诊断" }
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        val lines = clip.lines()
        assertEquals("P4 调度诊断", lines[0])
        assertTrue(lines[1], lines[1].startsWith("当前有效目录："))
        assertEquals("调度：运行中", lines[2])
        assertTrue(lines.contains("【统计】") && lines.contains("【最近事件】"))
        assertTrue(lines.any { it.contains("DISPATCH #3 UNLOCK") })

        click(activity, "清空诊断记录（开始新一轮测量）")
        confirm(latestDialog())
        assertTrue(store.loadLog().isEmpty())
        assertTrue(store.loadStats().counts.isEmpty())
        assertEquals(settings, store.loadSettings())
        assertEquals(3L, store.loadState().seq)
        assertFalse(texts(activity).any { it.contains("DISPATCH #3") })
    }

    private fun open(): P4ScheduleActivity {
        controller = Robolectric.buildActivity(P4ScheduleActivity::class.java).setup()
        val activity = controller!!.get()
        // The rule list re-renders once the P1 sources arrive; wait so views are not replaced mid-test.
        awaitCondition {
            P4ScheduleActivity::class.java.getDeclaredField("sourcesLoaded").run {
                isAccessible = true
                getBoolean(activity)
            }
        }
        return activity
    }

    private fun latestDialog(): AlertDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())

    private fun confirm(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun click(activity: P4ScheduleActivity, label: String) = clickNth(activity, label, 0)

    private fun clickNth(activity: P4ScheduleActivity, label: String, index: Int) {
        views(activity.window.decorView).filterIsInstance<Button>()
            .filter { it.text.toString() == label }.toList()[index].performClick()
    }

    private fun texts(activity: P4ScheduleActivity): List<String> =
        views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitCondition(condition: () -> Boolean) {
        val timeout = System.nanoTime() + 5_000_000_000L
        do {
            idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < timeout)
        assertTrue("The expected page update did not happen", condition())
    }
}
