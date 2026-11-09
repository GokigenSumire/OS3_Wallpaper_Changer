package dev.abdus.apps.shimmer.p3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorPreferencesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun newInstallAsksAndStartsAtBottom() {
        val store = EditorPreferences(context)
        assertEquals(EditorSwitchSavePolicy.ASK, store.switchSavePolicy())
        assertEquals(EditorDockPosition.BOTTOM, store.dockPosition())
        assertEquals(false, store.dockVisible())
        assertEquals(false, store.toolsExpanded())
    }

    @Test fun choicesPersistAcrossStoreInstancesWithoutOverwritingEachOther() {
        val store = EditorPreferences(context)
        store.setSwitchSavePolicy(EditorSwitchSavePolicy.SAVE)
        store.setDockPosition(EditorDockPosition.TOP)
        val reopened = EditorPreferences(context)
        assertEquals(EditorSwitchSavePolicy.SAVE, reopened.switchSavePolicy())
        assertEquals(EditorDockPosition.TOP, reopened.dockPosition())
        reopened.setSwitchSavePolicy(EditorSwitchSavePolicy.DISCARD)
        assertEquals(EditorDockPosition.TOP, store.dockPosition())
        assertEquals(EditorSwitchSavePolicy.DISCARD, store.switchSavePolicy())
    }

    @Test fun unknownValuesFallBackWithoutWritingOtherPreferences() {
        val prefs = context.getSharedPreferences(EditorPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(EditorPreferences.KEY_SWITCH_SAVE_POLICY, "future").putString(EditorPreferences.KEY_DOCK_POSITION, "future").putString("unrelated", "kept").commit()
        assertEquals(EditorSwitchSavePolicy.ASK, EditorPreferences(context).switchSavePolicy())
        assertEquals(EditorDockPosition.BOTTOM, EditorPreferences(context).dockPosition())
        assertEquals("kept", prefs.getString("unrelated", null))
    }

    @Test fun wrongStoredTypesDoNotCrashTheEditor() {
        context.getSharedPreferences(EditorPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(EditorPreferences.KEY_SWITCH_SAVE_POLICY, 1).putBoolean(EditorPreferences.KEY_DOCK_POSITION, true).commit()
        val store = EditorPreferences(context)
        assertEquals(EditorSwitchSavePolicy.ASK, store.switchSavePolicy())
        assertEquals(EditorDockPosition.BOTTOM, store.dockPosition())
    }

    @Test fun previewAndExpandedStatePersistIndependentlyAlongsideOtherChoices() {
        val store = EditorPreferences(context)
        store.setDockVisible(true)
        store.setToolsExpanded(true)
        store.setSwitchSavePolicy(EditorSwitchSavePolicy.DISCARD)
        store.setDockVisible(false)
        val reopened = EditorPreferences(context)
        assertEquals(false, reopened.dockVisible())
        assertEquals(true, reopened.toolsExpanded())
        assertEquals(EditorSwitchSavePolicy.DISCARD, reopened.switchSavePolicy())
        reopened.setDockVisible(true)
        reopened.setToolsExpanded(false)
        assertEquals(true, store.dockVisible())
        assertEquals(false, store.toolsExpanded())
    }

    @Test fun damagedVisibilityValuesReturnSafeDefaultsWithoutClearingOtherChoices() {
        val store = EditorPreferences(context)
        store.setDockPosition(EditorDockPosition.TOP)
        context.getSharedPreferences(EditorPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(EditorPreferences.KEY_DOCK_VISIBLE, "invalid").putInt(EditorPreferences.KEY_TOOLS_EXPANDED, 4).commit()
        assertEquals(false, store.dockVisible())
        assertEquals(false, store.toolsExpanded())
        assertEquals(EditorDockPosition.TOP, store.dockPosition())
    }
}
