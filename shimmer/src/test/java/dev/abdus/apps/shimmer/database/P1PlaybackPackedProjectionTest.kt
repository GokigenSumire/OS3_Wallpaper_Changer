package dev.abdus.apps.shimmer.database

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class P1PlaybackPackedProjectionTest {
    @Test
    fun normalFourFieldsAndLongOrderRoundTripExactly() {
        val fields = listOf("id", "content://images/id", "image.png", "folder/image.png")
        assertEquals(P1PlaybackSourceLinkRow(fields[0], fields[1], fields[2], fields[3], Long.MAX_VALUE),
            P1PlaybackPackedProjection.decode(pack(fields, Long.MAX_VALUE)))
    }

    @Test
    fun unicodeAndSupplementaryAndCombiningAndBomArePreserved() {
        val fields = listOf("图😀", "content://图/😀/%2F", "é\u0301\uFEFF.png", "目录/𐀀/\uE000/图.png")
        assertEquals(P1PlaybackSourceLinkRow(fields[0], fields[1], fields[2], fields[3], Long.MIN_VALUE),
            P1PlaybackPackedProjection.decode(pack(fields, Long.MIN_VALUE)))
    }

    @Test
    fun emptyFieldsAndEmptyRowListAreValidNotMissing() {
        assertEquals(P1PlaybackSourceLinkRow("", "", "", "", 0), P1PlaybackPackedProjection.decode(pack(List(4) { "" })))
        assertEquals(emptyList<P1PlaybackSourceLinkRow>(), P1PlaybackPackedProjection.decode(emptyList()))
    }

    @Test
    fun fieldSeparatorsAndQuotesAndBackslashesAreNotAnEncodingProtocol() {
        val fields = listOf("id|32:", "content://uri/'\"\\|", "1:|%_\n.png", "a/b|c:d/图.png")
        val result = P1PlaybackPackedProjection.decode(pack(fields))!!
        assertEquals(fields, listOf(result.id, result.uri, result.displayName, result.relativePath))
    }

    @Test
    fun allFourNulFieldsSelectReferenceFallback() {
        for (index in 0..3) {
            val fields = MutableList(4) { "normal" }.also { it[index] = "before\u0000after" }
            assertNull(P1PlaybackPackedProjection.decode(pack(fields)))
        }
    }

    @Test
    fun headerTruncationInvalidCharactersAndOversizedLengthsAreRejected() {
        val valid = pack(listOf("a", "b", "c", "d"))
        for (size in 0 until 32) assertNull(P1PlaybackPackedProjection.decode(valid.copy(packedFields = valid.packedFields.copyOf(size))))
        for (character in listOf('g', '-', ' ', ':', '\u0000')) {
            assertNull(P1PlaybackPackedProjection.decode(valid.copy(packedFields = valid.packedFields.clone().also { it[0] = character.code.toByte() })))
        }
        for (header in listOf("ffffffff", "80000000", "7fffffff")) {
            assertNull(P1PlaybackPackedProjection.decode(valid.copy(packedFields =
                header.toByteArray(Charsets.US_ASCII) + valid.packedFields.drop(8))))
        }
    }

    @Test
    fun uppercaseLengthDigitsAreValidAndLengthIsBytesNotCharacters() {
        val bytes = pack(listOf("图😀图😀图😀", "uri", "name", "path"))
        val upper = bytes.copy(packedFields = bytes.packedFields.clone().also { body ->
            for (index in 0 until 32) body[index] = body[index].toInt().toChar().uppercaseChar().code.toByte()
        })
        assertEquals(P1PlaybackPackedProjection.decode(bytes), P1PlaybackPackedProjection.decode(upper))
    }

    @Test
    fun truncatedAndTrailingPayloadAreRejected() {
        val row = pack(listOf("id", "uri", "name", "path"))
        assertNull(P1PlaybackPackedProjection.decode(row.copy(packedFields = row.packedFields.dropLast(1).toByteArray())))
        assertNull(P1PlaybackPackedProjection.decode(row.copy(packedFields = row.packedFields + byteArrayOf(65))))
    }

    @Test
    fun invalidUtf8AndOverlongSurrogateOrOutOfRangeScalarsAreRejected() {
        val invalid = listOf(
            byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0xc1.toByte(), 0x81.toByte()),
            byteArrayOf(0xe0.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xf5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xc2.toByte()), byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x98.toByte()),
        )
        for (bytes in invalid) for (field in 0..3) {
            val fields = MutableList(4) { "valid".toByteArray() }.also { it[field] = bytes }
            assertNull(P1PlaybackPackedProjection.decode(packBytes(fields)))
        }
    }

    @Test
    fun utf8SequenceCannotBorrowContinuationFromAnotherField() {
        val fields = listOf(byteArrayOf(0xc2.toByte()), byteArrayOf(0xa2.toByte()), byteArrayOf(), byteArrayOf())
        assertNull(P1PlaybackPackedProjection.decode(packBytes(fields)))
    }

    @Test
    fun scalarBoundaryCorpusAgreesWithIndependentStandardDecoder() {
        val scalars = listOf(1, 0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff)
        for (scalar in scalars) {
            val value = String(Character.toChars(scalar))
            val decoded = P1PlaybackPackedProjection.decode(pack(List(4) { value }))
            if (isNonCharacter(scalar)) assertNull(decoded) else assertEquals(value, decoded!!.id)
        }
        for (first in 0..255) for (second in 0..255) {
            val bytes = byteArrayOf(first.toByte(), second.toByte())
            assertEquals("$first,$second", standardValid(bytes),
                P1PlaybackPackedProjection.decode(packBytes(List(4) { bytes })) != null)
        }
    }

    @Test
    fun randomizedThreeAndFourByteCorpusAgreesWithIndependentStandardDecoder() {
        val random = Random(48)
        repeat(10000) {
            val bytes = random.nextBytes(random.nextInt(3, 5))
            assertEquals(standardValid(bytes), P1PlaybackPackedProjection.decode(packBytes(List(4) { bytes })) != null)
        }
    }

    @Test
    fun validRowsBeforeOrAfterInvalidRowDoNotEscapeAsAPartialSource() {
        val good = pack(List(4) { "good" })
        val bad = pack(List(4) { "nul\u0000" })
        assertNull(P1PlaybackPackedProjection.decode(listOf(good, bad, good)))
        assertNull(P1PlaybackPackedProjection.decode(listOf(bad, good)))
    }

    @Test
    fun validUnicodeNoncharactersSelectPlatformReferenceForEveryField() {
        val points = (0xfdd0..0xfdef).toList() + (0..16).flatMap { listOf((it shl 16) + 0xfffe, (it shl 16) + 0xffff) }
        for (point in points) for (field in 0..3) {
            val value = "before" + String(Character.toChars(point)) + "after"
            val fields = MutableList(4) { "valid" }.also { it[field] = value }
            assertNull(P1PlaybackPackedProjection.decode(pack(fields)))
        }
        assertNotNull(P1PlaybackPackedProjection.decode(pack(List(4) { "\uFFFD" })))
    }

    @Test
    fun mutableByteInputCannotPolluteDecodedOutputAndNextReadObservesMutation() {
        val row = pack(List(4) { "good" })
        val result = P1PlaybackPackedProjection.decode(row)!!
        row.packedFields[32] = 0
        assertEquals("good", result.id)
        assertNull(P1PlaybackPackedProjection.decode(row))
    }

    @Test
    fun longUriAnd11890RowsRetainExactOrderAndEveryField() {
        val rows = (0 until 11890).map { index ->
            pack(listOf("id-$index", "content://图/" + "目录/".repeat(80) + index,
                "图$index.png", "目录/😀/$index.png"), index.toLong())
        }
        val result = P1PlaybackPackedProjection.decode(rows)!!
        assertEquals(11890, result.size)
        for ((index, row) in result.withIndex()) {
            assertEquals("id-$index", row.id)
            assertEquals("content://图/" + "目录/".repeat(80) + index, row.uri)
            assertEquals("图$index.png", row.displayName)
            assertEquals("目录/😀/$index.png", row.relativePath)
            assertEquals(index.toLong(), row.sortOrder)
        }
    }

    private fun standardValid(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return try {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            decoded.toString().codePoints().noneMatch(::isNonCharacter)
        } catch (_: CharacterCodingException) { false }
    }

    private fun isNonCharacter(point: Int): Boolean = point in 0xfdd0..0xfdef || (point and 0xfffe) == 0xfffe

    private fun pack(fields: List<String>, order: Long = 0) = packBytes(fields.map { it.toByteArray(Charsets.UTF_8) }, order)
    private fun packBytes(fields: List<ByteArray>, order: Long = 0): P1PlaybackPackedSourceLinkRow {
        val header = fields.joinToString("") { String.format(Locale.ROOT, "%08x", it.size) }.toByteArray(Charsets.US_ASCII)
        return P1PlaybackPackedSourceLinkRow(fields.fold(header) { result, bytes -> result + bytes }, order)
    }
}
