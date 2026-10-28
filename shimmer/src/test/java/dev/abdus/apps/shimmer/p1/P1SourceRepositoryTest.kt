package dev.abdus.apps.shimmer.p1

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P1SourceRepositoryTest {
    private var database: ShimmerDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
    }

    @Test
    fun boundsOnlyDecodeAcceptsValidPngAndMetadataFailurePreservesConfiguration() {
        val valid = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(ONE_PIXEL_PNG, 0, ONE_PIXEL_PNG.size, valid)
        assertTrue("valid PNG must expose positive bounds", valid.outWidth > 0 && valid.outHeight > 0)

        val decision = P1ScanCommitGate.decide(
            P1ScanOutcome.Failed(
                reason = P1ScanFailure.METADATA,
                visitedNodes = 1,
                discoveredImages = 1,
            ),
        )
        assertTrue(decision is P1ScanCommitDecision.Preserve)
        assertTrue(
            (decision as P1ScanCommitDecision.Preserve).reason == PreserveReason.METADATA_FAILURE,
        )
    }

    @Test
    fun cancelledScanPreservesConfigurationAndRecordsCancelledState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        val directory = File(context.cacheDir, "p1-cancel-${System.nanoTime()}").apply { mkdirs() }
        try {
            val repository = P1SourceRepository(context, db)
            val result = repository.importDirectory(Uri.fromFile(directory), true) {
                throw CancellationException("test cancellation")
            }

            assertEquals(P1ImportState.CANCELLED, result.state)
            assertEquals("CANCELLED", db.p1SourceDao().getSource(result.sourceId)?.scanState)
            assertTrue(db.p1SourceDao().getSourceImages(result.sourceId).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun singleOpaqueDocumentWithoutPersistentGrantIsCopiedAndAvailableForPlayback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries().build()
        database = db
        val provider = OpaqueImageProvider(File(context.cacheDir, "opaque-image.png").apply {
            writeBytes(ONE_PIXEL_PNG)
        })
        ShadowContentResolver.registerProviderInternal("test.opaque", provider)
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.opaque" })
        assertEquals(ONE_PIXEL_PNG.size, context.contentResolver.openInputStream(
            Uri.parse("content://test.opaque/document/42"),
        )!!.use { it.readBytes() }.size)

        val result = P1SourceRepository(context, db).importSingleDocument(
            Uri.parse("content://test.opaque/document/42"),
        )

        assertEquals(P1ImportState.COMMITTED, result.state)
        assertEquals("APP_COPY", db.p1SourceDao().getSource(result.sourceId)?.permissionState)
        val playback = db.p1SourceDao().getEnabledPlaybackImages()
        assertEquals(1, playback.size)
        assertEquals("新增图片.png", playback.single().displayName)
        assertEquals("file", Uri.parse(playback.single().uri).scheme)
    }

    @Test
    fun multipleOpaqueDocumentsAreCommittedEvenWhenDisplayNameHasNoExtension() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries().build()
        database = db
        val provider = OpaqueImageProvider(File(context.cacheDir, "opaque-multiple.png").apply {
            writeBytes(ONE_PIXEL_PNG)
        }, "无扩展名图片")
        ShadowContentResolver.registerProviderInternal("test.opaque", provider)
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.opaque" })

        val results = P1SourceRepository(context, db).importSelectedDocuments(listOf(
            Uri.parse("content://test.opaque/document/43"),
            Uri.parse("content://test.opaque/document/44"),
        ))

        assertEquals(listOf(P1ImportState.COMMITTED, P1ImportState.COMMITTED), results.map { it.state })
        assertEquals(2, db.p1SourceDao().getEnabledPlaybackImages().size)
        assertEquals(setOf("无扩展名图片"), db.p1SourceDao().getEnabledPlaybackImages().map { it.displayName }.toSet())
    }

    @Test
    fun invalidReplacementOfSameCopiedDocumentPreservesCommittedBytesAndMetadata() = runBlocking {
        // Robolectric accepts undecodable bytes by default. Disable that compatibility shortcut
        // so this fixture exercises decoding failure instead of a synthetic placeholder bitmap.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries().build()
        database = db
        val suppliedFile = File(context.cacheDir, "changing-provider-image.png").apply {
            writeBytes(ONE_PIXEL_PNG)
        }
        val provider = OpaqueImageProvider(suppliedFile)
        ShadowContentResolver.registerProviderInternal("test.opaque", provider)
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.opaque" })
        val repository = P1SourceRepository(context, db)
        val selectedUri = Uri.parse("content://test.opaque/document/46")
        assertEquals(P1ImportState.COMMITTED, repository.importSingleDocument(selectedUri).state)
        val before = db.p1SourceDao().getSourceImages(
            P1SourceRepository.stableSourceId(selectedUri, P1SourceKind.SINGLE_DOCUMENT),
        ).single()
        val committedCopy = File(requireNotNull(Uri.parse(before.uri).path))
        val committedBytes = committedCopy.readBytes()
        val committedHash = java.security.MessageDigest.getInstance("SHA-256").digest(committedBytes)

        suppliedFile.writeBytes("provider returned non-image bytes".toByteArray())
        val result = repository.importSingleDocument(selectedUri)

        assertArrayEquals(committedHash, java.security.MessageDigest.getInstance("SHA-256").digest(committedCopy.readBytes()))
        assertEquals(P1ImportState.METADATA_FAILED, result.state)
        assertEquals(before, db.p1SourceDao().getSourceImages(result.sourceId).single())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(committedCopy.path, bounds)
        assertTrue("Previously committed app copy remains readable", bounds.outWidth > 0 && bounds.outHeight > 0)
    }

    @Test
    fun selectedDocumentWithOpenableColumnsDoesNotRequireDirectoryMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = OpaqueImageProvider(File(context.cacheDir, "openable-image.png").apply {
            writeBytes(ONE_PIXEL_PNG)
        })
        ShadowContentResolver.registerProviderInternal("test.opaque", provider)

        val outcome = P1AndroidSourceScanner(context).scan(P1SourceSpec(
            sourceId = "openable-source",
            rootUri = "content://test.opaque/document/45",
            kind = P1SourceKind.SINGLE_DOCUMENT,
            includeSubdirectories = false,
        ))

        assertTrue("Selected image metadata should use the openable-document contract: $outcome", outcome is P1ScanOutcome.Complete)
        assertEquals("新增图片.png", (outcome as P1ScanOutcome.Complete).images.single().displayName)
    }

    @Test
    fun largeDirectoryOfValidImagesCommitsWithoutMetadataFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries().build()
        database = db
        val directory = File(context.cacheDir, "p1-large-${System.nanoTime()}").apply { mkdirs() }
        try {
            repeat(700) { index ->
                File(directory, "image-${index.toString().padStart(4, '0')}.png").writeBytes(ONE_PIXEL_PNG)
            }

            val result = P1SourceRepository(context, db).importDirectory(Uri.fromFile(directory), true)

            assertEquals(P1ImportState.COMMITTED, result.state)
            assertEquals(700, result.committedImageCount)
            assertEquals(700, db.p1SourceDao().getSourceImages(result.sourceId).size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun largeDirectorySkipsOneUndecodableImageAndCommitsTheReadableImages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries().build()
        database = db
        val directory = File(context.cacheDir, "p1-large-invalid-${System.nanoTime()}").apply { mkdirs() }
        try {
            repeat(700) { index ->
                File(directory, "image-${index.toString().padStart(4, '0')}.png").writeBytes(ONE_PIXEL_PNG)
            }
            File(directory, "image-0700.jpg").writeBytes("not-an-image".toByteArray())

            val result = P1SourceRepository(context, db).importDirectory(Uri.fromFile(directory), true)

            assertEquals(P1ImportState.COMMITTED, result.state)
            assertEquals(700, result.committedImageCount)
            assertEquals(700, db.p1SourceDao().getSourceImages(result.sourceId).size)
            assertEquals("METADATA_SKIPPED:1", db.p1SourceDao().getSource(result.sourceId)?.lastError)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reselectingExistingNonRecursiveDirectoryCannotImportItsChildrenWithTheNewDialogDefault() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries().build()
        database = db
        val directory = File(context.cacheDir, "p1-recursive-preserve-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(directory, "root.png").writeBytes(ONE_PIXEL_PNG)
            File(directory, "child").apply { mkdirs() }.let { File(it, "child.png").writeBytes(ONE_PIXEL_PNG) }
            val repository = P1SourceRepository(context, db)
            val first = repository.importDirectory(Uri.fromFile(directory), false)
            assertEquals(1, first.committedImageCount)
            val second = repository.importDirectory(Uri.fromFile(directory), true)
            assertEquals(first.sourceId, second.sourceId)
            assertEquals(false, db.p1SourceDao().getSource(first.sourceId)?.includeSubdirectories)
            assertEquals(listOf("root.png"), db.p1SourceDao().getSourceImages(first.sourceId).map { it.relativePath })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellationAfterMetadataWorkersCompletePreservesThePreviousCommittedSet() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries().build()
        database = db
        val directory = File(context.cacheDir, "p1-metadata-cancel-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(directory, "kept.png").writeBytes(ONE_PIXEL_PNG)
            val repository = P1SourceRepository(context, db)
            val first = repository.importDirectory(Uri.fromFile(directory))
            val oldImages = db.p1SourceDao().getAllImages()
            val oldLinks = db.p1SourceDao().getAllLinks()
            File(directory, "new.png").writeBytes(ONE_PIXEL_PNG)
            val cancelled = repository.importDirectory(Uri.fromFile(directory), true) { progress ->
                if (progress.phase == P1ScanPhase.METADATA && progress.processedImages == progress.totalImages && progress.totalImages > 0) {
                    throw CancellationException("cancel metadata before commit")
                }
            }
            assertEquals(P1ImportState.CANCELLED, cancelled.state)
            assertEquals("CANCELLED", db.p1SourceDao().getSource(first.sourceId)?.scanState)
            assertEquals(oldImages, db.p1SourceDao().getAllImages())
            assertEquals(oldLinks, db.p1SourceDao().getAllLinks())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** External provider fixture: opaque IDs are intentionally unrelated to display names. */
    private class OpaqueImageProvider(
        private val image: File,
        private val displayName: String = "新增图片.png",
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> displayName
                        OpenableColumns.SIZE -> image.length()
                        else -> null
                    }
                }.toTypedArray<Any?>())
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    }

    private companion object {
        // 1x1 RGBA PNG, kept inline so this regression test has no filesystem/provider fixture.
        val ONE_PIXEL_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
