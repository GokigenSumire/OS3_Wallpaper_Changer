package dev.abdus.apps.shimmer.p5

import dev.abdus.apps.shimmer.p2.PlaybackDirection
import kotlin.random.Random

/**
 * Resolves the user's [TransitionSettings] into the [TransitionSpec] of one switch. One instance
 * per Engine: a random pick never repeats that Engine's previous random pick.
 */
class TransitionPlanner(private val random: Random = Random.Default) {
    private var lastRandomEffect: TransitionEffect? = null

    /**
     * @param direction the playback step, or null for restore, sync and re-decode paths (treated
     *   as a step forward).
     * @param sameImage the incoming image is the one already shown (a re-decode, e.g. a blur
     *   change), which crossfades whatever effect is chosen.
     * @param staticMode P2 static acceptance mode, which always swaps instantly.
     */
    fun plan(
        settings: TransitionSettings,
        direction: PlaybackDirection?,
        sameImage: Boolean,
        staticMode: Boolean,
    ): TransitionSpec {
        val duration = settings.normalized().durationMillis
        if (staticMode || settings.choice == TransitionChoice.NONE) return TransitionSpec.NONE
        if (sameImage) return TransitionSpec.crossfade(duration)
        val effect = when (settings.choice) {
            TransitionChoice.NONE -> TransitionEffect.NONE
            TransitionChoice.CROSSFADE -> TransitionEffect.CROSSFADE
            TransitionChoice.FADE_THROUGH -> TransitionEffect.FADE_THROUGH
            TransitionChoice.SLIDE -> TransitionEffect.SLIDE
            TransitionChoice.ZOOM -> TransitionEffect.ZOOM
            TransitionChoice.RANDOM -> pickRandomEffect()
        }
        val slide = if (effect == TransitionEffect.SLIDE) slideDirection(settings.direction, direction) else SlideDirection.LEFT
        return TransitionSpec(effect, duration, slide)
    }

    private fun pickRandomEffect(): TransitionEffect {
        val pool = TransitionEffect.ANIMATED.filter { it != lastRandomEffect }
        return pool[random.nextInt(pool.size)].also { lastRandomEffect = it }
    }

    private fun slideDirection(choice: SlideDirectionChoice, playback: PlaybackDirection?): SlideDirection = when (choice) {
        // Next: content moves left, the new image enters from the right; previous is the reverse.
        SlideDirectionChoice.FOLLOW -> if (playback == PlaybackDirection.PREVIOUS) SlideDirection.RIGHT else SlideDirection.LEFT
        SlideDirectionChoice.LEFT -> SlideDirection.LEFT
        SlideDirectionChoice.RIGHT -> SlideDirection.RIGHT
        SlideDirectionChoice.UP -> SlideDirection.UP
        SlideDirectionChoice.DOWN -> SlideDirection.DOWN
        SlideDirectionChoice.RANDOM -> SlideDirection.entries[random.nextInt(SlideDirection.entries.size)]
    }
}
