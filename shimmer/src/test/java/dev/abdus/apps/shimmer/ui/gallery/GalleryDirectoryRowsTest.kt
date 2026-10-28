package dev.abdus.apps.shimmer.ui.gallery

import dev.abdus.apps.shimmer.p1.buildDirectoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDirectoryRowsTest {
    private val tree = buildDirectoryTree(listOf("1/1-1/a.png", "1/1-1/c.png", "1/1-2/b.png", "top.png")) { it }

    private fun rows(
        sourceEnabled: Boolean = true,
        excluded: Set<String> = emptySet(),
        expanded: Set<String> = emptySet(),
        limit: Int = GalleryDirectoryRows.ROW_LIMIT,
    ) = GalleryDirectoryRows.rows(tree, "我的图库", sourceEnabled, excluded, expanded, limit)

    @Test
    fun theSourceStartsCollapsedAsOneRow() {
        val result = rows()

        val root = result.rows.single()
        assertEquals("", root.path)
        assertEquals("我的图库", root.name)
        assertEquals(4, root.totalCount)
        assertTrue(root.hasChildren)
        assertFalse(root.expanded)
        assertEquals(DirectoryControl.SOURCE_SWITCH, root.control)
        assertEquals("播放中", root.stateLabel)
        assertFalse(result.truncated)
    }

    @Test
    fun expandingALevelShowsOnlyItsNextLevel() {
        val firstLevel = rows(expanded = setOf(""))
        assertEquals(listOf("", "1"), firstLevel.rows.map { it.path })
        assertEquals(3, firstLevel.rows[1].totalCount)
        assertEquals(DirectoryControl.TOGGLE, firstLevel.rows[1].control)

        val secondLevel = rows(expanded = setOf("", "1"))
        assertEquals(listOf("", "1", "1/1-1", "1/1-2"), secondLevel.rows.map { it.path })
        assertEquals(listOf(0, 1, 2, 2), secondLevel.rows.map { it.depth })
        assertEquals(listOf("1-1", "1-2"), secondLevel.rows.drop(2).map { it.name })
        assertEquals(2, secondLevel.rows[2].totalCount)
    }

    @Test
    fun aCollapsedParentHidesExpandedDescendants() {
        // 子目录记住了展开状态，但上级折叠时整个分支一起隐藏
        val result = rows(expanded = setOf("", "1/1-1"))

        assertEquals(listOf("", "1"), result.rows.map { it.path })
    }

    @Test
    fun aStoppedLevelHandsItsChildrenToTheParentControl() {
        val result = rows(excluded = setOf("1", "1/1-2"), expanded = setOf("", "1"))
        val byPath = result.rows.associateBy { it.path }

        assertEquals("已停用", byPath.getValue("1").stateLabel)
        assertEquals(DirectoryControl.TOGGLE, byPath.getValue("1").control)
        assertTrue(byPath.getValue("1").stopped)

        assertEquals("受上级停用影响", byPath.getValue("1/1-1").stateLabel)
        assertEquals(DirectoryControl.PARENT, byPath.getValue("1/1-1").control)
        assertFalse(byPath.getValue("1/1-1").stopped)

        // 本层记录保留，上级恢复后再次生效
        assertEquals("受上级停用影响（本层也已停用）", byPath.getValue("1/1-2").stateLabel)
        assertEquals(DirectoryControl.PARENT, byPath.getValue("1/1-2").control)
        assertTrue(byPath.getValue("1/1-2").stopped)
    }

    @Test
    fun aStoppedSourceCoversEveryLevel() {
        val result = rows(sourceEnabled = false, expanded = setOf("", "1"))

        assertEquals("来源已停用", result.rows[0].stateLabel)
        assertEquals(DirectoryControl.SOURCE_SWITCH, result.rows[0].control)
        assertTrue(result.rows.drop(1).all { it.control == DirectoryControl.PARENT })
        assertTrue(result.rows.drop(1).all { it.stateLabel == "受上级停用影响" })
    }

    @Test
    fun aLeafCannotBeExpanded() {
        val result = rows(expanded = setOf("", "1", "1/1-1"))
        val leaf = result.rows.single { it.path == "1/1-1" }

        assertFalse(leaf.hasChildren)
        assertFalse(leaf.expanded)
        assertEquals(4, result.rows.size)
    }

    @Test
    fun largeTreesStopAtTheRowLimit() {
        val result = rows(expanded = setOf("", "1"), limit = 3)

        assertEquals(listOf("", "1", "1/1-1"), result.rows.map { it.path })
        assertTrue(result.truncated)
    }

    @Test
    fun aTreeThatExactlyFitsIsNotTruncated() {
        val result = rows(expanded = setOf("", "1"), limit = 4)

        assertEquals(4, result.rows.size)
        assertFalse(result.truncated)
    }
}
