package dev.abdus.apps.shimmer.media

/** Main-thread lease bookkeeping, independent from MediaCodec and injectable in tests. */
class MediaDecoderBudget(private val maximum: Int = 2) {
    init { require(maximum > 0) }
    private val owners = LinkedHashSet<Any>()
    fun acquire(owner: Any): Boolean = owner in owners || (owners.size < maximum && owners.add(owner))
    fun release(owner: Any) { owners.remove(owner) }
    val activeCount: Int get() = owners.size
}
