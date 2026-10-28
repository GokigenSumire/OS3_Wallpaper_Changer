package dev.abdus.apps.shimmer.p1

import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P1SourcePreviewTest {
    @Test
    fun singleAndMultipleSourcesChooseFirstImageWhileDirectoryChoosesLast() {
        val images = listOf(
            image("one", "子目录/one.jpg"),
            image("two", "子目录/two.jpg"),
        )

        assertEquals("one", chooseSourcePreview("SINGLE_DOCUMENT", images).id)
        assertEquals("one", chooseSourcePreview("MULTIPLE_DOCUMENTS", images).id)
        assertEquals("two", chooseSourcePreview("DIRECTORY_TREE", images).id)
    }

    @Test
    fun cardFileSummaryListsTheFirstFewNamesAndMarksTheRest() {
        val images = listOf(
            image("one", "a/one.jpg"),
            image("two", "a/two.jpg"),
            image("three", "a/three.jpg"),
            image("four", "a/four.jpg"),
        )

        assertEquals("one.jpg、two.jpg、three.jpg 等", directoryFileSummary(images))
        assertEquals("one.jpg、two.jpg", directoryFileSummary(images.take(2)))
        assertTrue(directoryFileSummary(emptyList()).isBlank())
    }

    private fun image(id: String, path: String) = P1ImageRecordEntity(
        id = id,
        uri = "file:///$id.jpg",
        displayName = "$id.jpg",
        relativePath = path,
    )
}
