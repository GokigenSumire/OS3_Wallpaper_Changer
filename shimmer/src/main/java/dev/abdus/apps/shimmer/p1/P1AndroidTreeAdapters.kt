package dev.abdus.apps.shimmer.p1

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.CancellationException

/** Canonical identity, separate from the access URI that carries the user's tree grant. */
fun p1DocumentIdentity(uri: Uri): String {
    val normalized = uri.normalizeScheme()
    if (normalized.scheme == "file") {
        val path = normalized.path ?: return normalized.toString()
        return try {
            Uri.fromFile(File(path).canonicalFile).toString()
        } catch (_: java.io.IOException) {
            normalized.toString()
        }
    }
    if (normalized.scheme != "content") return normalized.toString()
    val authority = normalized.authority ?: return normalized.toString()
    // Some providers expose a tree grant followed by a child document segment. The framework
    // helper only recognizes the canonical document URI shape, so normalize this common route
    // explicitly before falling back to the helper APIs.
    normalized.toString().substringAfterLast("/document/", "")
        .takeIf { it.isNotBlank() }
        ?.let { encodedId ->
            return DocumentsContract.buildDocumentUri(authority, Uri.decode(encodedId)).toString()
        }
    val documentId = try {
        // A tree access URI may contain a child document: prefer that child over the tree root.
        DocumentsContract.getDocumentId(normalized)
    } catch (_: IllegalArgumentException) {
        try {
            DocumentsContract.getTreeDocumentId(normalized)
        } catch (_: IllegalArgumentException) {
            return normalized.toString()
        }
    }
    return DocumentsContract.buildDocumentUri(authority, documentId).toString()
}

/** Adapts app-readable files and SAF documents to the provider-neutral P1 scanner. */
class P1AndroidSourceScanner(context: Context) {
    private val appContext = context.applicationContext

