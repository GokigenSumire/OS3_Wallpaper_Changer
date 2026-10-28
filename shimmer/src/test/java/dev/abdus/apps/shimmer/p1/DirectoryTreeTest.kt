package dev.abdus.apps.shimmer.p1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryTreeTest {
    @Test
    fun intermediateFoldersBecomeTheirOwnLevelInsteadOfOneFlatEntry() {
        val tree = buildDirectoryTree(listOf("1/1-1/a.jpg", "1/1-2/b.jpg", "1/1-1/c.jpg")) { it }

        // The v14 report: 1/ must collapse as a whole even though it holds no files of its own.
        assertEquals(listOf("1"), tree.children.map { it.path })
        val one = tree.children.single()
        assertEquals(emptyList<String>(), one.directItems)
        assertEquals(3, one.totalCount)
        assertEquals(listOf("1/1-1", "1/1-2"), one.children.map { it.path })
        assertEquals(listOf("1-1", "1-2"), one.children.map { it.name })
        assertEquals(listOf(2, 1), one.children.map { it.directItems.size })
        assertEquals(listOf(1, 2), listOf(one.depth, one.children.first().depth))
    }

    @Test
    fun rootLevelFilesStayOnTheRootAndKeepInputOrder() {
        val tree = buildDirectoryTree(listOf("b.jpg", "a.jpg", "sub/c.jpg")) { it }

        assertEquals(DIRECTORY_TREE_ROOT_NAME, tree.name)
        assertEquals("", tree.path)
        assertEquals(0, tree.depth)
        assertEquals(listOf("b.jpg", "a.jpg"), tree.directItems)
        assertEquals(3, tree.totalCount)
        assertEquals(listOf("sub"), tree.children.map { it.name })
    }

    @Test
    fun anEmptyCatalogStillProducesAUsableRootLevel() {
        val tree = buildDirectoryTree(emptyList<String>()) { it }

        assertEquals(0, tree.totalCount)
        assertEquals(emptyList<DirectoryTreeNode<String>>(), tree.children)
    }

    @Test
    fun directorySegmentsDropTheFileNameAndAcceptWindowsSeparators() {
        assertEquals(listOf("1", "1-1"), directorySegments("1/1-1/a.jpg"))
        assertEquals(listOf("1", "1-1"), directorySegments("\\1\\1-1\\a.jpg"))
        assertEquals(emptyList<String>(), directorySegments("a.jpg"))
        assertEquals(emptyList<String>(), directorySegments(""))
    }

    @Test
    fun directoryContainmentUsesPathSegmentsSoASharedPrefixIsNotAMatch() {
        assertTrue(isWithinDirectory("1/1-1", "1"))
        assertTrue(isWithinDirectory("1", "1"))
        // "10" starts with "1" but is a different folder.
        assertFalse(isWithinDirectory("10", "1"))
        assertFalse(isWithinDirectory("2/1", "1"))
        // The root contains everything; it is controlled by the source enabled flag instead.
        assertTrue(isWithinDirectory("1/1-1", ""))
    }

    @Test
    fun wildcardCharactersInFolderNamesAreComparedLiterally() {
        // A LIKE-based query would treat these as patterns and stop the wrong folders.
        assertTrue(isWithinDirectory("100%_合集/图", "100%_合集"))
        assertFalse(isWithinDirectory("100X合集", "100%_合集"))
    }
}
