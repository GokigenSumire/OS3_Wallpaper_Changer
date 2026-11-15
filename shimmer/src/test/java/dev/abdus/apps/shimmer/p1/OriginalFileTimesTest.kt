package dev.abdus.apps.shimmer.p1

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OriginalFileTimesTest {
    @Test fun invalidAndAbsentNativeTimesStayUnknownIndependently() {
        assertEquals(dev.abdus.apps.shimmer.p2.FileTimestamps(), OriginalFileTimes.decode(null))
        assertEquals(dev.abdus.apps.shimmer.p2.FileTimestamps(null, 99), OriginalFileTimes.decode(longArrayOf(0, 99)))
        assertEquals(dev.abdus.apps.shimmer.p2.FileTimestamps(5, null), OriginalFileTimes.decode(longArrayOf(5, -1)))
    }
    @Test fun externalStoragePathsCannotEscapeTheirKnownVolume() {
        val root = File("/storage/emulated/0")
        assertEquals(File(root, "Pictures/a.jpg").canonicalFile, OriginalFileTimes.resolveInside(root, "Pictures/a.jpg"))
        assertNull(OriginalFileTimes.resolveInside(root, "../other/file"))
        assertNull(OriginalFileTimes.resolveInside(root, "/data/data/private"))
        assertNull(OriginalFileTimes.resolveInside(root, "a\u0000b"))
    }
    private class Provider(val value: Long?, val reject: Boolean = false) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (reject) throw IllegalArgumentException("Unsupported optional column")
            return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)).apply { addRow(arrayOf(value)) }
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
    private fun read(value: Long?, reject: Boolean = false): dev.abdus.apps.shimmer.p2.FileTimestamps {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = Provider(value, reject)
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.original.times" })
        ShadowContentResolver.registerProviderInternal("test.original.times", provider)
        return P1AndroidSourceScanner(context).selectedTimes(Uri.parse("content://test.original.times/file"), null)
    }
    @Test fun providerModificationUsesMillisecondsAndNeverBecomesCreation() {
        val result = read(1700000000123)
        assertEquals(1700000000123, result.modifiedMillis); assertNull(result.createdMillis)
    }
    @Test fun missingRejectedZeroAndNegativeTimesDoNotPreventImport() {
        for (value in listOf(null, 0L, -1L)) assertNull(read(value).modifiedMillis)
        assertNull(read(99, reject = true).modifiedMillis)
    }
    @Test(expected = OperationCanceledException::class) fun optionalMetadataDoesNotSwallowCancellation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        P1AndroidSourceScanner(context).selectedTimes(Uri.parse("content://test.original.times/file"), CancellationSignal().apply { cancel() })
    }
}
