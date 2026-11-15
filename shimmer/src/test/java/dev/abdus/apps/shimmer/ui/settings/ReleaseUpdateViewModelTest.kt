package dev.abdus.apps.shimmer.ui.settings

import dev.abdus.apps.shimmer.ui.settings.components.ReleaseUpdateViewModel
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import personal.wallpaper.update.ReleaseUpdateChecker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReleaseUpdateViewModelTest {
    @Test fun openingCardDoesNotRequestNetworkAndFailureCanBeRetried() {
        var calls = 0
        val checker = ReleaseUpdateChecker { calls++; ReleaseUpdateChecker.Response(503, "") }
        val model = ReleaseUpdateViewModel(checker, Dispatchers.Unconfined)
        assertEquals(0, calls)
        assertNull(model.state.value.result)
        model.check()
        assertEquals(1, calls)
        assertFalse(model.state.value.checking)
        assertEquals(ReleaseUpdateChecker.State.UNAVAILABLE, model.state.value.result?.state)
        model.check()
        assertEquals(2, calls)
    }
}
