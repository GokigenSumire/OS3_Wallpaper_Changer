package dev.abdus.apps.shimmer

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

data class ScannedImage(
    val uri: Uri,
    val width: Int?,
    val height: Int?,
    val fileSize: Long?
)

private fun Cursor.requireCompleteDocumentsResult() {
    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
        throw ScanException(ScanFailure.INCOMPLETE_RESULT)
    }
    if (extras.getString(DocumentsContract.EXTRA_ERROR) != null) {
        throw ScanException(ScanFailure.READ)
    }
}

interface ImageScanner {
    fun scan(uri: Uri, existingFiles: Map<Uri, Long?>?, checkActive: () -> Unit): List<ScannedImage>
}

private class FileScanner : ImageScanner {
    override fun scan(uri: Uri, existingFiles: Map<Uri, Long?>?, checkActive: () -> Unit): List<ScannedImage> {
        val dir = File(uri.path ?: throw ScanException(ScanFailure.READ))
        if (!dir.isDirectory || !dir.canRead()) throw ScanException(ScanFailure.READ)
        val files = dir.listFiles() ?: throw ScanException(ScanFailure.NULL_RESULT)
        return files
            .filter {
                checkActive()
                it.isFile && it.name.isImageFile()
            }
            .map { file ->
                checkActive()
                val fileUri = Uri.fromFile(file)
                val fileSize = file.length().takeIf { it > 0 }
                
                // Check if file is already indexed (URI + size match)
                val isExisting = existingFiles?.containsKey(fileUri) == true && existingFiles[fileUri] == fileSize
                
                // If existing and size matches, skip bitmap decoding (just verify file exists)
                if (isExisting && file.exists()) {
                    ScannedImage(fileUri, null, null, fileSize)
                } else {
                    // New or changed file - decode bitmap
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    if (options.outWidth <= 0 || options.outHeight <= 0) {
                        throw ScanException(ScanFailure.METADATA_DECODE)
                    }
                    ScannedImage(
                        fileUri,
                        options.outWidth.takeIf { it > 0 },
                        options.outHeight.takeIf { it > 0 },
                        fileSize
                    )
                }
            }
    }
}

private class DocumentScanner(private val context: Context) : ImageScanner {
    override fun scan(uri: Uri, existingFiles: Map<Uri, Long?>?, checkActive: () -> Unit): List<ScannedImage> {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        val results = mutableListOf<ScannedImage>()

        val cursorResult = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw ScanException(ScanFailure.NULL_RESULT)
        cursorResult.use { cursor ->
            // Some document providers return a partial list while loading from remote storage.
            cursor.requireCompleteDocumentsResult()
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                checkActive()
                val mimeType = cursor.getString(mimeIdx)
                val name = cursor.getString(nameIdx) ?: ""

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue

                if (mimeType?.startsWith("image/") == true || name.isImageFile()) {
                    val docId = cursor.getString(idIdx)
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                    val fileSize = cursor.getLong(sizeIdx).takeIf { it > 0 }

                    val isExisting = existingFiles?.containsKey(fileUri) == true && existingFiles[fileUri] == fileSize

                    if (isExisting) {
                        results.add(ScannedImage(fileUri, null, null, fileSize))
                    } else {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        val stream = context.contentResolver.openInputStream(fileUri)
                            ?: throw ScanException(ScanFailure.METADATA_DECODE)
                        stream.use {
                            BitmapFactory.decodeStream(it, null, options)
                        }
                        if (options.outWidth <= 0 || options.outHeight <= 0) {
                            throw ScanException(ScanFailure.METADATA_DECODE)
                        }
                        results.add(ScannedImage(
                            fileUri,
                            options.outWidth,
                            options.outHeight,
                            fileSize
                        ))
                    }
                }
            }
            cursor.requireCompleteDocumentsResult()
        }
        return results
    }
}

private class MediaStoreScanner(private val context: Context) : ImageScanner {
    override fun scan(uri: Uri, existingFiles: Map<Uri, Long?>?, checkActive: () -> Unit): List<ScannedImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(FavoritesFolderResolver.getDefaultRelativePath())

        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    checkActive()
                    val id = cursor.getLong(idIdx)
                    val fileUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val size = cursor.getLong(sizeIdx).takeIf { it > 0 }
                    
                    val width = cursor.getInt(widthIdx).takeIf { it > 0 }
                    val height = cursor.getInt(heightIdx).takeIf { it > 0 }
                    add(ScannedImage(fileUri, width, height, size))
                }
            }
        } ?: throw ScanException(ScanFailure.NULL_RESULT)
    }
}

class FolderScanner(context: Context) {
    private val fileScanner = FileScanner()
    private val documentScanner = DocumentScanner(context)
    private val mediaStoreScanner = MediaStoreScanner(context)

    fun scan(
        uri: Uri,
        existingFiles: Map<Uri, Long?>? = null,
        checkActive: () -> Unit = {},
    ): ScanResult<ScannedImage> {
        return captureScan(checkActive) {
            val scanner = when {
                FavoritesFolderResolver.isDefaultFavoritesUri(uri) -> mediaStoreScanner
                uri.scheme == "file" -> fileScanner
                else -> documentScanner
            }
            scanner.scan(uri, existingFiles, checkActive)
        }
    }
}
