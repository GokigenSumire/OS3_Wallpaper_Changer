package dev.abdus.apps.shimmer.p2

import java.util.AbstractList
import java.util.Collections

/** Deeply immutable IDs: only this final, copying wrapper is shareable across prepared state. */
internal class ImmutablePlaybackIds(input: List<String>) : AbstractList<String>() {
    private val frozen = Collections.unmodifiableList(ArrayList(input))
    override val size: Int get() = frozen.size
    override fun get(index: Int): String = frozen[index]
}

/** Preserve controlled identities, but never certify an arbitrary read-only view over mutable data. */
internal fun List<String>.immutablePlaybackIds(): List<String> =
    if (this is ImmutablePlaybackIds) this else ImmutablePlaybackIds(this)
