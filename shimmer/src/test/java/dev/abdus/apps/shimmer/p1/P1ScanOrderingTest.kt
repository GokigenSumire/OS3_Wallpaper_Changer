package dev.abdus.apps.shimmer.p1

import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class P1ScanOrderingTest {
    private class Node(
        override val id: String,
        override val name: String,
        override val isDirectory: Boolean = false,
        override val uri: String = id,
        private val load: () -> Iterable<P1TreeNode> = { emptyList() },
    ) : P1TreeNode {
        override val mimeType: String? = if (isDirectory) null else "image/png"
        override val sizeBytes: Long? = 1
        override fun children(): Iterable<P1TreeNode> = load()
    }

    private fun image(id: String, path: String, uri: String = id) =
        P1ScannedImage(id, "source", uri, path.substringAfterLast('/'), path, 1)

    private fun originalNodes(nodes: Iterable<P1TreeNode>) = nodes.sortedWith(
        compareBy({ it.isDirectory }, { it.name.lowercase() }, { it.name }, { it.id }),
    )

    private fun originalImages(images: Iterable<P1ScannedImage>) = images.sortedWith(
        compareBy({ it.relativePath.lowercase() }, { it.relativePath }, { it.stableKey }),
    )

    @Test
    fun mixedNamesAndDirectoryFlagsMatchTheOriginalComparator() {
        val nodes = listOf(
            Node("dir-z", "z", true), Node("b", "B.png"), Node("dir-a", "a", true),
            Node("a2", "a.png"), Node("a1", "A.png"), Node("unicode", "图片.png"),
        )
        assertEquals(originalNodes(nodes), orderP1TreeNodes(nodes))
        assertEquals(listOf("A.png", "a.png", "B.png", "图片.png", "a", "z"),
            orderP1TreeNodes(nodes).map { it.name })
    }

    @Test
    fun filesPrecedeDirectoriesEvenWhenDirectoryNameSortsFirst() {
        val file = Node("file", "zzz.png")
        val directory = Node("directory", "AAA", true)
        assertEquals(listOf(file, directory), orderP1TreeNodes(listOf(directory, file)))
    }

    @Test
    fun sameFoldedNameUsesOriginalCaseBeforeIdentity() {
        val nodes = listOf(Node("a", "a.png"), Node("z", "A.png"))
        assertEquals(listOf("z", "a"), orderP1TreeNodes(nodes).map { it.id })
        assertEquals(originalNodes(nodes), orderP1TreeNodes(nodes))
    }

    @Test
    fun sameNameUsesTheOriginalDocumentIdentityTieBreak() {
        val nodes = listOf(Node("content://p/document/2", "same.png"), Node("content://p/document/10", "same.png"))
        assertEquals(originalNodes(nodes), orderP1TreeNodes(nodes))
        assertEquals(listOf("content://p/document/10", "content://p/document/2"), orderP1TreeNodes(nodes).map { it.id })
    }

    @Test
    fun equalNodeSortKeysKeepTheOriginalInputOrderAndAccessUris() {
        val first = Node("same", "same.png", uri = "content://p/tree/one/document/same")
        val second = Node("same", "same.png", uri = "content://p/tree/two/document/same")
        val ordered = orderP1TreeNodes(listOf(first, second))
        assertSame(first, ordered[0])
        assertSame(second, ordered[1])
    }

    @Test
    fun finalPathsKeepCaseAndStableIdentityTieBreaks() {
        val images = listOf(image("z", "a/same.png"), image("b", "A/same.png"),
            image("a", "A/same.png"), image("p", "z.png"), image("q", "图片.png"))
        assertEquals(originalImages(images), orderP1ScannedImages(images))
        assertEquals(listOf("a", "b", "z", "p", "q"), orderP1ScannedImages(images).map { it.stableKey })
    }

    @Test
    fun duplicateFinalSortKeysStayStableWithoutDeduplication() {
        val first = image("same", "same.png", "content://p/tree/one/document/same")
        val second = image("same", "same.png", "content://p/tree/two/document/same")
        val ordered = orderP1ScannedImages(listOf(first, second))
        assertEquals(2, ordered.size)
        assertSame(first, ordered[0])
        assertSame(second, ordered[1])
    }

    @Test
    fun turkishDefaultLocaleDoesNotChangeTheNoLocaleLowercaseContract() {
        synchronized(Locale::class.java) {
            val original = Locale.getDefault()
            try {
                val nodes = listOf(Node("I", "I.png"), Node("i", "i.png"), Node("dotted", "İ.png"), Node("dotless", "ı.png"))
                val images = nodes.map { image(it.id, "${it.name}/image.png") }
                Locale.setDefault(Locale.US)
                val expectedNodes = originalNodes(nodes)
                val expectedImages = originalImages(images)
                Locale.setDefault(Locale.forLanguageTag("tr-TR"))
                assertEquals(expectedNodes, originalNodes(nodes))
                assertEquals(expectedNodes, orderP1TreeNodes(nodes))
                assertEquals(expectedImages, originalImages(images))
                assertEquals(expectedImages, orderP1ScannedImages(images))
            } finally {
                Locale.setDefault(original)
            }
        }
    }

    @Test
    fun shuffledTreesAlwaysMatchTheOriginalComparator() {
        val nodes = (0 until 180).map { index ->
            Node("id-${index % 13}", "${listOf("A", "a", "B", "İ", "ß", "图")[index % 6]}-${index % 17}.png", index % 4 == 0)
        }
        repeat(15) { seed ->
            val shuffled = nodes.shuffled(Random(seed))
            assertEquals(originalNodes(shuffled), orderP1TreeNodes(shuffled))
        }
    }

    @Test
    fun shuffledImagesAlwaysMatchTheOriginalComparator() {
        val images = (0 until 180).map { index ->
            image("id-${index % 13}", "${listOf("A", "a", "B", "İ", "ß", "图")[index % 6]}/${index % 17}.png", "uri-$index")
        }
        repeat(15) { seed ->
            val shuffled = images.shuffled(Random(seed))
            assertEquals(originalImages(shuffled), orderP1ScannedImages(shuffled))
        }
    }

    @Test
    fun treeNamesAreLoweredExactlyOncePerEntryNotPerComparison() {
        var calls = 0
        val nodes = (0 until 2048).map { Node("$it", "Mixed-${2048 - it}.png") }
        val ordered = orderP1TreeNodes(nodes, lowercase = { calls++; it.lowercase() })
        assertEquals(2048, calls)
        assertEquals(originalNodes(nodes), ordered)
    }

    @Test
    fun finalPathsAreLoweredExactlyOncePerEntryNotPerComparison() {
        var calls = 0
        val images = (0 until 2048).map { image("$it", "Mixed/Path-${2048 - it}.png") }
        val ordered = orderP1ScannedImages(images, lowercase = { calls++; it.lowercase() })
        assertEquals(2048, calls)
        assertEquals(originalImages(images), ordered)
    }

    @Test
    fun repeatedNodeReferencesAreNotLostByTheOrderingHelper() {
        val repeated = Node("same", "same.png")
        var calls = 0
        val ordered = orderP1TreeNodes(listOf(repeated, repeated, repeated), lowercase = { calls++; it.lowercase() })
        assertEquals(3, calls)
        assertEquals(listOf(repeated, repeated, repeated), ordered)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsCheckedBetweenNodeKeyPreparations() {
        var checks = 0
        orderP1TreeNodes((0 until 20).map { Node("$it", "$it.png") }, checkActive = {
            if (++checks == 4) throw CancellationException()
        })
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsCheckedBetweenFinalImageKeyPreparations() {
        var checks = 0
        orderP1ScannedImages((0 until 20).map { image("$it", "$it.png") }, checkActive = {
            if (++checks == 4) throw CancellationException()
        })
    }

    @Test
    fun overlappingIdentityKeepsTheSameFirstPathAfterProviderShuffle() {
        val shared = Node("shared", "image.png", uri = "content://p/tree/root/document/shared")
        val a = Node("a", "A", true, load = { listOf(shared) })
        val b = Node("b", "B", true, load = { listOf(shared) })
        val source = P1SourceSpec("s", "content://p/tree/root", P1SourceKind.DIRECTORY_TREE)
        repeat(8) { seed ->
            val root = Node("root", "Root", true, load = { listOf(a, b).shuffled(Random(seed)) })
            val outcome = P1RecursiveScanner().scan(source, root) as P1ScanOutcome.Complete
            assertEquals(1, outcome.images.size)
            assertEquals("A/image.png", outcome.images.single().relativePath)
            assertEquals("image:shared", outcome.images.single().stableKey)
            assertEquals(shared.uri, outcome.images.single().uri)
        }
    }

    @Test
    fun directOnlyScanningDoesNotStartSubdirectoryEnumeration() {
        val nested = Node("nested", "Nested", true, load = { error("must not descend") })
        val root = Node("root", "Root", true, load = { listOf(nested, Node("top", "top.png")) })
        val source = P1SourceSpec("s", "file:///root", P1SourceKind.DIRECTORY_TREE, includeSubdirectories = false)
        val outcome = P1RecursiveScanner().scan(source, root) as P1ScanOutcome.Complete
        assertEquals(listOf("top.png"), outcome.images.map { it.relativePath })
    }

    @Test
    fun cancelledChildKeyCollectionNeverPublishesPartialComplete() {
        val source = P1SourceSpec("s", "file:///root", P1SourceKind.DIRECTORY_TREE)
        var collecting = false
        var checks = 0
        val root = Node("root", "Root", true, load = {
            collecting = true
            (0 until 20).map { Node("$it", "$it.png") }
        })
        val outcome = P1RecursiveScanner().scan(source, root, checkActive = {
            if (collecting && ++checks == 4) throw CancellationException()
        })
        assertTrue(outcome is P1ScanOutcome.Cancelled)
        assertTrue(P1ScanCommitGate.decide(outcome) is P1ScanCommitDecision.Preserve)
    }

    @Test
    fun metadataAndPermissionFailuresRemainPreserveOutcomes() {
        val source = P1SourceSpec("s", "file:///root", P1SourceKind.DIRECTORY_TREE)
        listOf(P1ScanFailure.METADATA, P1ScanFailure.INCOMPLETE_RESULT, P1ScanFailure.READ).forEach { reason ->
            val root = Node("root", "Root", true, load = { throw P1TreeReadException(reason) })
            val outcome = P1RecursiveScanner().scan(source, root) as P1ScanOutcome.Failed
            assertEquals(reason, outcome.reason)
            assertTrue(P1ScanCommitGate.decide(outcome) is P1ScanCommitDecision.Preserve)
        }
        val root = Node("root", "Root", true, load = { throw SecurityException() })
        val outcome = P1RecursiveScanner().scan(source, root) as P1ScanOutcome.Failed
        assertEquals(P1ScanFailure.PERMISSION, outcome.reason)
        assertTrue(P1ScanCommitGate.decide(outcome) is P1ScanCommitDecision.Preserve)
    }

    @Test
    fun finalRelativePathSortStillDiffersFromFileBeforeDirectoryWalk() {
        val root = Node("root", "Root", true, load = {
            listOf(Node("z", "z.png"), Node("a", "A", true, load = { listOf(Node("nested", "image.png")) }))
        })
        val outcome = P1RecursiveScanner().scan(
            P1SourceSpec("s", "file:///root", P1SourceKind.DIRECTORY_TREE), root,
        ) as P1ScanOutcome.Complete
        assertEquals(listOf("A/image.png", "z.png"), outcome.images.map { it.relativePath })
    }

    @Test
    fun successfulProgressKeepsStableVisitedAndDiscoveredCounts() {
        val root = Node("root", "Root", true, load = { listOf(Node("b", "b.png"), Node("a", "a.png")) })
        val events = mutableListOf<P1ScanProgress>()
        val outcome = P1RecursiveScanner().scan(
            P1SourceSpec("s", "file:///root", P1SourceKind.DIRECTORY_TREE), root, onProgress = { events += it },
        ) as P1ScanOutcome.Complete
        assertEquals(listOf(1 to 0, 2 to 0, 2 to 1, 3 to 1, 3 to 2),
            events.map { it.visitedNodes to it.discoveredImages })
        assertEquals(listOf("a.png", "b.png"), outcome.images.map { it.relativePath })
    }
}
