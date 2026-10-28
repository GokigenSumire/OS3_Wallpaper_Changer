package dev.abdus.apps.shimmer.p2

import java.util.AbstractList
import java.util.Collections

/**
 * Final, defensively copied metadata produced only after the repository's real catalog read.
 * Its identity proves immutability, not freshness or authorization: the reader must still verify
 * the database, rules and disabled preferences on every action before returning this object.
 * A coordinator may reuse it only after validating its complete catalog once for itself.
 */
internal class CertifiedPlaybackItems(items: List<PlaybackItem>) : AbstractList<PlaybackItem>() {
    private val frozen = Collections.unmodifiableList(ArrayList(items))
    override val size: Int get() = frozen.size
    override fun get(index: Int): PlaybackItem = frozen[index]
}
