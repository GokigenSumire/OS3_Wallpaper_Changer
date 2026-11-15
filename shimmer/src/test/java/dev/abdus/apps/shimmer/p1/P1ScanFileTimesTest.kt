package dev.abdus.apps.shimmer.p1

import dev.abdus.apps.shimmer.p2.FileTimestamps
import org.junit.Assert.*
import org.junit.Test

class P1ScanFileTimesTest {
    private class Node(override val name: String, override val isDirectory: Boolean,
                       override val fileTimes: FileTimestamps, val nodes: List<P1TreeNode> = emptyList()) : P1TreeNode {
        override val id = name
        override val mimeType = if (isDirectory) "vnd.android.document/directory" else "image/jpeg"
        override val sizeBytes: Long? = null
        override fun children() = nodes
    }
    @Test fun walkCarriesTheOriginalRootAndEveryAncestorWithSourceRelativePaths() {
        val leaf = Node("photo.jpg", false, FileTimestamps(30, 31))
        val root = Node("root", true, FileTimestamps(10, 11), listOf(Node("child", true, FileTimestamps(20, 21), listOf(leaf))))
        val result = P1RecursiveScanner().scan(P1SourceSpec("s", "file:///root", P1SourceKind.DIRECTORY_TREE), root) as P1ScanOutcome.Complete
        val image = result.images.single()
        assertEquals("child/photo.jpg", image.relativePath)
        assertEquals(FileTimestamps(30, 31), image.fileTimes)
        assertEquals(listOf("", "child"), image.directoryTimes.map { it.path })
        assertEquals(listOf(10L, 20L), image.directoryTimes.map { it.times.createdMillis })
    }
}
