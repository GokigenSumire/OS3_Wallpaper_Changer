package dev.abdus.apps.shimmer.p2

/** 恢复不是手势本身；成功接管后只重放尚未过期的原动作，自动恢复不发隐式下一张。 */
internal fun sourceRestoreFollowUp(
    direction: PlaybackDirection,
    restored: Boolean,
    restoreOnly: Boolean,
    expectedGeneration: Long,
    currentGeneration: Long,
    active: Boolean,
    visible: Boolean,
    ready: Boolean,
): PlaybackDirection? = direction.takeIf {
    restored && !restoreOnly && expectedGeneration == currentGeneration && active && visible && ready
}
