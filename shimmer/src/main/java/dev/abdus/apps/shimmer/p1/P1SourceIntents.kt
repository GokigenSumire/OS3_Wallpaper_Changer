package dev.abdus.apps.shimmer.p1

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Android entry points for P1 source selection. */
object P1SourceIntents {
    private const val IMAGE_MIME = "image/*"

    fun openSingleImage(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = IMAGE_MIME
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun openMultipleImages(): Intent = openSingleImage().apply {
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    fun openDirectory(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun selectedUris(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return emptyList()
        val values = buildList {
            data.data?.let(::add)
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }
        return values.distinct()
    }

    /**
     * Persists read access only. A provider may reject persistable access; the caller can keep
     * the source as a pending selection and ask the user to choose it again later.
     */
    fun persistReadPermission(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
}
