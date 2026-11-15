package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.ui.playback.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaybackTimeOrderTest {
    private fun item(id: String, created: Long? = null, modified: Long? = null, path: String = "$id.jpg",
                     source: String = "s", dirs: List<PlaybackDirectoryTime> = emptyList()) =
        PlaybackItem(id, path.substringAfterLast('/'), path, source, sourceKind = "DIRECTORY_TREE",
            fileTimes = FileTimestamps(created, modified), directoryTimes = dirs)
    private fun ids(items: List<PlaybackItem>, order: PlaybackOrder): List<String> =
        PlaybackCoordinator().apply { setItems(items); setOrder(order) }.orderedItems().map { it.id }
    private fun dir(path: String, created: Long? = null, modified: Long? = created) =
        PlaybackDirectoryTime(path, FileTimestamps(created, modified))

    @Test fun imageCreationSortsGloballyAndLeavesUnknownLastBothDirections() {
        val values = listOf(item("2", 20, path = "a/2.jpg"), item("10", 10, path = "b/10.jpg"), item("0"))
        assertEquals(listOf("10", "2", "0"), ids(values, PlaybackOrder.IMAGE_CREATED_ASC))
        assertEquals(listOf("2", "10", "0"), ids(values, PlaybackOrder.IMAGE_CREATED_DESC))
    }
    @Test fun modifiedAndCreationAreIndependentAndLongValuesDoNotOverflow() {
        val values = listOf(item("a", 1, Long.MAX_VALUE), item("b", Long.MAX_VALUE, 1), item("c", -2, 0))
        assertEquals(listOf("b", "a", "c"), ids(values, PlaybackOrder.IMAGE_MODIFIED_ASC))
        assertEquals(listOf("a", "b", "c"), ids(values, PlaybackOrder.IMAGE_MODIFIED_DESC))
        assertEquals(listOf("a", "b", "c"), ids(values, PlaybackOrder.IMAGE_CREATED_ASC))
    }
    @Test fun equalAndUnknownTimesUseStableNaturalOrderRegardlessOfEnumeration() {
        val values = listOf(item("10", 1), item("2", 1), item("1", 1))
        for (mode in listOf(PlaybackOrder.IMAGE_CREATED_ASC, PlaybackOrder.IMAGE_CREATED_DESC)) {
            assertEquals(listOf("1", "2", "10"), ids(values, mode))
            assertEquals(ids(values, mode), ids(values.reversed(), mode))
        }
    }
    @Test fun siblingFoldersFollowTheirOwnTimesAndKeepImagesTogether() {
        val values = listOf(item("b2", path = "b/2.jpg", dirs = listOf(dir("", 1), dir("b", 10, 30))),
            item("a1", path = "a/1.jpg", dirs = listOf(dir("", 1), dir("a", 20, 5))),
            item("b1", path = "b/1.jpg", dirs = listOf(dir("", 1), dir("b", 10, 30))),
            item("u", path = "u/u.jpg", dirs = listOf(dir("", 1), dir("u"))))
        assertEquals(listOf("b1", "b2", "a1", "u"), ids(values, PlaybackOrder.FOLDER_CREATED_ASC))
        assertEquals(listOf("a1", "b1", "b2", "u"), ids(values, PlaybackOrder.FOLDER_CREATED_DESC))
        assertEquals(listOf("a1", "b1", "b2", "u"), ids(values, PlaybackOrder.FOLDER_MODIFIED_ASC))
        assertEquals(listOf("b1", "b2", "a1", "u"), ids(values, PlaybackOrder.FOLDER_MODIFIED_DESC))
    }
    @Test fun parentFilesStayBeforeChildrenEvenWhenSortingDescending() {
        val values = listOf(item("child", path = "a/child/1.jpg", dirs = listOf(dir("", 1), dir("a", 20), dir("a/child", 40))),
            item("parent", path = "a/2.jpg", dirs = listOf(dir("", 1), dir("a", 20))),
            item("root", path = "3.jpg", dirs = listOf(dir("", 1))))
        for (order in PlaybackOrder.entries.filter { it.isTimeOrder && it.folders })
            assertEquals(listOf("root", "parent", "child"), ids(values, order))
    }
    @Test fun rootFoldersSortAcrossSourcesAndNonDirectorySourcesAreUnknown() {
        val values = listOf(item("a", source = "a", dirs = listOf(dir("", 30))),
            item("b", source = "b", dirs = listOf(dir("", 10))), item("c", source = "c"))
        assertEquals(listOf("b", "a", "c"), ids(values, PlaybackOrder.FOLDER_CREATED_ASC))
        assertEquals(listOf("a", "b", "c"), ids(values, PlaybackOrder.FOLDER_CREATED_DESC))
    }
    @Test fun updatingOnlyTimeMetadataInvalidatesCachedOrder() {
        val values = listOf(item("a", 1), item("b", 2))
        val coordinator = PlaybackCoordinator().apply { setItems(values); setOrder(PlaybackOrder.IMAGE_CREATED_ASC) }
        assertEquals(listOf("a", "b"), coordinator.orderedItems().map { it.id })
        coordinator.setItems(values.map { if (it.id == "a") it.copy(fileTimes = FileTimestamps(3)) else it })
        assertEquals(listOf("b", "a"), coordinator.orderedItems().map { it.id })
    }
    @Test fun mutableAncestorInputCannotChangeAnAcceptedCatalogBehindItsBack() {
        val dirs = mutableListOf(dir("", 1))
        val certified = CertifiedPlaybackItems(listOf(item("a", dirs = dirs)))
        dirs[0] = dir("", 5)
        assertEquals(1L, certified.single().directoryTimes.single().times.createdMillis)
    }
    @Test fun allTimeModesPersistRestoreAndAdvanceWithoutChangingHistoryOnSelection() {
        val catalog = listOf(item("a", 30, 10), item("b", 10, 30), item("c", 20, 20))
        val original = PlaybackSnapshot(currentId = "b", historyIds = listOf("b"), historyCursor = 0)
        for (mode in PlayOrder.entries.filter { it.name.contains("_CREATED_") || it.name.contains("_MODIFIED_") }) {
            val selected = PlaybackOrderUpdate.apply(original, catalog, mode)
            assertEquals(original.currentId, selected.currentId)
            assertEquals(original.historyIds, selected.historyIds)
            val saved = Json.decodeFromString<PlaybackSnapshot>(Json.encodeToString(PlaybackSnapshot.serializer(), selected.forPersistence()))
            val engine = PlaybackCoordinator().apply { setItems(catalog); restore(saved) }
            assertEquals(selected.queueIds, engine.orderedItems().map { it.id })
            val expected = selected.queueIds[(selected.queueIndex + 1) % catalog.size]
            val request = engine.requestNext()
            assertEquals(expected, request.item!!.id)
            assertTrue(engine.commit(request.version)); assertEquals(expected, engine.current()!!.id)
        }
    }
    @Test fun controlsRoundTripEveryOrderAndPreserveOtherTimeDimensions() {
        for (order in PlayOrder.entries) assertEquals(order, PlayOrderControls.from(order).order())
        val controls = PlayOrderControls.from(PlayOrder.IMAGE_MODIFIED_DESC)
        assertEquals(PlayOrder.FOLDER_MODIFIED_DESC, controls.copy(folders = true).order())
        assertEquals(PlayOrder.IMAGE_CREATED_DESC, controls.copy(creation = true).order())
        assertEquals(PlayOrder.IMAGE_MODIFIED_ASC, controls.copy(descending = false).order())
        assertEquals(PlayOrder.RANDOM, controls.copy(mode = PlayOrderMode.RANDOM).order())
    }
}
