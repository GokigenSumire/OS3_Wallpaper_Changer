package dev.abdus.apps.shimmer.ui.gallery

/** 由主线程发起和提交；磁盘读取仍在 IO，较旧读取不能覆盖更晚发起读取的结果。 */
internal class GalleryDisabledImageReads {
    private var newestRead = 0L
    var images: Set<String> = emptySet()
        private set

    fun beginRead(): Long {
        newestRead = if (newestRead == Long.MAX_VALUE) 0 else newestRead + 1
        return newestRead
    }

    fun publish(read: Long, value: Set<String>): Boolean {
        if (read != newestRead) return false
        images = value.toSet()
        return true
    }
}
