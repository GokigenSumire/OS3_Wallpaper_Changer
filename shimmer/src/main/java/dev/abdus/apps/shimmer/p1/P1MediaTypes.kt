package dev.abdus.apps.shimmer.p1

import dev.abdus.apps.shimmer.media.MediaFileTypes

/** 动态照片的外层文件仍是图片；不以是否含音轨或可播放片段决定来源分类。 */
object P1MediaTypes {
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val UNKNOWN = "UNKNOWN"
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    fun classify(name: String, uri: String = "", mime: String? = null): String? {
        if (MediaFileTypes.isVideo(name, mime) || MediaFileTypes.isVideo(uri)) return VIDEO
        if (mime?.startsWith("image/", ignoreCase = true) == true) return IMAGE
        fun extension(value: String) = value.substringBefore('?').substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return if (extension(name) in imageExtensions || extension(uri) in imageExtensions) IMAGE else null
    }

    /** 两类都开时保留升级前尚未识别的记录；单类过滤不猜测未知文件。 */
    const val PLAYBACK_SQL = " AND ((s.imagesEnabled = 1 AND s.videosEnabled = 1)" +
        " OR (i.mediaType = 'IMAGE' AND s.imagesEnabled = 1)" +
        " OR (i.mediaType = 'VIDEO' AND s.videosEnabled = 1)) "
}
