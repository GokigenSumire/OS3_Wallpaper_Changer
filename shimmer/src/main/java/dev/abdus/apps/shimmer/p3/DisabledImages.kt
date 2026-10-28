package dev.abdus.apps.shimmer.p3

import androidx.core.net.toUri

/** 编辑停用提示必须指向真实恢复入口；新入口在播放列表下方。 */
object DisabledImageText {
    const val SECTION_TITLE = "已停用图片"
    const val DISABLED_TOAST = "本张图片已停用。可在“播放”页列表下方的“已停用图片”中恢复。"
}

/** 编辑页逐张停用的一条图片记录。 */
data class DisabledImageEntry(
    /** The stored ID: the image URI, the same key the playback catalog filters by. */
    val id: String,
    val name: String,
    /** Folder path inside its source, or a note when no source record holds this image any more. */
    val detail: String,
)

/**
 * Builds the restore list from the stored IDs.
 *
 * The editor stores only the URI. A P1 record supplies the readable name and path; an image whose
 * record is gone (source deleted, legacy folder) still gets a row so it can be restored.
 */
object DisabledImageList {
    /** Rows read and drawn at most; "全部恢复" still clears every stored ID. */
    const val DISPLAY_LIMIT = 200

    /** IDs to look up, in a stable order, so a large set never reads every record. */
    fun idsToShow(disabled: Set<String>): List<String> = disabled.sorted().take(DISPLAY_LIMIT)

    /** [records] maps an ID to its P1 `displayName` and `relativePath`; missing IDs have no record. */
    fun entries(ids: List<String>, records: Map<String, Pair<String, String>>): List<DisabledImageEntry> =
        ids.map { id ->
            val record = records[id]
            if (record == null) {
                DisabledImageEntry(id, fallbackName(id), "来源记录中已没有这张图片")
            } else {
                val (displayName, relativePath) = record
                DisabledImageEntry(
                    id = id,
                    name = displayName.ifBlank { relativePath.substringAfterLast('/').ifBlank { fallbackName(id) } },
                    detail = relativePath.ifBlank { "来源根目录" },
                )
            }
        }.sortedWith(compareBy({ it.name }, { it.id }))

    /** Last readable segment of a content or file URI; SAF document IDs end in `primary:dir/a.jpg`. */
    fun fallbackName(id: String): String {
        // lastPathSegment is already decoded; decoding again would garble a name containing '%'.
        val segment = id.toUri().lastPathSegment ?: return id
        val name = segment.substringAfterLast('/').substringAfterLast(':')
        return name.ifBlank { id }
    }
}
