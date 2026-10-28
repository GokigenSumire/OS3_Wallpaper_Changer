package dev.abdus.apps.shimmer.p1

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * P9-MULTI-GROUP-01：一次多选添加的图片组。
 *
 * 多选的图片没有共同的上级目录，来源表却要求每个来源有唯一的 `rootUri`。这里为每次多选生成一个
 * 合成根 `os3-batch:<创建毫秒>-<随机串>`：它只是来源身份，不是可读位置，不会被交给系统去打开；
 * 组内每张图片仍按自己的文档 URI 读取、复制与校验。
 *
 * 版本 39 及以前，每张图片各自成为一个来源（根就是该图片），已有的这类来源保持原样、不合并。
 */
object P1ImageBatch {
    const val SCHEME = "os3-batch"
    private const val PREFIX = "$SCHEME:"

    /** 每次调用都是新的一组；同一批图片再选一次也是新的一组。 */
    fun newRootUri(nowMillis: Long = System.currentTimeMillis()): String =
        "$PREFIX$nowMillis-${UUID.randomUUID().toString().replace("-", "").take(12)}"

    fun isBatchRoot(rootUri: String): Boolean = rootUri.startsWith(PREFIX)

    /** 合成根里记的创建时间；不是本格式或无法解析时为 null。 */
    fun createdAtMillis(rootUri: String): Long? =
        rootUri.takeIf(::isBatchRoot)
            ?.removePrefix(PREFIX)
            ?.substringBefore('-')
            ?.toLongOrNull()

    /**
     * 卡片与组头上代替路径显示的文字，例如"一次多选添加（2026-10-08 20:15:42）"。
     * 带到秒：同一分钟里多选两次也能分清是哪一组。
     */
    fun displayLabel(rootUri: String, timeZone: TimeZone = TimeZone.getDefault()): String {
        val created = createdAtMillis(rootUri) ?: return LABEL
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }
            .format(Date(created))
        return "$LABEL（$time）"
    }

    const val LABEL = "一次多选添加"
}
