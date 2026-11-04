package dev.abdus.apps.shimmer.media

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Read-only, bounded metadata interpretation. A marker alone never proves a playable video. */
object MotionPhotoParser {
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    const val MAX_XMP_CHARS = 1_048_576
    data class Result(val video: MediaByteRange, val photoTimestampUs: Long? = null)

    fun parseXmp(xmp: String, total: Long): Result? = runCatching {
        if (total <= 0 || xmp.length > MAX_XMP_CHARS ||
            xmp.contains("<!DOCTYPE", true) || xmp.contains("<!ENTITY", true)) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
            setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {})
        }
        val document = builder.parse(InputSource(StringReader(xmp)))
        val elements = document.getElementsByTagName("*")
        fun cameraAttribute(name: String): String? {
            for (i in 0 until elements.length) {
                val e = elements.item(i) as? Element ?: continue
                if (e.hasAttributeNS(CAMERA, name)) return e.getAttributeNS(CAMERA, name)
            }
            return null
        }
        val modern = cameraAttribute("MotionPhoto")
        if ((modern ?: cameraAttribute("MicroVideo"))?.toIntOrNull() != 1) return null
        val version = cameraAttribute(if (modern != null) "MotionPhotoVersion" else "MicroVideoVersion")
        if (version != null && version.toIntOrNull() != 1) return null
        val timestamp = (cameraAttribute("MotionPhotoPresentationTimestampUs")
            ?: cameraAttribute("MicroVideoPresentationTimestampUs"))?.toLongOrNull()?.takeIf { it >= 0 }
        data class Item(val mime: String, val semantic: String, val length: Long, val padding: Long)
        val items = ArrayList<Item>()
        for (i in 0 until elements.length) {
            val e = elements.item(i) as? Element ?: continue
            if (e.namespaceURI != CONTAINER || e.localName != "Item") continue
            val size = e.getAttributeNS(ITEM, "Length").let { if (it.isEmpty()) 0L else it.toLongOrNull() ?: return null }
            val padding = e.getAttributeNS(ITEM, "Padding").let { if (it.isEmpty()) 0L else it.toLongOrNull() ?: return null }
            if (size < 0 || padding < 0) return null
            items += Item(e.getAttributeNS(ITEM, "Mime"), e.getAttributeNS(ITEM, "Semantic"), size, padding)
        }
        if (items.isNotEmpty()) {
            if (items.size < 2 || items.first().semantic != "Primary" || !items.first().mime.startsWith("image/") ||
                items.count { it.semantic == "Primary" } != 1 || items.count { it.semantic == "MotionPhoto" } != 1) return null
            val movie = items.last()
            if (movie.semantic != "MotionPhoto" || movie.mime !in setOf("video/mp4", "video/quicktime") || movie.length <= 0) return null
            var cursor = total
            for (i in items.lastIndex downTo 1) {
                val item = items[i]
                if (item.padding != 0L || item.length > cursor) return null
                cursor -= item.length
            }
            if (cursor <= items.first().padding) return null
            val range = MediaByteRange(total - movie.length, movie.length)
            return Result(range.takeIf { it.offset > 0 && it.fits(total) } ?: return null, timestamp)
        }
        // Legacy adapter only. Modern metadata must describe its resource directory.
        if (modern != null) return null
        val size = cameraAttribute("MicroVideoOffset")?.toLongOrNull() ?: return null
        if (size <= 0 || size >= total) return null
        Result(MediaByteRange(total - size, size), timestamp)
    }.getOrNull()

    /** Top-level BMFF boxes; no byte-pattern guessing inside compressed image data. */
    fun heifVideo(total: Long, read: (Long, Int) -> ByteArray?): Result? = runCatching {
        var position = 0L
        repeat(2048) {
            if (position > total - 8) return null
            val header = read(position, 8)?.takeIf { it.size == 8 } ?: return null
            var size = uint32(header, 0)
            var headerSize = 8L
            if (size == 1L) {
                val extended = read(position + 8, 8)?.takeIf { it.size == 8 } ?: return null
                if (extended[0].toInt() < 0) return null
                size = extended.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
                headerSize = 16
            } else if (size == 0L) size = total - position
            if (size < headerSize || size > total - position) return null
            val type = String(header, 4, 4, Charsets.US_ASCII)
            if (type == "mpvd") {
                if (position == 0L || size <= headerSize || position + size != total) return null
                return Result(MediaByteRange(position + headerSize, size - headerSize))
            }
            position += size
        }
        null
    }.getOrNull()

    /** Only bounded headers are read. The caller supplies a range-checked seekable view. */
    fun inspect(total: Long, read: (Long, Int) -> ByteArray?): Result? = runCatching {
        if (total < 12) return null
        val initial = read(0, 12)?.takeIf { it.size == 12 } ?: return null
        val candidate = if ((initial[0].toInt() and 255) == 255 && (initial[1].toInt() and 255) == 216) {
            var position = 2L
            var result: Result? = null
            var visited = 0
            while (position < minOf(total, 1_048_576L) && visited++ < 2048) {
                val marker = read(position, 4)?.takeIf { it.size == 4 } ?: break
                if ((marker[0].toInt() and 255) != 255) break
                val code = marker[1].toInt() and 255
                if (code == 218 || code == 217) break
                val length = ((marker[2].toInt() and 255) shl 8) or (marker[3].toInt() and 255)
                if (length < 2 || length.toLong() > total - position - 2) break
                if (code == 225) {
                    val payload = read(position + 4, length - 2) ?: break
                    val prefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
                    if (payload.size >= prefix.size && prefix.indices.all { payload[it] == prefix[it] }) {
                        val xmp = String(payload, prefix.size, payload.size - prefix.size, Charsets.UTF_8).trimEnd('\u0000')
                        result = parseXmp(xmp, total)
                        if (result != null) break
                    }
                }
                position += length + 2L
            }
            result
        } else if (String(initial, 4, 4, Charsets.US_ASCII) == "ftyp") {
            heifVideo(total, read)
        } else null
        val range = candidate?.video ?: return null
        if (!range.fits(total) || range.length < 12) return null
        val header = read(range.offset, 12)?.takeIf { it.size == 12 } ?: return null
        candidate.takeIf { String(header, 4, 4, Charsets.US_ASCII) == "ftyp" }
    }.getOrNull()

    private fun uint32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[offset + i].toLong() and 255) }
}
