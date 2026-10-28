package dev.abdus.apps.shimmer.p2

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p3.ImageTransform
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSplitStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs() = context.getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE)
    private fun configs() = context.getSharedPreferences(PlaybackSnapshotStore.MANUAL_NAME, Context.MODE_PRIVATE)
    private fun state(ids: List<String> = listOf("a", "hidden", "b")) = PlaybackSnapshot(
        order = PlaybackOrder.MANUAL, queueIds = ids, currentId = "a", historyIds = listOf("a"), historyCursor = 0,
        sourcePositions = mapOf("root" to "a"), sourceHistoryCursors = mapOf("root" to 0), historySourceIds = listOf("root"),
    )

    @Test fun stableLargeManualCommitsEncodeNoOrderIdsAndKeepRuntimeFileSmall() {
        val ids = (1..26_000).map { "content://test/very-long-path/$it" }.immutablePlaybackIds()
        val store = PlaybackSnapshotStore(context)
        store.save(state(ids))
        val originalConfigurations = configs().all.toMap()
        repeat(20) {
            val before = store.load()!!
            val after = before.copy(currentId = ids[it], historyIds = before.historyIds + ids[it], historyCursor = before.historyIds.size)
            val result = store.compareAndSaveWithWork(before, after)
            assertTrue(result.accepted)
            assertEquals(0, result.work.manualIdsEncoded)
            assertEquals(0, result.work.manualConfigurationWrites)
            assertEquals(0, result.work.manualIdsCopied)
            assertTrue(result.work.runtimeCharacters < 10_000)
            assertSame(ids, store.load()!!.queueIds)
        }
        assertEquals(originalConfigurations, configs().all)
        assertFalse(prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, "")!!.contains(ids.last()))
        assertEquals(ids, PlaybackSnapshotStore(context).load()!!.queueIds)
    }

    @Test fun onlyRealReorderingWritesConfigurationAndTwoSlotsBoundStorage() {
        val store = PlaybackSnapshotStore(context)
        store.save(state())
        repeat(20) { turn ->
            val before = store.load()!!
            val ids = if (turn % 2 == 0) listOf("b", "hidden", "a") else listOf("a", "hidden", "b")
            val result = store.compareAndSaveWithWork(before, before.copy(queueIds = ids))
            assertTrue(result.accepted)
            assertEquals(3, result.work.manualIdsEncoded)
            assertEquals(1, result.work.manualConfigurationWrites)
            assertTrue(configs().all.keys.all { it in setOf("slot_0", "slot_1") })
            assertTrue(configs().all.size <= 2)
            assertEquals(ids, PlaybackSnapshotStore(context).load()!!.queueIds)
        }
    }

    @Test fun legacyManualLoadsReadOnlyAndFirstRealSaveMigratesWithoutChangingState() {
        val legacy = state().copy(historyTransforms = listOf(ImageTransform(userScale = 2f)))
        val raw = Json.encodeToString(legacy)
        prefs().edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, raw).commit()
        val store = PlaybackSnapshotStore(context)
        assertEquals(legacy, store.load())
        assertEquals(raw, prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        assertTrue(configs().all.isEmpty())
        store.save(legacy)
        assertEquals(legacy, store.load())
        assertEquals(legacy, PlaybackSnapshotStore(context).load())
        assertTrue(prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, "")!!.contains("\"format\":2"))
    }

    @Test fun legacyNonManualDoesNotResurrectDormantManualOrderWhenModesChange() {
        val store = PlaybackSnapshotStore(context)
        store.save(state())
        val stale = configs().all.toMap()
        store.save(state().copy(order = PlaybackOrder.NATURAL_DESC))
        assertTrue(store.load()!!.queueIds.isEmpty())
        assertEquals(stale, configs().all)
        val manual = PlaybackOrderUpdateForTest.selectManual(store.load()!!)
        store.save(manual)
        assertEquals(listOf("fresh", "a"), store.load()!!.queueIds)
    }

    @Test fun twoInstancesMergeLatestExactCompositeAndRejectStaleConfiguration() {
        val first = PlaybackSnapshotStore(context); val second = PlaybackSnapshotStore(context)
        first.save(state())
        val old = first.load()!!
        second.update { it!!.copy(queueIds = listOf("b", "hidden", "a")) }
        assertFalse(first.compareAndSave(old, old.copy(currentId = "b")))
        assertEquals(listOf("b", "hidden", "a"), first.load()!!.queueIds)
        val latest = first.load()!!
        assertTrue(first.compareAndSave(latest, latest.copy(currentId = "b")))
        assertEquals("b", second.load()!!.currentId)
    }

    @Test fun externalSameRuntimeButChangedReferencedConfigCannotHitDecodedMemo() {
        val store = PlaybackSnapshotStore(context)
        store.save(state())
        val before = store.load()!!
        val key = configs().all.keys.single()
        val raw = configs().getString(key, null)!!
        configs().edit().putString(key, raw.replace("\"hidden\"", "\"other\"" )).commit()
        assertNotEquals(before.queueIds, store.load()!!.queueIds)
        assertFalse(store.compareAndSave(before, before))
    }

    @Test fun missingMismatchedOrCorruptReferencedOrderReturnsNoStaleStateAndPreservesRaw() {
        val store = PlaybackSnapshotStore(context); store.save(state())
        val key = configs().all.keys.single()
        val good = configs().getString(key, null)!!
        val runtime = prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null)!!
        for (bad in listOf("not-json", good.replace("\"token\":\"", "\"token\":\"wrong-"))) {
            configs().edit().putString(key, bad).commit()
            assertNull(store.load())
            assertFalse(store.compareAndSave(null, state()))
            assertEquals(bad, configs().getString(key, null))
            assertEquals(runtime, prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        }
        configs().edit().remove(key).commit()
        assertNull(store.load()); assertEquals(runtime, prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        configs().edit().putString(key, good).commit()
        assertEquals(state(), store.load())
    }

    @Test fun unknownRuntimeVersionOrMalformedRuntimeNeverDeletesUserConfiguration() {
        val store = PlaybackSnapshotStore(context); store.save(state())
        val original = prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null)!!
        for (bad in listOf("bad-json", original.replace("\"format\":2", "\"format\":99"), original.replace("\"format\":2,", ""))) {
            prefs().edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, bad).commit()
            assertNull(store.load())
            assertFalse(store.compareAndSave(null, state()))
            assertEquals(bad, prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        }
    }

    @Test fun allImplicitWriteEntrypointsRejectCorruptRuntimeWithoutErasingRawOrConfiguration() {
        val store = PlaybackSnapshotStore(context); store.save(state())
        prefs().edit().putString(PlaybackSnapshotStore.SNAPSHOT_KEY, "invalid-json").commit()
        val previous = prefs().all.toMap(); val orders = configs().all.toMap()
        val writes = listOf<() -> Unit>(
            { store.save(state()) }, { store.update { state() } }, { store.clearHistory() },
            { store.commitImageTransform("a", ImageTransform(userScale = 2f)) })
        writes.forEach { write ->
            try { write(); fail("Corrupt record must require explicit recovery") } catch (expected: IllegalStateException) { }
            assertEquals(previous, prefs().all); assertEquals(orders, configs().all)
        }
    }

    @Test fun nonStringRuntimeOrConfigurationIsPreservedAndNeverAuthorizesNullCas() {
        val store = PlaybackSnapshotStore(context); store.save(state())
        val key = configs().all.keys.single()
        configs().edit().putInt(key, 7).commit()
        assertNull(store.load()); assertFalse(store.compareAndSave(null, state()))
        assertEquals(7, configs().getInt(key, 0))
        prefs().edit().putInt(PlaybackSnapshotStore.SNAPSHOT_KEY, 9).commit()
        assertNull(store.load()); assertFalse(store.compareAndSave(null, state()))
        assertEquals(9, prefs().getInt(PlaybackSnapshotStore.SNAPSHOT_KEY, 0))
    }

    @Test fun mutableInputsAndLoadedCollectionsCannotPolluteSharedOrderIdentity() {
        val queue = mutableListOf("a", "hidden", "b")
        val store = PlaybackSnapshotStore(context); store.save(state(queue))
        val saved = store.load()!!
        queue.clear()
        assertEquals(listOf("a", "hidden", "b"), saved.queueIds)
        try { (saved.queueIds as MutableList<String>)[0] = "poison"; fail("Must be immutable") } catch (expected: UnsupportedOperationException) { }
        assertEquals(saved, PlaybackSnapshotStore(context).load())
    }

    @Test fun transformAndDefaultHistoryClearKeepOrderBlobAndCurrentResumeInformation() {
        val store = PlaybackSnapshotStore(context)
        store.save(state().copy(historyTransforms = listOf(ImageTransform(userScale = 2f))))
        val original = configs().all.toMap()
        store.commitImageTransform("a", ImageTransform(userScale = 3f))
        assertTrue(store.load()!!.historyTransforms.isEmpty())
        assertEquals(3f, store.loadImageTransform("a")!!.userScale)
        val cleared = store.clearHistory()
        assertEquals(state().queueIds, cleared.queueIds)
        assertEquals(state().sourcePositions, cleared.sourcePositions)
        assertEquals(original, configs().all)
        store.commitImageTransform("a", null)
        assertNull(store.loadImageTransform("a"))
    }

    @Test fun resetHistoryDoesNotReferenceOrReviveTheOldManualConfiguration() {
        val store = PlaybackSnapshotStore(context); store.save(state())
        val reset = store.clearHistory(true)
        assertEquals(PlaybackOrder.NATURAL_ASC, reset.order)
        assertTrue(reset.queueIds.isEmpty())
        assertEquals(reset, PlaybackSnapshotStore(context).load())
    }

    @Test fun conditionalTransformAndCasStillCheckAdmissionInsideOriginalRealLock() {
        val store = PlaybackSnapshotStore(context); store.save(state())
        assertFalse(store.commitImageTransformIfAllowed("a", ImageTransform(userScale = 2f)) { false })
        assertNull(store.loadImageTransform("a"))
        assertFalse(store.compareAndSaveIfAllowed(store.load(), state().copy(currentId = "b")) { false })
        assertEquals(state(), store.load())
    }

    @Test fun failedFlushAbortsBeforeOverwritingEitherConfigurationSlot() {
        val actual = PlaybackSnapshotStore(context); actual.save(state())
        val before = prefs().all.toMap(); val orders = configs().all.toMap()
        val broken = PlaybackSnapshotStore(FailingContext(context, failRuntimeFlush = true))
        try { broken.save(state(listOf("b", "hidden", "a"))); fail("Expected real commit refusal") } catch (expected: IllegalStateException) { }
        assertEquals(before, prefs().all); assertEquals(orders, configs().all); assertEquals(state(), actual.load())
    }

    @Test fun failedConfigurationPreparationNeverPublishesItsRuntimePointerOrTransform() {
        val actual = PlaybackSnapshotStore(context); actual.save(state())
        val before = prefs().all.toMap(); val orders = configs().all.toMap()
        val broken = PlaybackSnapshotStore(FailingContext(context, failConfigurationCommit = true))
        try { broken.save(state(listOf("b", "hidden", "a"))); fail("Expected configuration commit refusal") } catch (expected: IllegalStateException) { }
        assertEquals(before, prefs().all); assertEquals(orders, configs().all); assertEquals(state(), actual.load())
    }

    @Test fun preparedButUnpublishedSlotLeavesPreviousDurableReferenceReadable() {
        val actual = PlaybackSnapshotStore(context); actual.save(state())
        val before = prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null)
        val broken = PlaybackSnapshotStore(FailingContext(context, failRuntimeApply = true))
        try { broken.save(state(listOf("b", "hidden", "a"))); fail("Expected runtime publication refusal") } catch (expected: IllegalStateException) { }
        assertEquals(before, prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        assertEquals(state(), PlaybackSnapshotStore(context).load())
        assertEquals(2, configs().all.size)
    }

    @Test fun eachConfigurationChangeFlushesRuntimeBeforePreparingAndPublishingTheOtherSlot() {
        val events = mutableListOf<String>()
        val store = PlaybackSnapshotStore(FailingContext(context, events = events))
        store.save(state())
        assertEquals(listOf("runtime:commit", "configuration:commit", "runtime:apply"), events)
        events.clear()
        store.update { it!!.copy(currentId = "b") }
        assertEquals(listOf("runtime:apply"), events)
        repeat(4) { index ->
            events.clear()
            store.update { it!!.copy(queueIds = if (index % 2 == 0) listOf("b", "hidden", "a") else listOf("a", "hidden", "b")) }
            assertEquals(listOf("runtime:commit", "configuration:commit", "runtime:apply"), events)
            assertEquals(2, configs().all.size)
        }
    }

    @Test fun nonManualToManualPreparationFailureKeepsTheDurableNonManualStateAndCanRecover() {
        val actual = PlaybackSnapshotStore(context); actual.save(state())
        val nonManual = state().copy(order = PlaybackOrder.NATURAL_DESC).forPersistence()
        actual.save(nonManual)
        val broken = PlaybackSnapshotStore(FailingContext(context, failRuntimeApply = true))
        try { broken.save(state(listOf("new", "a"))); fail("Expected runtime failure") } catch (expected: IllegalStateException) { }
        assertEquals(nonManual, PlaybackSnapshotStore(context).load())
        actual.save(state(listOf("new", "a")))
        assertEquals(listOf("new", "a"), PlaybackSnapshotStore(context).load()!!.queueIds)
        assertTrue(configs().all.size <= 2)
    }

    @Test fun failedConfigurationCommitAfterMemoryPublicationStillPreservesReferencedSlotAndCanRetry() {
        val actual = PlaybackSnapshotStore(context); actual.save(state())
        val before = prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null)
        val broken = PlaybackSnapshotStore(FailingContext(context, failConfigurationAfterPublication = true))
        try { broken.save(state(listOf("b", "hidden", "a"))); fail("Expected configuration refusal") } catch (expected: IllegalStateException) { }
        assertEquals(before, prefs().getString(PlaybackSnapshotStore.SNAPSHOT_KEY, null))
        assertEquals(2, configs().all.size)
        assertEquals(state(), PlaybackSnapshotStore(context).load())
        actual.save(state(listOf("b", "hidden", "a")))
        assertEquals(listOf("b", "hidden", "a"), PlaybackSnapshotStore(context).load()!!.queueIds)
    }

    private class FailingContext(base: Context, val failRuntimeFlush: Boolean = false,
        val failConfigurationCommit: Boolean = false, val failRuntimeApply: Boolean = false,
        val failConfigurationAfterPublication: Boolean = false, val events: MutableList<String>? = null) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val original = super.getSharedPreferences(name, mode)
            return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                val result = try { method.invoke(original, *(args ?: emptyArray())) } catch (failed: InvocationTargetException) { throw failed.targetException }
                if (method.name != "edit") result else {
                    val editor = result as SharedPreferences.Editor
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, operation, arguments ->
                        if (operation.name == "commit" || operation.name == "apply") {
                            events?.add("${if (name == PlaybackSnapshotStore.NAME) "runtime" else "configuration"}:${operation.name}")
                        }
                        if (operation.name == "commit" && ((name == PlaybackSnapshotStore.NAME && failRuntimeFlush) ||
                            (name == PlaybackSnapshotStore.MANUAL_NAME && failConfigurationCommit))) false
                        else if (operation.name == "apply" && name == PlaybackSnapshotStore.NAME && failRuntimeApply) throw IllegalStateException("runtime apply injected failure")
                        else if (operation.name == "commit" && name == PlaybackSnapshotStore.MANUAL_NAME && failConfigurationAfterPublication) {
                            editor.commit(); false // Controlled post-publication refusal, not a real disk outage.
                        }
                        else {
                            val applied = try { operation.invoke(editor, *(arguments ?: emptyArray())) } catch (failed: InvocationTargetException) { throw failed.targetException }
                            if (applied is SharedPreferences.Editor) proxy else applied
                        }
                    }
                }
            } as SharedPreferences
        }
    }
    private object PlaybackOrderUpdateForTest {
        fun selectManual(state: PlaybackSnapshot) = dev.abdus.apps.shimmer.ui.playback.PlaybackOrderUpdate.select(
            state, dev.abdus.apps.shimmer.ui.playback.PlayOrder.MANUAL, listOf("fresh", "a"))
    }
}
