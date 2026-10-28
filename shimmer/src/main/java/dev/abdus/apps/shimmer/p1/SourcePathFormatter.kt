package dev.abdus.apps.shimmer.p1

import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/**
 * Converts SAF document/tree URIs to a human-readable storage path.
 *
 * SAF does not expose a filesystem path in the URI itself.  The path returned for the
 * external-storage provider is therefore a display mapping (for example
 * `/storage/emulated/0/Pictures`), not a path that the app should use for file I/O.  Providers
 * that cannot be mapped safely keep their URI so that the source remains diagnosable.
 */
object SourcePathFormatter {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Returns a readable path or the original URI when it cannot be mapped safely. */
    fun format(rawValue: String): String {
        val raw = rawValue.trim()
        if (raw.isEmpty()) return raw
        // P9-MULTI-GROUP-01：一次多选的合成根不是位置，显示为添加方式与时间
        if (P1ImageBatch.isBatchRoot(raw)) return P1ImageBatch.displayLabel(raw)
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return raw

        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.takeIf { it.isNotBlank() } ?: raw
        }
        if (!uri.scheme.equals("content", ignoreCase = true)) return raw

        mapVendorFileProvider(uri)?.let { return it }

        // A media/document provider may also expose a document id, but it does not identify a
        // stable filesystem volume.  Only map the external-storage provider to avoid inventing
        // paths such as /storage/image/123 for media IDs.
        if (!uri.authority.equals(EXTERNAL_STORAGE_AUTHORITY, ignoreCase = true)) return raw

        val pathSegments = uri.pathSegments
        val hasDocument = pathSegments.contains("document")
        val isTree = !hasDocument && DocumentsContract.isTreeUri(uri)
        val documentId = runCatching {
            if (isTree) DocumentsContract.getTreeDocumentId(uri)
            else DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return raw

        return mapDocumentId(documentId, appendTrailingSlash = isTree) ?: raw
    }

    /** Formats and truncates only for a compact UI row, preserving both path ends. */
    fun shorten(rawValue: String, maxLength: Int = 72): String {
        require(maxLength >= 16) { "maxLength must leave enough room for a readable path" }
        val formatted = format(rawValue)
        if (formatted.length <= maxLength) return formatted
        val headLength = maxLength / 2
        val tailLength = (maxLength - headLength - 3).coerceAtLeast(8)
        return formatted.take(headLength) + "..." + formatted.takeLast(tailLength)
    }

    /**
     * Name of the folder a source root points at, for the source's own collapsible tree level.
     *
     * Returns null when the root cannot be mapped to a readable path (the raw URI is not a name);
     * callers then fall back to a generic label.
     */
    fun folderName(rawValue: String): String? {
        val path = format(rawValue).trimEnd('/')
        if (!path.startsWith("/")) return null
        if (path == "/storage/emulated/0") return PRIMARY_STORAGE_NAME
        return path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private const val PRIMARY_STORAGE_NAME = "内部存储"

    /**
     * The HyperOS file manager shares documents through a FileProvider whose `external_files`
     * root is the primary external-storage volume. Other roots stay unmapped.
     */
    private fun mapVendorFileProvider(uri: Uri): String? {
        val authority = uri.authority ?: return null
        if (!authority.startsWith(FILE_EXPLORER_AUTHORITY_PREFIX, ignoreCase = true)) return null
        val segments = uri.pathSegments
        if (segments.size < 2 || segments.first() != "external_files") return null
        return (listOf("/storage/emulated/0") + segments.drop(1).filter { it.isNotEmpty() }).joinToString("/")
    }

    private const val FILE_EXPLORER_AUTHORITY_PREFIX = "com.android.fileexplorer."

    private fun mapDocumentId(documentIdValue: String, appendTrailingSlash: Boolean): String? {
        // DocumentsContract has already decoded exactly once. Percent text in a filename
        // (for example a literal "%2F") must never become another path separator.
        val documentId = documentIdValue
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null

        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1).trim('/').split('/').filter { it.isNotEmpty() }
        val base = when (volume.lowercase(Locale.US)) {
            // Android's primary external-storage volume is mounted here on the target device.
            "primary" -> "/storage/emulated/0"
            else -> if (volume.matches(Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"))) "/storage/$volume" else return null
        }
        val path = (listOf(base) + relative).joinToString("/")
        return if (appendTrailingSlash && !path.endsWith('/')) "$path/" else path
    }
}
