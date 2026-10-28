package dev.abdus.apps.shimmer.p1

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.ui.gallery.GallerySourceSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.TimeZone
import java.util.concurrent.CancellationException

/**
 * P9-MULTI-GROUP-01：一次多选添加成为一组，多次添加多次分组。
 *
 * 提供方没有持久读权限，所以每张图片都走应用内副本，与目标机上照片选择器的情况相同。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P9ImageBatchTest {
    private var database: ShimmerDatabase? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun closeDatabase() {
        database?.close()
    }

    @Test
    fun oneMultiSelectBecomesOneGroupInSelectionOrder() = runBlocking {
        val db = newDatabase()
        registerImages("51" to "甲.png", "52" to "乙.png", "53" to "丙.png")
        val progress = mutableListOf<P1ScanProgress>()

        val result = P1SourceRepository(context, db).importImageBatch(
            listOf(doc("51"), doc("52"), doc("53")),
        ) { progress += it }

        assertEquals(P1ImportState.COMMITTED, result.state)
        assertEquals(3, result.committedImageCount)
        assertEquals(0, result.skippedImageCount)
        val source = db.p1SourceDao().getSources().single()
        assertEquals(result.sourceId, source.id)
        assertEquals(P1SourceKind.MULTIPLE_DOCUMENTS.name, source.kind)
        assertTrue(P1ImageBatch.isBatchRoot(source.rootUri))
        assertEquals("COMMITTED", source.scanState)
        assertNull(source.lastError)
        assertEquals(listOf("甲.png", "乙.png", "丙.png"),
            db.p1SourceDao().getSourceImages(source.id).map { it.displayName })
        assertEquals(listOf(1, 2, 3), progress.map { it.visitedNodes })
        assertEquals(3, progress.last().discoveredImages)
    }

    @Test
    fun eachMultiSelectBecomesItsOwnGroup() = runBlocking {
        val db = newDatabase()
        registerImages("51" to "甲.png", "52" to "乙.png", "53" to "丙.png")
        val repository = P1SourceRepository(context, db)

        val first = repository.importImageBatch(listOf(doc("51"), doc("52")))
        val second = repository.importImageBatch(listOf(doc("53")))

        assertNotEquals(first.sourceId, second.sourceId)
        val sources = db.p1SourceDao().getSources()
        assertEquals(2, sources.size)
        assertEquals(2, sources.map { it.rootUri }.toSet().size)
        assertEquals(2, db.p1SourceDao().getSourceImages(first.sourceId).size)
        assertEquals(listOf("丙.png"), db.p1SourceDao().getSourceImages(second.sourceId).map { it.displayName })
    }

    @Test
    fun unreadableImagesAreSkippedAndTheGroupStateIsRestoredToCommitted() = runBlocking {
        val db = newDatabase()
        registerImages("51" to "甲.png", "52" to "乙.png")

        // 最后一张读不到：它写下的撤权状态不能留在已经建好的组上
        val result = P1SourceRepository(context, db).importImageBatch(
            listOf(doc("51"), doc("52"), doc("99")),
        )

        assertEquals(P1ImportState.COMMITTED, result.state)
        assertEquals(2, result.committedImageCount)
        assertEquals(1, result.skippedImageCount)
        val source = requireNotNull(db.p1SourceDao().getSource(result.sourceId))
        assertEquals("COMMITTED", source.scanState)
        assertEquals("APP_COPY", source.permissionState)
        assertEquals("BATCH_SKIPPED:1", source.lastError)
        assertEquals(2, db.p1SourceDao().getSourceImages(source.id).size)
    }

    @Test
    fun aBatchWithNoReadableImageLeavesNoEmptyGroup() = runBlocking {
        val db = newDatabase()
        registerImages("51" to "甲.png")
        val repository = P1SourceRepository(context, db)
        val existing = repository.importImageBatch(listOf(doc("51")))

        val result = repository.importImageBatch(listOf(doc("98"), doc("99")))

        assertEquals(P1ImportState.PERMISSION_REQUIRED, result.state)
        assertEquals(0, result.committedImageCount)
        assertEquals(2, result.skippedImageCount)
        assertEquals(listOf(existing.sourceId), db.p1SourceDao().getSources().map { it.id })
        assertEquals(1, db.p1SourceDao().getSourceImages(existing.sourceId).size)
    }

    @Test
    fun cancellingABatchRemovesTheNewGroupAndKeepsEarlierGroups() = runBlocking {
        val db = newDatabase()
        registerImages("51" to "甲.png", "52" to "乙.png", "53" to "丙.png")
        val repository = P1SourceRepository(context, db)
        val existing = repository.importImageBatch(listOf(doc("51")))

        val thrown = runCatching {
            repository.importImageBatch(listOf(doc("52"), doc("53"))) {
                throw CancellationException("test cancellation")
            }
        }.exceptionOrNull()

        assertTrue("cancellation must reach the caller: $thrown", thrown is CancellationException)
        assertEquals(listOf(existing.sourceId), db.p1SourceDao().getSources().map { it.id })
        assertEquals(1, db.p1SourceDao().getSourceImages(existing.sourceId).size)
    }

    @Test
    fun rescanningABatchKeepsItsRootImagesAndPickedNames() = runBlocking {
        val db = newDatabase()
        registerImages("51" to "甲.png", "52" to "乙.png")
        val repository = P1SourceRepository(context, db)
        val imported = repository.importImageBatch(listOf(doc("51"), doc("52")))
        val before = requireNotNull(db.p1SourceDao().getSource(imported.sourceId))

        val result = repository.rescan(imported.sourceId)

        assertEquals(P1ImportState.COMMITTED, result.state)
        assertEquals(2, result.committedImageCount)
        val after = requireNotNull(db.p1SourceDao().getSource(imported.sourceId))
        assertEquals(before.rootUri, after.rootUri)
        assertEquals("COMMITTED", after.scanState)
        assertEquals(1, db.p1SourceDao().getSources().size)
        // 应用内副本的文件名是哈希；复扫不能把用户选图时的名字换成哈希
        assertEquals(listOf("甲.png", "乙.png"),
            db.p1SourceDao().getSourceImages(imported.sourceId).map { it.displayName })
    }

    @Test
    fun reauthorizingABatchAddsThePickedImagesToTheSameGroup() = runBlocking {
        val db = newDatabase()
        registerImages("51" to "甲.png", "52" to "乙.png")
        val repository = P1SourceRepository(context, db)
        val imported = repository.importImageBatch(listOf(doc("51")))
        val root = requireNotNull(db.p1SourceDao().getSource(imported.sourceId)).rootUri

        val result = repository.reauthorize(imported.sourceId, listOf(doc("52")))

        assertEquals(P1ImportState.COMMITTED, result.state)
        assertEquals(imported.sourceId, result.sourceId)
        assertEquals(root, db.p1SourceDao().getSource(imported.sourceId)?.rootUri)
        assertEquals(1, db.p1SourceDao().getSources().size)
        assertEquals(listOf("甲.png", "乙.png"),
            db.p1SourceDao().getSourceImages(imported.sourceId).map { it.displayName })
    }

    @Test
    fun batchRootIsAnIdentityWithACreationTimeNotALocation() {
        val root = P1ImageBatch.newRootUri(0L)

        assertTrue(P1ImageBatch.isBatchRoot(root))
        assertEquals(0L, P1ImageBatch.createdAtMillis(root))
        assertNotEquals(root, P1ImageBatch.newRootUri(0L))
        assertFalse(P1ImageBatch.isBatchRoot("content://test.batch/document/51"))
        assertNull(P1ImageBatch.createdAtMillis("file:///storage/emulated/0/Pictures"))
        assertEquals("一次多选添加（1970-01-01 00:00:00）",
            P1ImageBatch.displayLabel(root, TimeZone.getTimeZone("UTC")))
        assertEquals(P1ImageBatch.LABEL, P1ImageBatch.displayLabel("${P1ImageBatch.SCHEME}:broken"))
    }

    @Test
    fun twoBatchesAddedWithinTheSameMinuteAreLabelledApart() {
        // 卡片只显示添加方式和时间；只到分钟时，一分钟内多选两次的两组标签完全相同
        val utc = TimeZone.getTimeZone("UTC")
        val first = P1ImageBatch.displayLabel(P1ImageBatch.newRootUri(60_000L), utc)
        val second = P1ImageBatch.displayLabel(P1ImageBatch.newRootUri(60_000L + 25_000L), utc)

        assertEquals("一次多选添加（1970-01-01 00:01:00）", first)
        assertEquals("一次多选添加（1970-01-01 00:01:25）", second)
    }

    @Test
    fun cardLocationNamesTheAddMethodAndNeverTheSyntheticRoot() {
        val root = P1ImageBatch.newRootUri()

        val location = GallerySourceSummary.location(root)

        assertTrue(location, location.startsWith("${P1ImageBatch.LABEL}（"))
        assertFalse(location, location.contains(P1ImageBatch.SCHEME))
        assertNull(SourcePathFormatter.folderName(root))
        assertEquals("多张图片", GallerySourceSummary.title(P1SourceKind.MULTIPLE_DOCUMENTS.name, root))
    }

    private fun newDatabase(): ShimmerDatabase =
        Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
            .allowMainThreadQueries().build()
            .also { database = it }

    /** Registers readable documents by id; any other id behaves like a revoked or deleted image. */
    private fun registerImages(vararg named: Pair<String, String>) {
        val files = named.associate { (id, _) ->
            id to File(context.cacheDir, "batch-$id-${System.nanoTime()}.png").apply { writeBytes(ONE_PIXEL_PNG) }
        }
        val provider = BatchImageProvider(files, named.toMap())
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
    }

    private fun doc(id: String): Uri = Uri.parse("content://$AUTHORITY/document/$id")

    private class BatchImageProvider(
        private val files: Map<String, File>,
        private val names: Map<String, String>,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val id = uri.lastPathSegment.orEmpty()
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                val file = files[id] ?: return@apply
                addRow(columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> names[id]
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                }.toTypedArray<Any?>())
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = files[uri.lastPathSegment.orEmpty()] ?: throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    }

    private companion object {
        const val AUTHORITY = "test.batch"
        val ONE_PIXEL_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
