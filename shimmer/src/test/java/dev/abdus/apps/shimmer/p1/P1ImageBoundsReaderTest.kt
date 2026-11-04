package dev.abdus.apps.shimmer.p1

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import org.robolectric.shadows.ShadowContentResolver

/** 真实 AFD／有效 PNG 与注入计数共同检查资源和子段；宿主 pipe 不冒充 Android 原生 pipe。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P1ImageBoundsReaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun boundedVideoMetadataIsReadBeforeTheImageStreamClosesItsDescriptor() {
        val provider = provider(asset(byteArrayOf(1, 2, 3, 4, 5, 6), 2, 3))
        val decoder = RecordingDecoder(streamDimensions = null, readStreamBytes = true)
        var videos = 0
        val reader = P1ImageBoundsReader(context.contentResolver, decoder, videoDecoder = { current ->
            assertTrue(current.fileDescriptor.valid())
            assertEquals(2L, current.startOffset)
            assertEquals(3L, current.declaredLength)
            videos++
            VALID_DIMENSIONS
        }) { error("bounded stream must not seek") }
        assertEquals(VALID_DIMENSIONS, reader.read(URI))
        assertArrayEquals(byteArrayOf(3, 4, 5), decoder.streamBytes)
        assertEquals(1, videos)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun unboundedVideoFallbackKeepsTheProviderOpenAfterBothImageAttemptsFail() {
        val provider = provider(asset(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)))
        val decoder = RecordingDecoder(descriptorDimensions = null, streamDimensions = null, readStreamBytes = true)
        var videos = 0
        val reader = P1ImageBoundsReader(context.contentResolver, decoder, videoDecoder = { current ->
            assertTrue(current.fileDescriptor.valid()); videos++; VALID_DIMENSIONS
        }) { 0L }
        assertEquals(VALID_DIMENSIONS, reader.read(URI))
        assertEquals(1, decoder.descriptorCalls)
        assertEquals(1, decoder.streamCalls)
        assertEquals(1, videos)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun aVideoReadThroughTheRealImageProbeIsAcceptedByTheImportValidator() = kotlinx.coroutines.runBlocking {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val provider = provider(asset("video payload".toByteArray(), 0, 13))
        val reader = P1ImageBoundsReader(context.contentResolver, videoDecoder = { current ->
            assertTrue(current.fileDescriptor.valid())
            P1MetadataValidator.Dimensions(1920, 1080)
        })
        val outcome = P1ScanOutcome.Complete(listOf(P1ScannedImage("image:video", "source", URI.toString(), "clip.mp4", "clip.mp4", 13)), 1)
        val result = P1MetadataValidator.validate(outcome, { reader.read(Uri.parse(it.uri)) }) as P1ScanOutcome.Complete
        assertEquals(1, result.images.size)
        assertEquals(1920, result.images.single().width)
        assertEquals(1080, result.images.single().height)
        assertEquals(0, result.skippedMetadata)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun videoFailureStillClosesBothStreamAndAssetAndPropagatesPermission() {
        val provider = provider(asset(byteArrayOf(1, 2), 0, 2))
        val failure = SecurityException("video permission revoked")
        val reader = P1ImageBoundsReader(context.contentResolver, RecordingDecoder(streamDimensions = null),
            videoDecoder = { assertTrue(it.fileDescriptor.valid()); throw failure })
        assertSame(failure, runCatching { reader.read(URI) }.exceptionOrNull())
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun filePathStillReallyDecodesPngBounds() {
        val file = fixtureFile(ONE_PIXEL_PNG)
        val dimensions = P1ImageBoundsReader(context.contentResolver).read(Uri.fromFile(file))
        assertEquals(P1MetadataValidator.Dimensions(1, 1), dimensions)
    }

    @Test
    fun seekableUnboundedAssetUsesDescriptorOnceAndClosesIt() {
        val provider = provider(asset())
        val decoder = RecordingDecoder()
        val reader = P1ImageBoundsReader(context.contentResolver, decoder) { fd ->
            assertSame(provider.asset!!.fileDescriptor, fd)
            0L
        }
        assertEquals(VALID_DIMENSIONS, reader.read(URI))
        assertEquals(1, provider.opens)
        assertEquals(1, decoder.descriptorCalls)
        assertEquals(0, decoder.streamCalls)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun unboundedAssetReallyDecodesPngThroughBitmapFactoryDescriptor() {
        val provider = provider(asset())
        // Robolectric's Os.lseek is not an Android seekability proof; descriptor starts at zero here.
        val result = P1ImageBoundsReader(context.contentResolver, seekDescriptor = { 0L }).read(URI)
        assertEquals(P1MetadataValidator.Dimensions(1, 1), result)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun nonzeroAssetOffsetNeverUsesDescriptorAndReadsOnlyTheDeclaredSlice() {
        val prefix = byteArrayOf(11, 12, 13, 14, 15)
        val provider = provider(asset(prefix + ONE_PIXEL_PNG + byteArrayOf(77, 78), prefix.size.toLong(), ONE_PIXEL_PNG.size.toLong()))
        val decoder = RecordingDecoder(readStreamBytes = true)
        assertEquals(VALID_DIMENSIONS, P1ImageBoundsReader(context.contentResolver, decoder) { error("subrange must not seek FD") }.read(URI))
        assertEquals(0, decoder.descriptorCalls)
        assertEquals(1, decoder.streamCalls)
        assertArrayEquals(ONE_PIXEL_PNG, decoder.streamBytes)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun declaredLengthAtZeroOffsetAlsoUsesTheBoundedStream() {
        val provider = provider(asset(ONE_PIXEL_PNG + byteArrayOf(91, 92), 0, ONE_PIXEL_PNG.size.toLong()))
        val decoder = RecordingDecoder(readStreamBytes = true)
        P1ImageBoundsReader(context.contentResolver, decoder) { error("bounded asset must not seek FD") }.read(URI)
        assertArrayEquals(ONE_PIXEL_PNG, decoder.streamBytes)
        assertEquals(0, decoder.descriptorCalls)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun knownWholeFileLengthRemainsOnTheBoundedStreamPath() {
        val provider = provider(asset(ONE_PIXEL_PNG, 0, ONE_PIXEL_PNG.size.toLong()))
        val decoder = RecordingDecoder(readStreamBytes = true)
        val result = P1ImageBoundsReader(context.contentResolver, decoder) { error("known length must not seek FD") }.read(URI)
        assertEquals(VALID_DIMENSIONS, result)
        assertEquals(0, decoder.descriptorCalls)
        assertEquals(1, decoder.streamCalls)
        assertArrayEquals(ONE_PIXEL_PNG, decoder.streamBytes)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun aRealBoundedAssetStreamStillDecodesValidPng() {
        val prefix = byteArrayOf(41, 42, 43)
        val provider = provider(asset(prefix + ONE_PIXEL_PNG, prefix.size.toLong(), ONE_PIXEL_PNG.size.toLong()))
        val result = P1ImageBoundsReader(context.contentResolver).read(URI)
        assertEquals(P1MetadataValidator.Dimensions(1, 1), result)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun aPipeAssetWhosePositionerRejectsSeekUsesTheSameOpenedStream() {
        // Robolectric implements createPipe with a host file: inject nonseekability, not fake OS proof.
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(ONE_PIXEL_PNG) }
        val provider = provider(AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH))
        val decoder = RecordingDecoder(readStreamBytes = true)
        P1ImageBoundsReader(context.contentResolver, decoder) { throw ErrnoException("lseek", OsConstants.ESPIPE) }.read(URI)
        assertEquals(0, decoder.descriptorCalls)
        assertEquals(1, decoder.streamCalls)
        assertArrayEquals(ONE_PIXEL_PNG, decoder.streamBytes)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun seekReturningANonzeroPositionDoesNotDecodeAnyUncertainBytes() {
        val provider = provider(asset())
        val decoder = RecordingDecoder(readStreamBytes = true)
        val result = FileInputStream(provider.descriptor!!).use { stream ->
            P1ImageBoundsReader(context.contentResolver, decoder) { fd ->
                assertSame(provider.descriptor, fd)
                // Actually move the host FD; do not pretend a constant return proves a safe start.
                stream.channel.position(1L).position()
            }.read(URI)
        }
        assertEquals(null, result)
        assertEquals(0, decoder.descriptorCalls)
        assertEquals(0, decoder.streamCalls)
        assertEquals(null, decoder.streamBytes)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun descriptorRejectionRewindsAndFallsBackWithoutReopeningTheProvider() {
        val provider = provider(asset())
        var seeks = 0
        val decoder = RecordingDecoder(descriptorDimensions = null, readStreamBytes = true)
        val result = P1ImageBoundsReader(context.contentResolver, decoder) { seeks++; 0L }.read(URI)
        assertEquals(VALID_DIMENSIONS, result)
        assertEquals(2, seeks)
        assertEquals(1, decoder.descriptorCalls)
        assertEquals(1, decoder.streamCalls)
        assertArrayEquals(ONE_PIXEL_PNG, decoder.streamBytes)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun fallbackThatCannotRewindDoesNotDecodeAnUnknownTailOrReopen() {
        val provider = provider(asset())
        var seeks = 0
        val decoder = RecordingDecoder(descriptorDimensions = null)
        val result = P1ImageBoundsReader(context.contentResolver, decoder) { if (++seeks == 1) 0L else 1L }.read(URI)
        assertEquals(null, result)
        assertEquals(1, decoder.descriptorCalls)
        assertEquals(0, decoder.streamCalls)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun fallbackWithErrnoOnRewindDoesNotReadAnUnknownTail() {
        val provider = provider(asset())
        var seeks = 0
        val decoder = RecordingDecoder(descriptorDimensions = null)
        val result = P1ImageBoundsReader(context.contentResolver, decoder) {
            if (++seeks == 1) 0L else throw ErrnoException("lseek", OsConstants.ESPIPE)
        }.read(URI)
        assertEquals(null, result)
        assertEquals(2, seeks)
        assertEquals(1, decoder.descriptorCalls)
        assertEquals(0, decoder.streamCalls)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun decoderSecurityFailurePropagatesAndClosesTheAsset() {
        val provider = provider(asset())
        val failure = SecurityException("revoked during decode")
        val decoder = RecordingDecoder(failure = failure)
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver, decoder) { 0L }.read(URI) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(0, decoder.streamCalls)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun decoderCancellationPropagatesAndClosesTheAsset() {
        val provider = provider(asset())
        val failure = CancellationException("cancelled during stream")
        val decoder = RecordingDecoder(failure = failure)
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver, decoder) { throw ErrnoException("lseek", OsConstants.ESPIPE) }.read(URI) }.exceptionOrNull()
        assertSame(failure, caught)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun providerSecurityFailureIsNotRetriedOrTreatedAsNullMetadata() {
        val failure = SecurityException("no read grant")
        val provider = provider(null, failure)
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver).read(URI) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, provider.opens)
    }

    @Test
    fun positionerCancellationIsNotConvertedIntoStreamFallback() {
        val provider = provider(asset())
        val failure = CancellationException("cancel seek")
        val decoder = RecordingDecoder()
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver, decoder) { throw failure }.read(URI) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(0, decoder.descriptorCalls)
        assertEquals(0, decoder.streamCalls)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun positionerSecurityFailureIsNotConvertedIntoStreamFallback() {
        val provider = provider(asset())
        val failure = SecurityException("seek access revoked")
        val decoder = RecordingDecoder()
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver, decoder) { throw failure }.read(URI) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(0, decoder.descriptorCalls)
        assertEquals(0, decoder.streamCalls)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun streamIoFailureStillClosesTheOpenedAsset() {
        val provider = provider(asset())
        val failure = IOException("read failed")
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver, RecordingDecoder(failure = failure)) { throw ErrnoException("lseek", OsConstants.ESPIPE) }.read(URI) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, provider.opens)
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun closeFailureAfterDecodeIsNotReportedAsSuccessfulMetadata() {
        val closeFailure = IOException("provider close failure")
        val resource = assetWithCloseFailure(closeFailure)
        val descriptor = resource.fileDescriptor
        val decoder = RecordingDecoder()
        // ContentResolver rewraps AFDs: directly exercise the production ownership/close boundary.
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver, decoder) { 0L }.readAsset(resource) }.exceptionOrNull()
        assertSame(closeFailure, caught)
        assertEquals(1, decoder.descriptorCalls)
        assertFalse(descriptor.valid())
    }

    @Test
    fun closeFailureDoesNotReplaceDecodeSecurityFailure() {
        val closeFailure = IOException("close after revoked permission")
        val resource = assetWithCloseFailure(closeFailure)
        val descriptor = resource.fileDescriptor
        val failure = SecurityException("decode read grant revoked")
        val decoder = RecordingDecoder(failure = failure)
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver, decoder) { 0L }.readAsset(resource) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, caught!!.suppressed.size)
        assertSame(closeFailure, caught.suppressed.single())
        assertFalse(descriptor.valid())
    }

    @Test
    fun closeFailureDoesNotReplaceDecodeCancellation() {
        val closeFailure = IOException("close after cancellation")
        val resource = assetWithCloseFailure(closeFailure)
        val descriptor = resource.fileDescriptor
        val failure = CancellationException("decode cancelled")
        val decoder = RecordingDecoder(failure = failure)
        val caught = runCatching { P1ImageBoundsReader(context.contentResolver, decoder) { 0L }.readAsset(resource) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, caught!!.suppressed.size)
        assertSame(closeFailure, caught.suppressed.single())
        assertFalse(descriptor.valid())
    }

    @Test
    fun nullAssetIsNotReopenedOrReportedAsSuccessfulBounds() {
        val provider = provider(null)
        assertEquals(null, P1ImageBoundsReader(context.contentResolver).read(URI))
        assertEquals(1, provider.opens)
    }

    @Test
    fun zeroOrNegativeDimensionsAreNotAccepted() {
        val provider = provider(asset())
        val decoder = RecordingDecoder(descriptorDimensions = P1MetadataValidator.Dimensions(0, 1), streamDimensions = P1MetadataValidator.Dimensions(1, -1))
        assertEquals(null, P1ImageBoundsReader(context.contentResolver, decoder) { 0L }.read(URI))
        assertFalse(provider.descriptor!!.valid())
    }

    @Test
    fun malformedBytesThroughTheRealStreamDecoderAreNotAccepted() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val provider = provider(asset("not an image".toByteArray()))
        assertEquals(null, P1ImageBoundsReader(context.contentResolver, seekDescriptor = {
            throw ErrnoException("lseek", OsConstants.ESPIPE)
        }).read(URI))
        assertFalse(provider.descriptor!!.valid())
    }

    private fun fixtureFile(bytes: ByteArray): File =
        File.createTempFile("p1-bounds-", ".png", context.cacheDir).apply { writeBytes(bytes) }

    private fun asset(bytes: ByteArray = ONE_PIXEL_PNG, offset: Long = 0,
        length: Long = AssetFileDescriptor.UNKNOWN_LENGTH): AssetFileDescriptor =
        AssetFileDescriptor(ParcelFileDescriptor.open(fixtureFile(bytes), ParcelFileDescriptor.MODE_READ_ONLY), offset, length)

    private fun assetWithCloseFailure(failure: IOException): AssetFileDescriptor =
        object : AssetFileDescriptor(
            ParcelFileDescriptor.open(fixtureFile(ONE_PIXEL_PNG), ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        ) {
            override fun close() {
                super.close()
                throw failure
            }
        }

    private fun provider(asset: AssetFileDescriptor?, failure: Exception? = null): AssetProvider =
        AssetProvider(asset, failure).also {
            it.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
            ShadowContentResolver.registerProviderInternal(AUTHORITY, it)
        }

    private class AssetProvider(val asset: AssetFileDescriptor?, private val failure: Exception?) : ContentProvider() {
        var opens = 0
        val descriptor = asset?.fileDescriptor
        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/png"
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            opens++
            failure?.let { throw it }
            return asset
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = error("bounds must not query metadata")
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only fixture")
    }

    private class RecordingDecoder(
        private val descriptorDimensions: P1MetadataValidator.Dimensions? = VALID_DIMENSIONS,
        private val streamDimensions: P1MetadataValidator.Dimensions? = VALID_DIMENSIONS,
        private val readStreamBytes: Boolean = false,
        private val failure: Exception? = null,
    ) : P1ImageBoundsReader.Decoder {
        var descriptorCalls = 0
        var streamCalls = 0
        var streamBytes: ByteArray? = null
        override fun file(path: String?): P1MetadataValidator.Dimensions? = error("content must not map to a file path")
        override fun descriptor(fd: FileDescriptor): P1MetadataValidator.Dimensions? {
            descriptorCalls++
            failure?.let { throw it }
            return descriptorDimensions
        }
        override fun stream(input: InputStream): P1MetadataValidator.Dimensions? {
            streamCalls++
            failure?.let { throw it }
            if (readStreamBytes) streamBytes = input.readBytes()
            return streamDimensions
        }
    }

    private companion object {
        const val AUTHORITY = "test.p1bounds"
        val URI: Uri = Uri.parse("content://$AUTHORITY/document/opaque")
        val VALID_DIMENSIONS = P1MetadataValidator.Dimensions(10, 20)
        val ONE_PIXEL_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
