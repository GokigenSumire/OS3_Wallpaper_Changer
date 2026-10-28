package dev.abdus.apps.shimmer.p2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P2GroupUiTest {
    @Test
    fun directorySourceBecomesOneNodePerLevelInsteadOfAFlatSubgroupList() {
        val items = listOf(
            item("a1", "目录/一/a1.jpg"),
            item("a2", "目录/一/a2.jpg"),
            item("b1", "目录/二/b1.jpg"),
        )

        val groups = buildPlaybackGroups(items)
        val tree = requireNotNull(groups.single().tree)

        // The user's v14 report: 目录 must collapse as a whole, with 一/ and 二/ one level below it.
        assertEquals(listOf("目录"), tree.children.map { it.path })
        assertEquals(3, tree.children.single().totalCount)
        assertEquals(listOf("目录/一", "目录/二"), tree.children.single().children.map { it.path })
        assertEquals(listOf(2, 1), tree.children.single().children.map { it.directItems.size })
        assertEquals("b1", groups.single().previewItem.id)
    }

    @Test
    fun onlyManualOrderFollowsContiguousPlaySegments() {
        assertTrue(groupsBySegment(PlaybackOrder.MANUAL))
        // A random round interleaves sources, so following runs produced one header per image.
        assertTrue(!groupsBySegment(PlaybackOrder.RANDOM))
        assertTrue(!groupsBySegment(PlaybackOrder.NATURAL_ASC))
        assertTrue(!groupsBySegment(PlaybackOrder.NATURAL_DESC))
    }

    @Test
    fun nonDirectorySourcesHaveNoDirectoryTree() {
        val groups = buildPlaybackGroups(listOf(item("multi-1", "multi-1.jpg", kind = "MULTIPLE_DOCUMENTS")))

        assertEquals(null, groups.single().tree)
    }

    @Test
    fun singleAndMultipleSourcesUseFirstImageAsPreview() {
        val items = listOf(
            item("single-1", "single-1.jpg", kind = "SINGLE_DOCUMENT"),
            item("multi-1", "multi-1.jpg", kind = "MULTIPLE_DOCUMENTS"),
            item("multi-2", "multi-2.jpg", kind = "MULTIPLE_DOCUMENTS"),
        )

        val groups = buildPlaybackGroups(items)

        assertEquals("single-1", groups[0].previewItem.id)
        assertEquals("multi-1", groups[1].previewItem.id)
    }

    @Test
    fun rowHighlightIsScopedToTheMovedItem() {
        assertTrue(shouldHighlightMovedRow("moved", "moved"))
        assertTrue(!shouldHighlightMovedRow("other", "moved"))
    }

    private fun item(id: String, path: String, kind: String = "DIRECTORY_TREE") = PlaybackItem(
        id = id,
        displayName = "$id.png",
        relativePath = path,
        sourceId = "source",
        sourceKind = kind,
        sourceRootUri = "file:///source",
    )
}
