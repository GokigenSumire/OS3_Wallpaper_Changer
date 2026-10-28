package dev.abdus.apps.shimmer.p1

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P1RecursiveScannerTest {
    private class Node(
        override val id: String,
        override val name: String,
        override val isDirectory: Boolean,
        override val mimeType: String? = null,
        override val sizeBytes: Long? = 1,
        private val accessUri: String? = null,
        private val childLoader: () -> Iterable<P1TreeNode> = { emptyList() },
    ) : P1TreeNode {
        override val uri: String = accessUri ?: id
        override fun children(): Iterable<P1TreeNode> = childLoader()
    }

    private fun file(id: String, name: String, mime: String? = null) =
        Node(id, name, isDirectory = false, mimeType = mime)

    private fun dir(id: String, name: String, children: () -> Iterable<P1TreeNode>) =
        Node(id, name, isDirectory = true, childLoader = children)

    private val source = P1SourceSpec("source-1", "content://tree/root", P1SourceKind.DIRECTORY_TREE)

    @Test
    fun walksNestedDirectoriesAndSortsByRelativePath() {
        val root = dir("root", "Pictures") {
            listOf(
                dir("b", "B") { listOf(file("b-2", "two.png"), file("b-1", "one.txt")) },
                file("a", "a.jpg"),
                dir("a-dir", "A") { listOf(file("a-2", "z.webp")) },
            )
        }

        val result = P1RecursiveScanner().scan(source, root)

        assertTrue(result is P1ScanOutcome.Complete)
        assertEquals(
            listOf("a.jpg", "A/z.webp", "B/two.png"),
            (result as P1ScanOutcome.Complete).images.map { it.relativePath },
        )
    }

    @Test
    fun duplicateNodeIdsAreVisitedOnceEvenWhenProviderRepeatsThem() {
        val shared = file("same", "same.jpg")
        val root = dir("root", "Pictures") {
            listOf(dir("one", "One") { listOf(shared) }, dir("two", "Two") { listOf(shared) })
        }

        val result = P1RecursiveScanner().scan(source, root)

        assertEquals(1, (result as P1ScanOutcome.Complete).images.size)
    }

    @Test
    fun cancellationIsNotACompleteEmptyScan() {
        val root = dir("root", "Pictures") { listOf(file("one", "one.jpg")) }
        val result = P1RecursiveScanner().scan(source, root, checkActive = { throw CancellationException() })

        assertTrue(result is P1ScanOutcome.Cancelled)
        assertTrue(result !is P1ScanOutcome.Complete)
    }

    @Test
    fun partialProviderFailureDoesNotReturnPartialImages() {
        val root = dir("root", "Pictures") {
            listOf(
                file("one", "one.jpg"),
                dir("remote", "Remote") { throw P1TreeReadException(P1ScanFailure.INCOMPLETE_RESULT) },
            )
        }

        val result = P1RecursiveScanner().scan(source, root)

        assertEquals(P1ScanFailure.INCOMPLETE_RESULT, (result as P1ScanOutcome.Failed).reason)
        assertEquals(1, result.discoveredImages)
    }

    @Test
    fun directOnlyModeDoesNotDescendIntoSubdirectories() {
        val root = dir("root", "Pictures") {
            listOf(file("top", "top.jpg"), dir("nested", "Nested") { listOf(file("deep", "deep.jpg")) })
        }

        val result = P1RecursiveScanner().scan(source.copy(includeSubdirectories = false), root)

        assertEquals(listOf("top.jpg"), (result as P1ScanOutcome.Complete).images.map { it.relativePath })
    }

    @Test
    fun accessUriMayDifferFromGlobalDocumentIdentity() {
        val identity = "content://provider/document/42"
        val access = "content://provider/tree/primary%3APictures/document/42"
        val root = dir("root", "Pictures") {
            listOf(file(identity, "one.jpg").copyWithUri(access))
        }

        val result = P1RecursiveScanner().scan(source, root) as P1ScanOutcome.Complete

        assertEquals(identity, result.images.single().stableKey.removePrefix("image:"))
        assertEquals(access, result.images.single().uri)
    }

    @Test
    fun sameDocumentIdentityIsStableAcrossDifferentSourceRoutes() {
        val identity = "content://provider/document/42"
        val first = P1RecursiveScanner().scan(
            source.copy(sourceId = "tree-source"),
            dir("root", "Pictures") { listOf(file(identity, "one.jpg")) },
        ) as P1ScanOutcome.Complete
        val second = P1RecursiveScanner().scan(
            source.copy(sourceId = "single-source"),
            dir("root", "Pictures") { listOf(file(identity, "one.jpg")) },
        ) as P1ScanOutcome.Complete

        assertEquals(first.images.single().stableKey, second.images.single().stableKey)
    }

    @Test
    fun cancellationDuringChildEnumerationCannotComplete() {
        var cancelled = false
        val root = object : P1TreeNode {
            override val id = "root"
            override val name = "Pictures"
            override val isDirectory = true
            override val mimeType: String? = null
            override val sizeBytes: Long? = null
            override fun children(): Iterable<P1TreeNode> = error("Cancellation-aware overload required")
            override fun children(checkActive: () -> Unit): Iterable<P1TreeNode> {
                cancelled = true
                checkActive()
                return listOf(file("one", "one.jpg"))
            }
        }

        val result = P1RecursiveScanner().scan(source, root, checkActive = {
            if (cancelled) throw CancellationException()
        })

        assertTrue(result is P1ScanOutcome.Cancelled)
    }

    @Test
    fun rootNameRepeatedAsSubdirectoryIsNotStrippedFromRelativePath() {
        val root = dir("root", "Pictures") {
            listOf(dir("nested", "Pictures") { listOf(file("one", "one.jpg")) })
        }

        val result = P1RecursiveScanner().scan(source, root) as P1ScanOutcome.Complete

        assertEquals("Pictures/one.jpg", result.images.single().relativePath)
    }

    @Test
    fun documentIdentityCanonicalizesTreeAndSingleRoutes() {
        val tree = Uri.parse("content://provider/tree/primary%3APictures/document/42")
        val single = Uri.parse("content://provider/document/42")

        assertEquals(p1DocumentIdentity(single), p1DocumentIdentity(tree))
    }

    private fun Node.copyWithUri(uri: String): Node = Node(
        id = id,
        name = name,
        isDirectory = isDirectory,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        accessUri = uri,
    )
}
