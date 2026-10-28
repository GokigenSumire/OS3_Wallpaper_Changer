package dev.abdus.apps.shimmer.database

import dev.abdus.apps.shimmer.p2.PlaybackItem
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class P1PlaybackItemMappingMemoTest {
    private fun image(id: String, source: String = "source") = P1PlaybackImage(
        id, "content://images/$id", "$id.png", "dir/$id.png", source, 9L, 3,
        "DIRECTORY_TREE", "content://roots/$source",
    )
    private fun certified(vararg ids: String) = P1ImmutablePlaybackImages(ids.map { image(it) })
    private fun baseline(images: List<P1PlaybackImage>, disabled: Set<String>) = images
        .filterNot { it.uri in disabled }.map { PlaybackItem(it.uri, it.displayName, it.relativePath,
            it.sourceId, it.sourceOrder, it.sourceKind, it.sourceRootUri) }

    @Test fun certifiedImmutableProjectionAndExactDisabledContentsReuseMapping() {
        val images = certified("a", "b")
        val memo = P1PlaybackItemMappingMemo()
        val first = memo.map(images, setOf("content://images/b"))
        assertSame(first, memo.map(images, linkedSetOf("content://images/b")))
        assertEquals(baseline(images, setOf("content://images/b")), first)
    }

    @Test fun newProjectionInstanceMissesEvenWhenAllContentsAreEqual() {
        val memo = P1PlaybackItemMappingMemo()
        val first = memo.map(certified("a", "b"), emptySet())
        val second = memo.map(certified("a", "b"), emptySet())
        assertEquals(first, second)
        assertNotSame(first, second)
    }

    @Test fun everyMappedFieldAndInsertionOrderRemainPartOfFreshResults() {
        val original = image("a")
        val variants = listOf(original.copy(uri = "other"), original.copy(displayName = "other"),
            original.copy(relativePath = "other"), original.copy(sourceId = "other"),
            original.copy(sourceOrder = Long.MAX_VALUE), original.copy(sourceKind = "other"),
            original.copy(sourceRootUri = "other"))
        val memo = P1PlaybackItemMappingMemo()
        val first = memo.map(P1ImmutablePlaybackImages(listOf(original, image("b"))), emptySet())
        for (variant in variants) {
            val changed = P1ImmutablePlaybackImages(listOf(variant, image("b")))
            assertEquals(baseline(changed, emptySet()), memo.map(changed, emptySet()))
            assertNotEquals(first, memo.map(changed, emptySet()))
        }
        val reversed = P1ImmutablePlaybackImages(listOf(image("b"), original))
        assertEquals(listOf("content://images/b", "content://images/a"), memo.map(reversed, emptySet()).map { it.id })
    }

    @Test fun immutableWrapperCopiesMutableInputAndRejectsEveryMutationPath() {
        val input = arrayListOf(image("a"), image("b"))
        val images = P1ImmutablePlaybackImages(input)
        input[0] = image("changed")
        input.clear()
        assertEquals(listOf("a", "b"), images.map { it.id })
        val mutable = images as MutableList<P1PlaybackImage>
        for (mutate in listOf<() -> Unit>({ mutable[0] = image("changed") }, { mutable.clear() },
            { mutable.add(image("changed")) }, { mutable.removeAt(0) },
            { mutable.listIterator().apply { next(); set(image("changed")) } },
            { mutable.subList(0, 1).clear() })) {
            try { mutate(); fail("受控投影不允许修改") } catch (_: UnsupportedOperationException) { }
        }
        assertEquals(listOf("a", "b"), images.map { it.id })
    }

    @Test fun ordinaryMutableListNeverUsesIdentityAsAnUnchangedCertificate() {
        val images = arrayListOf(image("a"), image("b"))
        val memo = P1PlaybackItemMappingMemo()
        val first = memo.map(images, emptySet())
        assertNotSame(first, memo.map(images, emptySet()))
        images[0] = images[0].copy(displayName = "changed", relativePath = "new/path")
        val changed = memo.map(images, emptySet())
        assertEquals("changed", changed.first().displayName)
        assertEquals("new/path", changed.first().relativePath)
        assertEquals("a.png", first.first().displayName)
    }

    @Test fun arbitraryReadOnlyViewOverMutableBackingIsNotCertified() {
        val backing = arrayListOf(image("a"))
        val view = java.util.Collections.unmodifiableList(backing)
        val memo = P1PlaybackItemMappingMemo()
        memo.map(view, emptySet())
        backing[0] = image("b")
        assertEquals("content://images/b", memo.map(view, emptySet()).single().id)
    }

    @Test fun disabledInputIsFrozenAndEachCallUsesItsActualContents() {
        val images = certified("a", "b", "c")
        val disabled = hashSetOf("content://images/b")
        val memo = P1PlaybackItemMappingMemo()
        val first = memo.map(images, disabled)
        disabled.clear()
        disabled.add("content://images/a")
        val second = memo.map(images, disabled)
        assertEquals(listOf("content://images/a", "content://images/c"), first.map { it.id })
        assertEquals(listOf("content://images/b", "content://images/c"), second.map { it.id })
        assertSame(first, memo.map(images, setOf("content://images/b")))
    }

    @Test fun equalSizeAndHashDisabledSetsAreNotTreatedAsEqual() {
        assertEquals(setOf("Aa").hashCode(), setOf("BB").hashCode())
        val images = P1ImmutablePlaybackImages(listOf(image("a").copy(uri = "Aa"), image("b").copy(uri = "BB")))
        val memo = P1PlaybackItemMappingMemo()
        assertEquals(listOf("BB"), memo.map(images, setOf("Aa")).map { it.id })
        assertEquals(listOf("Aa"), memo.map(images, setOf("BB")).map { it.id })
    }

    @Test fun atMostTwoEntriesAreRetainedAndHitRefreshesTheirRecency() {
        val a = certified("a"); val b = certified("b"); val c = certified("c")
        val memo = P1PlaybackItemMappingMemo()
        val firstA = memo.map(a, emptySet()); val firstB = memo.map(b, emptySet())
        assertSame(firstA, memo.map(a, emptySet()))
        memo.map(c, emptySet())
        assertSame(firstA, memo.map(a, emptySet()))
        assertNotSame(firstB, memo.map(b, emptySet()))
    }

    @Test fun emptyAndFullyDisabledCatalogsRetainTheirCorrectImmutableValues() {
        val memo = P1PlaybackItemMappingMemo()
        assertTrue(memo.map(P1ImmutablePlaybackImages(emptyList()), emptySet()).isEmpty())
        val first = memo.map(certified("a"), setOf("content://images/a"))
        assertTrue(first.isEmpty())
        try { (first as MutableList<PlaybackItem>).add(PlaybackItem("bad", "bad")); fail("输出必须冻结") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun mappedOutputCannotBeRewrittenThroughCallerMutation() {
        val images = certified("a", "b")
        val memo = P1PlaybackItemMappingMemo()
        val first = memo.map(images, emptySet())
        try { (first as MutableList<PlaybackItem>)[0] = PlaybackItem("bad", "bad"); fail("输出必须冻结") }
        catch (_: UnsupportedOperationException) { }
        assertSame(first, memo.map(images, emptySet()))
        assertEquals(baseline(images, emptySet()), first)
    }

    @Test fun simultaneousMappingsShareOnlyCompleteImmutableResults() {
        val images = certified("a", "b", "c")
        val memo = P1PlaybackItemMappingMemo()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((0 until 20).map { Callable { memo.map(images, setOf("content://images/b")) } })
                .map { it.get() }
            for (result in results) assertSame(results.first(), result)
            assertEquals(baseline(images, setOf("content://images/b")), results.first())
        } finally { pool.shutdownNow() }
    }

    @Test fun elevenThousandImagesMapOnceAndRemainEquivalentAcrossStableActions() {
        val images = P1ImmutablePlaybackImages((1..11_890).map { image(it.toString()) })
        val disabled = (1..11_890 step 37).map { "content://images/$it" }.toSet()
        val memo = P1PlaybackItemMappingMemo()
        val first = memo.map(images, disabled)
        repeat(20) { assertSame(first, memo.map(images, disabled.toSet())) }
        assertEquals(baseline(images, disabled), first)
        assertSame(images.first { it.uri !in disabled }.sourceRootUri, first.first().sourceRootUri)
    }
}
