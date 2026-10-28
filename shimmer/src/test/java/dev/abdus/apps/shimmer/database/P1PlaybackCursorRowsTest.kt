package dev.abdus.apps.shimmer.database

import android.app.Application
import android.database.Cursor
import android.database.CursorWrapper
import android.database.MatrixCursor
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The same cursor ownership and getter path the production reader invokes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1PlaybackCursorRowsTest {
    @Test
    fun preservesEveryStringFieldLongAndRowOrderAndClosesOnce() {
        val cursor = tracked(listOf(arrayOf<Any>("id", "content://a", "名😀.png", "目录/图.png", Long.MIN_VALUE),
            arrayOf<Any>("id2", "content://b", "name2", "path2", Long.MAX_VALUE)))
        val rows = P1PlaybackCursorReader.readRows(cursor) {}
        assertEquals(listOf(P1PlaybackSourceLinkRow("id", "content://a", "名😀.png", "目录/图.png", Long.MIN_VALUE),
            P1PlaybackSourceLinkRow("id2", "content://b", "name2", "path2", Long.MAX_VALUE)), rows)
        assertEquals(1, cursor.closes)
        assertTrue(cursor.isClosed)
    }

    @Test
    fun emptyCursorStillChecksCancellationBeforeAndAfterAndCloses() {
        val cursor = tracked(emptyList())
        var checks = 0
        assertTrue(P1PlaybackCursorReader.readRows(cursor) { checks++ }.isEmpty())
        assertEquals(2, checks)
        assertEquals(1, cursor.closes)
    }

    @Test
    fun cancellationBeforeFirstMovePropagatesOriginalAndClosesCursor() {
        val cursor = tracked(listOf(row("one")))
        val failure = CancellationException("cancel before first move")
        try {
            P1PlaybackCursorReader.readRows(cursor) { throw failure }
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) { assertSame(failure, caught) }
        assertEquals(0, cursor.moves)
        assertEquals(1, cursor.closes)
    }

    @Test
    fun cancellationAfterOneRowDoesNotReturnPartialRowsAndClosesCursor() {
        val cursor = tracked(listOf(row("one"), row("two")))
        var checks = 0
        val failure = CancellationException("cancel before second getter")
        try {
            P1PlaybackCursorReader.readRows(cursor) { if (++checks == 3) throw failure }
            fail("Cancellation must not return a partial list")
        } catch (caught: CancellationException) { assertSame(failure, caught) }
        assertEquals(2, cursor.moves)
        assertEquals(1, cursor.closes)
    }

    @Test
    fun cancellationAfterLastMoveBeforeReturnStillRejectsWholeResult() {
        val cursor = tracked(listOf(row("one")))
        var checks = 0
        val failure = CancellationException("cancel before publication")
        try {
            P1PlaybackCursorReader.readRows(cursor) { if (++checks == 3) throw failure }
            fail("Cancellation at publication must propagate")
        } catch (caught: CancellationException) { assertSame(failure, caught) }
        assertEquals(1, cursor.closes)
    }

    @Test
    fun eachStringGetterFailurePropagatesOriginalWithoutPartialFallbackAndCloses() {
        for (field in 0..3) {
            val failure = IllegalStateException("getter $field failure")
            val cursor = object : TrackedCursor(matrix(listOf(row("one")))) {
                override fun getString(columnIndex: Int): String {
                    if (columnIndex == field) throw failure
                    return super.getString(columnIndex)
                }
            }
            try {
                P1PlaybackCursorReader.readRows(cursor) {}
                fail("Getter failure must propagate")
            } catch (caught: IllegalStateException) { assertSame(failure, caught) }
            assertEquals(1, cursor.closes)
        }
    }

    @Test
    fun integerGetterFailurePropagatesAndClosesWithoutSortingCoercion() {
        val failure = IllegalArgumentException("integer getter failure")
        val cursor = object : TrackedCursor(matrix(listOf(row("one")))) {
            override fun getLong(columnIndex: Int): Long = throw failure
        }
        try {
            P1PlaybackCursorReader.readRows(cursor) {}
            fail("Getter failure must propagate")
        } catch (caught: IllegalArgumentException) { assertSame(failure, caught) }
        assertEquals(1, cursor.closes)
    }

    @Test
    fun moveFailurePropagatesAndClosesWithoutReturningPreviousRows() {
        val failure = IllegalStateException("move failure")
        val cursor = object : TrackedCursor(matrix(listOf(row("one"), row("two")))) {
            override fun moveToNext(): Boolean = if (moves == 1) throw failure else super.moveToNext()
        }
        try {
            P1PlaybackCursorReader.readRows(cursor) {}
            fail("Move failure must propagate")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        assertEquals(1, cursor.closes)
    }

    @Test
    fun wrongProjectionFailsClosedAndClosesBeforeReadingAnyRow() {
        val cursor = TrackedCursor(MatrixCursor(arrayOf("only")))
        try {
            P1PlaybackCursorReader.readRows(cursor) {}
            fail("Unexpected projection must fail")
        } catch (caught: IllegalStateException) { assertEquals("Unexpected playback cursor projection", caught.message) }
        assertEquals(0, cursor.moves)
        assertEquals(1, cursor.closes)
    }

    @Test
    fun closeFailureDoesNotReplaceGetterFailureAndRemainsSuppressed() {
        val failure = IllegalStateException("getter failure")
        val closeFailure = IllegalArgumentException("close failure")
        val cursor = object : TrackedCursor(matrix(listOf(row("one")))) {
            override fun getString(columnIndex: Int): String = throw failure
            override fun close() { super.close(); throw closeFailure }
        }
        try {
            P1PlaybackCursorReader.readRows(cursor) {}
            fail("Getter failure must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
            assertEquals(listOf(closeFailure), caught.suppressed.toList())
        }
        assertEquals(1, cursor.closes)
    }

    @Test
    fun successfulReadCloseFailureIsNotHiddenAsSuccessfulOrEmptyCatalog() {
        val closeFailure = IllegalArgumentException("close failure")
        val cursor = object : TrackedCursor(matrix(listOf(row("one")))) {
            override fun close() { super.close(); throw closeFailure }
        }
        try {
            P1PlaybackCursorReader.readRows(cursor) {}
            fail("Close failure must propagate")
        } catch (caught: IllegalArgumentException) { assertSame(closeFailure, caught) }
        assertEquals(1, cursor.closes)
    }

    @Test
    fun doesNotInventIdentityOrNormalizeNulUnicodeAndEmptyText() {
        val cursor = tracked(listOf(arrayOf<Any>("", "content://a\u0000suffix", "名\uFDD0.png", "\uFFFF/\u0000", 0L)))
        assertEquals(P1PlaybackSourceLinkRow("", "content://a\u0000suffix", "名\uFDD0.png", "\uFFFF/\u0000", 0L),
            P1PlaybackCursorReader.readRows(cursor) {}.single())
    }

    private fun row(id: String): Array<Any> = arrayOf(id, "content://$id", "$id.png", "$id.png", 0L)
    private fun matrix(rows: List<Array<Any>>) = MatrixCursor(arrayOf("id", "uri", "displayName", "relativePath", "sortOrder"))
        .apply { rows.forEach(::addRow) }
    private fun tracked(rows: List<Array<Any>>) = TrackedCursor(matrix(rows))
    private open class TrackedCursor(cursor: Cursor) : CursorWrapper(cursor) {
        var closes = 0
        var moves = 0
        override fun moveToNext(): Boolean { moves++; return super.moveToNext() }
        override fun close() { closes++; super.close() }
    }
}
