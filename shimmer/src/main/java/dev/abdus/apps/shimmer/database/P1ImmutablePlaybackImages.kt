package dev.abdus.apps.shimmer.database

import java.util.AbstractList
import java.util.Collections

/**
 * 受控的不可变完整投影；类型是 final，构造时复制列表，所有记录字段本身也是只读值。
 * 只有该包装的身份可用于下游纯映射复用；普通 List 即使引用相同也不作此承诺。
 * 它不是授权证明：真实事务读者仍须每次验证目录 stamp 和全部守护后才返回旧包装。
 */
internal class P1ImmutablePlaybackImages(images: List<P1PlaybackImage>) : AbstractList<P1PlaybackImage>() {
    private val frozen = Collections.unmodifiableList(ArrayList(images))
    override val size: Int get() = frozen.size
    override fun get(index: Int): P1PlaybackImage = frozen[index]
}
