package dev.abdus.apps.shimmer.database

/** Four exact SQLite TEXT byte sequences in one BLOB, plus the unchanged INTEGER ordering key. */
data class P1PlaybackPackedSourceLinkRow(val packedFields: ByteArray, val sortOrder: Long)

/**
 * The SQL header is four eight-digit hexadecimal UTF-8 byte lengths. A row is accepted only when
 * its entire payload is well-formed UTF-8 without NUL/noncharacters, and its lengths consume the
 * BLOB exactly. Some platform SQLite TEXT decoders replace Unicode noncharacters; use the real
 * reference decoder for those valid-but-unusual scalar values instead of guessing conversion.
 * Anything unusual selects the unchanged five-column SQLite TEXT query for the whole source;
 * no partly decoded source escapes and no identity, metadata or permission result is cached.
 */
object P1PlaybackPackedProjection {
    private const val HEADER_SIZE = 32

    fun decode(rows: List<P1PlaybackPackedSourceLinkRow>): List<P1PlaybackSourceLinkRow>? {
        val decoded = ArrayList<P1PlaybackSourceLinkRow>(rows.size)
        for (row in rows) decoded.add(decode(row) ?: return null)
        return decoded
    }

    fun decode(row: P1PlaybackPackedSourceLinkRow): P1PlaybackSourceLinkRow? {
        val bytes = row.packedFields
        if (bytes.size < HEADER_SIZE) return null
        val lengths = IntArray(4)
        var total = HEADER_SIZE.toLong()
        for (field in 0..3) {
            var length = 0L
            for (index in field * 8 until field * 8 + 8) {
                val digit = when (val character = bytes[index].toInt() and 0xff) {
                    in '0'.code..'9'.code -> character - '0'.code
                    in 'a'.code..'f'.code -> character - 'a'.code + 10
                    in 'A'.code..'F'.code -> character - 'A'.code + 10
                    else -> return null
                }
                length = (length shl 4) + digit
            }
            if (length > Int.MAX_VALUE) return null
            lengths[field] = length.toInt()
            total += length
        }
        if (total != bytes.size.toLong()) return null
        var offset = HEADER_SIZE
        val fields = arrayOfNulls<String>(4)
        for (field in 0..3) {
            val end = offset + lengths[field]
            if (!isExactUtf8(bytes, offset, end)) return null
            fields[field] = String(bytes, offset, lengths[field], Charsets.UTF_8)
            offset = end
        }
        return P1PlaybackSourceLinkRow(fields[0]!!, fields[1]!!, fields[2]!!, fields[3]!!, row.sortOrder)
    }

    /** UTF-8 scalar validation without decoding replacement characters or allocating per byte. */
    private fun isExactUtf8(bytes: ByteArray, start: Int, end: Int): Boolean {
        var index = start
        fun continuation(position: Int): Boolean = position < end &&
            (bytes[position].toInt() and 0xff) in 0x80..0xbf
        while (index < end) {
            val first = bytes[index].toInt() and 0xff
            when {
                first in 1..0x7f -> index++
                first in 0xc2..0xdf -> {
                    if (!continuation(index + 1)) return false
                    index += 2
                }
                first in 0xe0..0xef -> {
                    if (index + 2 >= end || !continuation(index + 2)) return false
                    val second = bytes[index + 1].toInt() and 0xff
                    val secondRange = when (first) {
                        0xe0 -> 0xa0..0xbf
                        0xed -> 0x80..0x9f
                        else -> 0x80..0xbf
                    }
                    if (second !in secondRange) return false
                    val codePoint = ((first and 0x0f) shl 12) or ((second and 0x3f) shl 6) or
                        (bytes[index + 2].toInt() and 0x3f)
                    if (isNonCharacter(codePoint)) return false
                    index += 3
                }
                first in 0xf0..0xf4 -> {
                    if (index + 3 >= end || !continuation(index + 2) || !continuation(index + 3)) return false
                    val second = bytes[index + 1].toInt() and 0xff
                    val secondRange = when (first) {
                        0xf0 -> 0x90..0xbf
                        0xf4 -> 0x80..0x8f
                        else -> 0x80..0xbf
                    }
                    if (second !in secondRange) return false
                    val codePoint = ((first and 0x07) shl 18) or ((second and 0x3f) shl 12) or
                        ((bytes[index + 2].toInt() and 0x3f) shl 6) or (bytes[index + 3].toInt() and 0x3f)
                    if (isNonCharacter(codePoint)) return false
                    index += 4
                }
                else -> return false
            }
        }
        return true
    }

    private fun isNonCharacter(codePoint: Int): Boolean =
        codePoint in 0xfdd0..0xfdef || (codePoint and 0xfffe) == 0xfffe
}
