package dev.abdus.apps.shimmer.ui.playback.schedule

import android.content.Context
import dev.abdus.apps.shimmer.p4.*
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScheduleEditorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store get() = ScheduleStore(context)
    private fun editor() = ScheduleEditor(store) { 12345L }
    @Test fun dailyCreationValidatesRangeAndDuplicatesWithoutOverwritingOtherControls() {
        val original = ScheduleSettings(pauseAll = true, onUnlock = true, interval = IntervalSettings(enabled = true, value = 42))
        store.saveSettings(original)
        assertNotNull(editor().saveDaily(null, -1))
        assertNotNull(editor().saveDaily(null, 1440))
        assertEquals(original, store.loadSettings())
        assertNull(editor().saveDaily(null, 480))
        val saved = store.loadSettings()
        assertEquals(12345L, saved.dailyTimes.single().editedAtWall)
        assertEquals(original, saved.copy(dailyTimes = emptyList()))
        assertNotNull(editor().saveDaily(null, 480))
        assertEquals(saved, store.loadSettings())
    }
    @Test fun editingTimeKeepsIdAndDisabledStateAndSetsFreshTimestamp() {
        store.saveSettings(ScheduleSettings(dailyTimes = listOf(DailyTime("d", 480, false, 1))))
        assertNull(editor().saveDaily("d", 600))
        assertEquals(DailyTime("d", 600, false, 12345), store.loadSettings().dailyTimes.single())
        editor().setDailyEnabled("d", true)
        assertTrue(store.loadSettings().dailyTimes.single().enabled)
    }
    @Test fun staleDailyDialogCannotRecreateDeletedEntryOrRemoveAnotherOne() {
        store.saveSettings(ScheduleSettings(dailyTimes = listOf(DailyTime("keep", 600))))
        assertNotNull(editor().saveDaily("gone", 480))
        editor().deleteDaily("gone")
        assertEquals("keep", store.loadSettings().dailyTimes.single().id)
        editor().deleteDaily("keep")
        assertTrue(store.loadSettings().dailyTimes.isEmpty())
    }
    @Test fun ruleValidationRejectsMissingSourcesAndBadWindows() {
        val base = RuleForm("r", sourceIds = listOf("s"))
        assertNotNull(editor().saveRule(base, false, emptySet()))
        assertNotNull(editor().saveRule(base.copy(windowOn = true, windowStart = "bad", windowEnd = "08:00"), false, setOf("s")))
        assertTrue(store.loadSettings().rules.isEmpty())
    }
    @Test fun allRepeatTypesSaveWithExpectedValidationAndRoundTripDrafts() {
        val forms = listOf(
            RuleForm("d", sourceIds = listOf("s")),
            RuleForm("date", repeat = RuleRepeat.DATE_RANGE, startDate = "2026-11-14", sourceIds = listOf("s")),
            RuleForm("year", repeat = RuleRepeat.YEARLY, startMonthDay = "12-20", endMonthDay = "01-05", sourceIds = listOf("s")),
            RuleForm("week", repeat = RuleRepeat.WEEKLY, weekdays = setOf(1, 7), windowOn = true,
                windowStart = "22:00", windowEnd = "02:00", sourceIds = listOf("s")))
        forms.forEach { form ->
            assertEquals(form, Json.decodeFromString<RuleForm>(Json.encodeToString(form)))
            assertNull(editor().saveRule(form, false, setOf("s")))
        }
        assertEquals(4, store.loadSettings().rules.size)
    }
    @Test fun ruleUpdatesKeepCurrentEnabledStateAndReadFreshIntervalSettings() {
        val form = RuleForm("r", sourceIds = listOf("s"))
        assertNull(editor().saveRule(form, false, setOf("s")))
        editor().setRuleEnabled("r", false)
        store.updateSettings { it.copy(interval = it.interval.copy(value = 17), onLock = true) }
        assertNull(editor().saveRule(form.copy(name = "new"), true, setOf("s")))
        val result = store.loadSettings()
        assertFalse(result.rules.single().enabled)
        assertEquals("new", result.rules.single().name)
        assertEquals(17, result.interval.value)
        assertTrue(result.onLock)
    }
    @Test fun deletedAndDuplicateRulesCannotBeRecreatedOrOverwritten() {
        val form = RuleForm("r", sourceIds = listOf("s"))
        assertNotNull(editor().saveRule(form, true, setOf("s")))
        assertNull(editor().saveRule(form, false, setOf("s")))
        assertNotNull(editor().saveRule(form.copy(name = "bad"), false, setOf("s")))
        editor().deleteRule("r")
        assertNotNull(editor().saveRule(form, true, setOf("s")))
        assertTrue(store.loadSettings().rules.isEmpty())
    }
    @Test fun priorityMovesByIdAndClampsAtEdges() {
        store.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("a"), PlaybackRule("b"), PlaybackRule("c"))))
        editor().moveRule("b", -1)
        assertEquals(listOf("b", "a", "c"), store.loadSettings().rules.map { it.id })
        editor().moveRule("b", -1); editor().moveRule("c", 1); editor().moveRule("gone", 1)
        assertEquals(listOf("b", "a", "c"), store.loadSettings().rules.map { it.id })
        editor().moveRule("b", 1)
        assertEquals(listOf("a", "b", "c"), store.loadSettings().rules.map { it.id })
    }
    @Test fun editingLockTargetNeverChangesHome() {
        val lock = ScheduleStore(context, WallpaperTarget.LOCK)
        ScheduleEditor(lock).saveDaily(null, 600)
        assertTrue(store.loadSettings().dailyTimes.isEmpty())
        assertEquals(600, lock.loadSettings().dailyTimes.single().minuteOfDay)
    }
    @Test fun updateSettingsExceptionLeavesOriginalDataIntact() {
        val original = store.loadSettings()
        assertThrows(IllegalStateException::class.java) { store.updateSettings { error("failed") } }
        assertEquals(original, store.loadSettings())
    }
}
