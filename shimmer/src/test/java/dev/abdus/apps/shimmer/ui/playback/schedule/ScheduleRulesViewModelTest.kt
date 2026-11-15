package dev.abdus.apps.shimmer.ui.playback.schedule

import android.app.Application
import android.os.Looper
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p4.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScheduleRulesViewModelTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Test fun sourcesFailureCanRetryAndSavingUsesActualSources() {
        var attempts = 0
        val source = P1SourceEntity("s", "content://test/source", "DIRECTORY_TREE", enabled = false)
        val vm = ScheduleRulesViewModel(app, WallpaperTarget.HOME) {
            if (++attempts == 1) error("unavailable") else flowOf(listOf(source))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(vm.uiState.value.sourceError)
        assertNotNull(vm.saveRule(RuleForm("r", sourceIds = listOf("s")), false))
        vm.retrySources(); shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.uiState.value.sourceError)
        assertEquals(listOf(source), vm.uiState.value.sources)
        assertNull(vm.saveRule(RuleForm("r", sourceIds = listOf("s")), false))
        assertEquals(listOf("s"), ScheduleStore(app).loadSettings().rules.single().sourceIds)
    }
    @Test fun externalChangesRefreshWithoutClobberingAndTargetRemainsIsolated() {
        val vm = ScheduleRulesViewModel(app, WallpaperTarget.LOCK) { flowOf(emptyList()) }
        val store = ScheduleStore(app, WallpaperTarget.LOCK)
        store.saveSettings(ScheduleSettings(pauseAll = true, onUnlock = true))
        shadowOf(Looper.getMainLooper()).idle()
        vm.refresh()
        assertTrue(vm.uiState.value.settings.pauseAll)
        assertNull(vm.saveDaily(null, 720))
        assertTrue(store.loadSettings().onUnlock)
        assertTrue(ScheduleStore(app).loadSettings().dailyTimes.isEmpty())
    }
}
