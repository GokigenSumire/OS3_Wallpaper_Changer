package dev.abdus.apps.shimmer.p3

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorSwitchSavePolicyTest {
    @Test fun absentOrUnknownPolicyKeepsAsking() {
        listOf(null, "", "future", "SAVE").forEach {
            assertEquals(EditorSwitchSavePolicy.ASK, EditorSwitchSavePolicy.fromStored(it))
        }
    }

    @Test fun stableValuesRoundTrip() {
        EditorSwitchSavePolicy.entries.forEach { assertEquals(it, EditorSwitchSavePolicy.fromStored(it.storedValue)) }
        assertEquals(listOf("保存", "不存", "询问"), EditorSwitchSavePolicy.entries.map { it.compactLabel })
    }

    @Test fun unchangedImagesNeverSaveOrAskEvenUnderSavePolicy() {
        EditorSwitchSavePolicy.entries.forEach { assertEquals(EditorSwitchDecision.SWITCH_WITHOUT_SAVE, it.decide(false)) }
    }

    @Test fun dirtyImagesUseTheChosenPolicy() {
        assertEquals(EditorSwitchDecision.SAVE_AND_SWITCH, EditorSwitchSavePolicy.SAVE.decide(true))
        assertEquals(EditorSwitchDecision.SWITCH_WITHOUT_SAVE, EditorSwitchSavePolicy.DISCARD.decide(true))
        assertEquals(EditorSwitchDecision.ASK, EditorSwitchSavePolicy.ASK.decide(true))
    }

    @Test fun invalidDockFallsBackAndBothPositionsSwitchBack() {
        assertEquals(EditorDockPosition.BOTTOM, EditorDockPosition.fromStored("future"))
        EditorDockPosition.entries.forEach {
            assertEquals(it, EditorDockPosition.fromStored(it.storedValue))
            assertEquals(it, it.opposite().opposite())
        }
    }
}
