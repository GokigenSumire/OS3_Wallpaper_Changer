package dev.abdus.apps.shimmer.ui.onboarding

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FirstUseGuideStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(FirstUseGuideStore.PREFS_NAME, Context.MODE_PRIVATE)
    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun firstLaunchShowsAndACompletionPersistsAcrossOwners() {
        assertTrue(FirstUseGuideStore(context).shouldShow())
        assertTrue(FirstUseGuideStore(context).complete())
        assertFalse(FirstUseGuideStore(context).shouldShow())
    }
    @Test fun corruptStoredTypeDoesNotCrashOrSuppressIntroduction() {
        prefs.edit().putString(FirstUseGuideStore.KEY_VERSION, "bad").commit()
        assertTrue(FirstUseGuideStore(context).shouldShow())
    }
    @Test fun readingAndManualReopenDoNotClearAcknowledgement() {
        FirstUseGuideStore(context).complete()
        val store = FirstUseGuideStore(context)
        repeat(3) { assertFalse(store.shouldShow()) }
        assertEquals(FirstUseGuideStore.VERSION, prefs.getInt(FirstUseGuideStore.KEY_VERSION, 0))
    }
    @Test fun oldTextGuideAcknowledgementDoesNotHideInteractiveGuide() {
        prefs.edit().putInt(FirstUseGuideStore.KEY_VERSION, 1).commit()
        assertTrue(FirstUseGuideStore(context).shouldShow())
        assertTrue(FirstUseGuideStore(context).complete())
        assertFalse(FirstUseGuideStore(context).shouldShow())
    }
    @Test fun futureAcknowledgementDoesNotRepeatOlderGuide() {
        prefs.edit().putInt(FirstUseGuideStore.KEY_VERSION, 99).commit()
        assertFalse(FirstUseGuideStore(context).shouldShow())
    }
    @Test fun previousInteractiveGuideDoesNotHideNewGesturePractice() {
        prefs.edit().putInt(FirstUseGuideStore.KEY_VERSION, 2).commit()
        assertTrue(FirstUseGuideStore(context).shouldShow())
    }
}
