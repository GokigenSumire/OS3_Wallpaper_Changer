package dev.abdus.apps.shimmer.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class MotionPhotoParserTest {
    private fun xmp(attributes: String, directory: String = "", cameraPrefix: String = "c") = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:r="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
          xmlns:$cameraPrefix="http://ns.google.com/photos/1.0/camera/"
          xmlns:d="http://ns.google.com/photos/1.0/container/" xmlns:i="http://ns.google.com/photos/1.0/container/item/">
        <r:RDF><r:Description $attributes>$directory</r:Description></r:RDF></x:xmpmeta>
    """.trimIndent()
    private fun directory(videoLength: String = "200", padding: String = "0") = """
        <d:Directory><r:Seq>
          <r:li><d:Item i:Mime="image/jpeg" i:Semantic="Primary" i:Padding="$padding"/></r:li>
          <r:li><d:Item i:Mime="video/mp4" i:Semantic="MotionPhoto" i:Length="$videoLength"/></r:li>
        </r:Seq></d:Directory>
    """
    @Test fun modernDirectoryUsesLengthAndKeepsMicrosecondTimestamp() {
        val result = MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"1\" c:MotionPhotoVersion=\"1\" c:MotionPhotoPresentationTimestampUs=\"1200000\"", directory()), 1000)!!
        assertEquals(MediaByteRange(800, 200), result.video)
        assertEquals(1200000L, result.photoTimestampUs)
    }
    @Test fun namespaceAliasesAreAcceptedWithoutStringPrefixGuessing() {
        val value = xmp("vendor:MicroVideo=\"1\" vendor:MicroVideoOffset=\"100\"", cameraPrefix = "vendor")
        assertEquals(MediaByteRange(900, 100), MotionPhotoParser.parseXmp(value, 1000)?.video)
    }
    @Test fun explicitModernFalseDoesNotReviveLegacyFlag() {
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"0\" c:MicroVideo=\"1\" c:MicroVideoOffset=\"100\""), 1000))
    }
    @Test fun modernFlagAloneNeverInventsVideo() {
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"1\" c:MicroVideoOffset=\"100\""), 1000))
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"1\"", directory("0")), 1000))
    }
    @Test fun gainmapAndPrimaryPaddingAreAccountedFor() {
        val dir = directory(padding = "8").replace("<r:li><d:Item i:Mime=\"video/mp4\"", "<r:li><d:Item i:Mime=\"image/jpeg\" i:Semantic=\"GainMap\" i:Length=\"250\"/></r:li><r:li><d:Item i:Mime=\"video/mp4\"")
        assertEquals(MediaByteRange(800, 200), MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"1\"", dir), 1000)?.video)
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"1\"", dir), 400))
    }
    @Test fun invalidLengthsAndUnsupportedVersionsKeepStill() {
        for (n in listOf("-1", "1001", "9223372036854775808", "not-a-number")) {
            assertNull(MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"1\"", directory(n)), 1000))
            assertNull(MotionPhotoParser.parseXmp(xmp("c:MicroVideo=\"1\" c:MicroVideoOffset=\"$n\""), 1000))
        }
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MicroVideo=\"1\" c:MicroVideoVersion=\"2\" c:MicroVideoOffset=\"100\""), 1000))
    }
    @Test fun unknownPhotoTimestampRemainsUnknown() {
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MicroVideo=\"1\" c:MicroVideoOffset=\"100\" c:MicroVideoPresentationTimestampUs=\"-1\""), 1000)!!.photoTimestampUs)
    }
    @Test fun foreignNamespaceCannotGrantMotionSemantics() {
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MicroVideo=\"1\" c:MicroVideoOffset=\"100\"").replace("http://ns.google.com/photos/1.0/camera/", "https://example.invalid/"), 1000))
    }
    @Test fun malformedXmlEntitiesAndExcessiveInputAreRejected() {
        assertNull(MotionPhotoParser.parseXmp("<bad", 1000))
        assertNull(MotionPhotoParser.parseXmp("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///private'>]>" + xmp("c:MicroVideo=\"1\""), 1000))
        assertNull(MotionPhotoParser.parseXmp("x".repeat(MotionPhotoParser.MAX_XMP_CHARS + 1), 1000))
    }
    @Test fun duplicateMotionAndNonFinalMovieAreRejected() {
        val duplicate = directory().replace("</r:Seq>", "<r:li><d:Item i:Mime=\"video/mp4\" i:Semantic=\"MotionPhoto\" i:Length=\"1\"/></r:li></r:Seq>")
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"1\"", duplicate), 1000))
        val trailer = directory().replace("</r:Seq>", "<r:li><d:Item i:Mime=\"image/jpeg\" i:Semantic=\"Other\" i:Length=\"1\"/></r:li></r:Seq>")
        assertNull(MotionPhotoParser.parseXmp(xmp("c:MotionPhoto=\"1\"", trailer), 1000))
    }
    private fun box(type: String, payload: ByteArray) = ByteBuffer.allocate(payload.size + 8).putInt(payload.size + 8).put(type.toByteArray()).put(payload).array()
    private fun heif(data: ByteArray) = MotionPhotoParser.heifVideo(data.size.toLong()) { pos, count ->
        if (pos < 0 || pos + count > data.size) null else data.copyOfRange(pos.toInt(), pos.toInt() + count)
    }
    @Test fun heifMpvdIsParsedWithoutInventingCoverTimestamp() {
        val bytes = box("ftyp", "heic".toByteArray()) + box("mdat", ByteArray(11)) + box("mpvd", ByteArray(20))
        val result = heif(bytes)!!
        assertEquals(MediaByteRange(39, 20), result.video)
        assertNull(result.photoTimestampUs)
    }
    @Test fun arbitraryEmbeddedMpvdPatternDoesNotCount() {
        assertNull(heif(box("ftyp", "heic".toByteArray()) + box("mdat", "xxxxmpvd".toByteArray())))
        assertNull(heif(box("ftyp", "heic".toByteArray()) + box("mpvd", ByteArray(20)) + byteArrayOf(1)))
    }
    @Test fun truncatedNegativeAndExtendedBoxesAreBounded() {
        assertNull(heif(ByteBuffer.allocate(8).putInt(-1).put("mpvd".toByteArray()).array()))
        assertNull(heif(ByteBuffer.allocate(16).putInt(1).put("mpvd".toByteArray()).putLong(Long.MAX_VALUE).array()))
        assertNull(heif(byteArrayOf(0, 0, 0)))
    }
}
