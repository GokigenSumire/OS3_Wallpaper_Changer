package dev.abdus.apps.shimmer.ui.gallery

import dev.abdus.apps.shimmer.p1.DIRECTORY_TREE_ROOT_NAME
import dev.abdus.apps.shimmer.p1.P1SourceKind
import dev.abdus.apps.shimmer.p1.SourcePathFormatter
import dev.abdus.apps.shimmer.p1.directorySegments

/** Card fields for one P1 source; the gallery tab shows these and links to P1 for the full tree. */
object GallerySourceSummary {
    /**
     * Number of distinct folders below the source root that hold images directly or in a
     * descendant, the same levels the P1 page can collapse. `1/1-1/a.jpg` counts `1` and `1/1-1`.
     */
    fun subdirectoryCount(relativePaths: List<String>): Int {
        val directories = HashSet<String>()
        for (path in relativePaths) {
            val segments = directorySegments(path)
            for (depth in 1..segments.size) directories += segments.take(depth).joinToString("/")
        }
        return directories.size
    }

    fun type(kind: String): SourceType = when (kind) {
        P1SourceKind.DIRECTORY_TREE.name -> SourceType.FOLDER
        P1SourceKind.MULTIPLE_DOCUMENTS.name -> SourceType.MULTI_IMAGES
        else -> SourceType.SINGLE_IMAGE
    }

    /** Same wording as the P1 page, so both pages name a source the same way. */
    fun kindLabel(kind: String): String = when (kind) {
        P1SourceKind.SINGLE_DOCUMENT.name -> "单张图片"
        P1SourceKind.MULTIPLE_DOCUMENTS.name -> "多张图片"
        P1SourceKind.DIRECTORY_TREE.name -> "文件夹目录"
        else -> kind
    }

    /** A folder shows its own name; other kinds, or an unmappable root, show the kind label. */
    fun title(kind: String, rootUri: String): String = when (kind) {
        P1SourceKind.DIRECTORY_TREE.name -> SourcePathFormatter.folderName(rootUri) ?: DIRECTORY_TREE_ROOT_NAME
        else -> kindLabel(kind)
    }

    /** Readable location for the card and the delete dialog; never the raw tree URI segment. */
    fun location(rootUri: String): String = SourcePathFormatter.shorten(rootUri)
}