    fun scan(
        source: P1SourceSpec,
        checkActive: () -> Unit = {},
        onProgress: (P1ScanProgress) -> Unit = {},
        cancellationSignal: CancellationSignal? = null,
    ): P1ScanOutcome {
        val root = try {
            checkActive()
            rootNode(Uri.parse(source.rootUri), source.kind, cancellationSignal, checkActive).also { checkActive() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: OperationCanceledException) {
            throw CancellationException("Provider query cancelled").apply { initCause(cancelled) }
        } catch (security: SecurityException) {
            return P1ScanOutcome.Failed(P1ScanFailure.PERMISSION, 0, 0)
        } catch (failed: P1TreeReadException) {
            return P1ScanOutcome.Failed(failed.failure, 0, 0)
        } catch (_: Exception) {
            return P1ScanOutcome.Failed(P1ScanFailure.READ, 0, 0)
        }
        // Explicit file selections can have opaque IDs or no extension (including app copies).
        // Their actual bytes are validated by the repository before any metadata is committed.
        if (source.kind != P1SourceKind.DIRECTORY_TREE) {
            if (root.isDirectory) return P1ScanOutcome.Failed(P1ScanFailure.METADATA, 0, 0)
            return P1RecursiveScanner(isImage = { !it.isDirectory })
                .scan(source, root, checkActive, onProgress)
        }
        return P1RecursiveScanner().scan(source, root, checkActive, onProgress)
    }

    private fun rootNode(uri: Uri, kind: P1SourceKind, cancellationSignal: CancellationSignal?, checkActive: () -> Unit): P1TreeNode {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return FileTreeNode(File(uri.path ?: throw P1TreeReadException(P1ScanFailure.READ)))
        }

        if (kind != P1SourceKind.DIRECTORY_TREE) {
            return selectedDocumentNode(uri, cancellationSignal, checkActive)
        }

        val isTree = runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
        val treeUri = uri.takeIf { isTree }
        val accessUri = if (treeUri != null) {
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
                .getOrElse { throw P1TreeReadException(P1ScanFailure.METADATA) }
            runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, documentId) }
                .getOrElse { throw P1TreeReadException(P1ScanFailure.METADATA) }
        } else {
            uri
        }
        return documentNode(accessUri, treeUri, cancellationSignal, checkActive)
    }

    private fun selectedDocumentNode(
        accessUri: Uri,
        cancellationSignal: CancellationSignal?,
        checkActive: () -> Unit,
    ): P1TreeNode {
        checkActive()
        val cursor = appContext.contentResolver.query(
            accessUri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null, cancellationSignal,
        ) ?: throw P1TreeReadException(P1ScanFailure.READ)
        val (displayName, size) = cursor.use {
            ensureProviderResultComplete(it)
            if (!it.moveToFirst()) throw P1TreeReadException(P1ScanFailure.METADATA)
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) it.getString(nameIndex)?.takeIf(String::isNotBlank) else null
            val bytes = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex).takeIf { value -> value >= 0 } else null
            if (it.moveToNext()) throw P1TreeReadException(P1ScanFailure.INCOMPLETE_RESULT)
            ensureProviderResultComplete(it)
            (name ?: accessUri.lastPathSegment ?: "image") to bytes
        }
        checkActive()
        return object : P1TreeNode {
            override val id = p1DocumentIdentity(accessUri)
            override val uri = accessUri.toString()
            override val name = displayName
            override val isDirectory = false
            override val mimeType = appContext.contentResolver.getType(accessUri)
            override val sizeBytes = size
            override fun children(): Iterable<P1TreeNode> = emptyList()
        }
    }

    private fun documentNode(
        accessUri: Uri,
        treeUri: Uri?,
        cancellationSignal: CancellationSignal?,
        checkActive: () -> Unit,
    ): P1TreeNode {
        val metadata = queryDocumentMetadata(accessUri, cancellationSignal, checkActive)
        return DocumentTreeNode(appContext, accessUri, treeUri, metadata, cancellationSignal)
    }

    private class FileTreeNode(private val file: File) : P1TreeNode {
        private val canonicalFile = runCatching { file.canonicalFile }
            .getOrElse { throw P1TreeReadException(P1ScanFailure.READ) }

        init {
            if (!canonicalFile.exists()) throw P1TreeReadException(P1ScanFailure.READ)
            if (!canonicalFile.canRead()) throw SecurityException("Unreadable file")
        }

        override val id: String = Uri.fromFile(canonicalFile).toString()
        override val uri: String = id
        override val name: String = canonicalFile.name.ifBlank { id }
        override val isDirectory: Boolean = canonicalFile.isDirectory
        override val mimeType: String? = null
        override val sizeBytes: Long? = canonicalFile.takeIf(File::isFile)?.length()

        override fun children(): Iterable<P1TreeNode> = children {}

        override fun children(checkActive: () -> Unit): Iterable<P1TreeNode> {
            checkActive()
            if (!canonicalFile.isDirectory || !canonicalFile.canRead()) {
                throw SecurityException("Unreadable directory")
            }
            val files = canonicalFile.listFiles()
                ?: throw P1TreeReadException(P1ScanFailure.READ)
            return files.map {
                checkActive()
                FileTreeNode(it)
            }
        }
    }

    private data class DocumentMetadata(
        val documentId: String,
        val mimeType: String,
        val displayName: String,
        val sizeBytes: Long?,
    )

    private inner class DocumentTreeNode(
        private val context: Context,
        private val accessUri: Uri,
        private val treeUri: Uri?,
        private val metadata: DocumentMetadata,
        private val cancellationSignal: CancellationSignal?,
    ) : P1TreeNode {
        override val id: String = p1DocumentIdentity(accessUri)
        override val uri: String = accessUri.toString()
        override val name: String = metadata.displayName
        override val isDirectory: Boolean = metadata.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        override val mimeType: String = metadata.mimeType
        override val sizeBytes: Long? = metadata.sizeBytes

        override fun children(): Iterable<P1TreeNode> = children {}

        override fun children(checkActive: () -> Unit): Iterable<P1TreeNode> {
            checkActive()
            if (!isDirectory) return emptyList()

            val authority = accessUri.authority
                ?: throw P1TreeReadException(P1ScanFailure.READ)
            val childrenUri = runCatching {
                if (treeUri != null) {
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, metadata.documentId)
                } else {
                    DocumentsContract.buildChildDocumentsUri(authority, metadata.documentId)
                }
            }.getOrElse { throw P1TreeReadException(P1ScanFailure.METADATA) }

            val result = mutableListOf<P1TreeNode>()
            val cursor = try {
                context.contentResolver.query(
                    childrenUri,
                    DOCUMENT_PROJECTION,
                    null,
                    null,
                    null,
                    cancellationSignal,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: OperationCanceledException) {
                throw CancellationException("Provider query cancelled").apply { initCause(cancelled) }
            } catch (security: SecurityException) {
                throw security
            } catch (_: Exception) {
                throw P1TreeReadException(P1ScanFailure.READ)
            } ?: throw P1TreeReadException(P1ScanFailure.READ)

            cursor.use { childCursor ->
                checkActive()
                ensureProviderResultComplete(childCursor)
                val indices = requiredIndices(childCursor)
                while (childCursor.moveToNext()) {
                    checkActive()
                    val childMetadata = metadataFromCursor(childCursor, indices)
                    val childAccessUri = runCatching {
                        if (treeUri != null) {
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, childMetadata.documentId)
                        } else {
                            DocumentsContract.buildDocumentUri(authority, childMetadata.documentId)
                        }
                    }.getOrElse { throw P1TreeReadException(P1ScanFailure.METADATA) }
                    result += DocumentTreeNode(
                        context = context,
                        accessUri = childAccessUri,
                        treeUri = treeUri,
                        metadata = childMetadata,
                        cancellationSignal = cancellationSignal,
                    )
                }
                checkActive()
                ensureProviderResultComplete(childCursor)
            }
            return result
        }
    }

    private fun queryDocumentMetadata(
        accessUri: Uri,
        cancellationSignal: CancellationSignal?,
        checkActive: () -> Unit,
    ): DocumentMetadata {
        checkActive()
        val cursor = try {
            appContext.contentResolver.query(
                accessUri,
                DOCUMENT_PROJECTION,
                null,
                null,
                null,
                cancellationSignal,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: OperationCanceledException) {
            throw CancellationException("Provider query cancelled").apply { initCause(cancelled) }
        } catch (security: SecurityException) {
            throw security
        } catch (_: Exception) {
            throw P1TreeReadException(P1ScanFailure.READ)
        } ?: throw P1TreeReadException(P1ScanFailure.READ)

        cursor.use { documentCursor ->
            checkActive()
            ensureProviderResultComplete(documentCursor)
            val indices = requiredIndices(documentCursor)
            if (!documentCursor.moveToFirst()) {
                throw P1TreeReadException(P1ScanFailure.METADATA)
            }
            val metadata = metadataFromCursor(documentCursor, indices)
            if (documentCursor.moveToNext()) {
                throw P1TreeReadException(P1ScanFailure.INCOMPLETE_RESULT)
            }
            checkActive()
            ensureProviderResultComplete(documentCursor)
            return metadata
        }
    }

    private data class ColumnIndices(
        val documentId: Int,
        val mimeType: Int,
        val displayName: Int,
        val size: Int,
    )

    private fun requiredIndices(cursor: Cursor): ColumnIndices {
        val indices = ColumnIndices(
            documentId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            mimeType = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE),
            displayName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            size = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE),
        )
        if (indices.documentId < 0 || indices.mimeType < 0 || indices.displayName < 0 || indices.size < 0) {
            throw P1TreeReadException(P1ScanFailure.METADATA)
        }
        return indices
    }

    private fun metadataFromCursor(cursor: Cursor, indices: ColumnIndices): DocumentMetadata {
        val documentId = requiredString(cursor, indices.documentId)
        val mimeType = requiredString(cursor, indices.mimeType)
        val displayName = requiredString(cursor, indices.displayName)
        val sizeBytes = if (cursor.isNull(indices.size)) {
            null
        } else {
            cursor.getLong(indices.size).takeIf { it >= 0L }
        }
        return DocumentMetadata(documentId, mimeType, displayName, sizeBytes)
    }

    private fun requiredString(cursor: Cursor, index: Int): String {
        if (cursor.isNull(index)) throw P1TreeReadException(P1ScanFailure.METADATA)
        return cursor.getString(index)?.takeIf { it.isNotBlank() }
            ?: throw P1TreeReadException(P1ScanFailure.METADATA)
    }

    private fun ensureProviderResultComplete(cursor: Cursor) {
        val extras = cursor.extras
        if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
            extras.getString(DocumentsContract.EXTRA_ERROR) != null
        ) {
            throw P1TreeReadException(P1ScanFailure.INCOMPLETE_RESULT)
        }
    }

    private companion object {
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
