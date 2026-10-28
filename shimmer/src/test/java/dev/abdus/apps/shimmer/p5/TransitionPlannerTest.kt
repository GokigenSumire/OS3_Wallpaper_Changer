package dev.abdus.apps.shimmer.p5

import dev.abdus.apps.shimmer.p2.PlaybackDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** REQ-FX-01: settings resolve to one spec per switch exactly as the P5 plan defines. */
class TransitionPlannerTest {
    private val planner = TransitionPlanner(Random(7))

    private fun plan(
        settings: TransitionSettings,
        direction: PlaybackDirection? = PlaybackDirection.NEXT,
        sameImage: Boolean = false,
        staticMode: Boolean = false,
    ) = planner.plan(settings, direction, sameImage, staticMode)

    @Test
    fun staticModeAndNoneAlwaysSwapInstantly() {
        TransitionChoice.entries.forEach { choice ->
            assertEquals(choice.name, TransitionSpec.NONE, plan(TransitionSettings(choice), staticMode = true))
            assertEquals(choice.name, TransitionSpec.NONE, plan(TransitionSettings(choice), sameImage = true, staticMode = true))
        }
        assertEquals(TransitionSpec.NONE, plan(TransitionSettings(TransitionChoice.NONE), sameImage = true))
        assertTrue(!TransitionSpec.NONE.animated)
    }

    @Test
    fun sameImageCrossfadesWithTheChosenDuration() {
        listOf(TransitionChoice.SLIDE, TransitionChoice.ZOOM, TransitionChoice.FADE_THROUGH, TransitionChoice.RANDOM).forEach {
            assertEquals(TransitionSpec.crossfade(800), plan(TransitionSettings(it, 800), sameImage = true))
        }
    }

    @Test
    fun fixedEffectsKeepTheirEffectAndANormalizedDuration() {
        val expected = mapOf(
            TransitionChoice.CROSSFADE to TransitionEffect.CROSSFADE,
            TransitionChoice.FADE_THROUGH to TransitionEffect.FADE_THROUGH,
            TransitionChoice.SLIDE to TransitionEffect.SLIDE,
            TransitionChoice.ZOOM to TransitionEffect.ZOOM,
        )
        expected.forEach { (choice, effect) ->
            // P9-EFFECTS-02：1100 ms 原样生效，不再吸附到 1000 ms 预设
            val spec = plan(TransitionSettings(choice, durationMillis = 1_100))
            assertEquals(effect, spec.effect)
            assertEquals(1_100, spec.durationMillis)
            assertTrue(spec.animated)
        }
        assertEquals(TransitionSpec.crossfade(1_500), plan(TransitionSettings()))
    }

    @Test
    fun followDirectionEntersFromTheRightOnNextAndFromTheLeftOnPrevious() {
        val slide = TransitionSettings(TransitionChoice.SLIDE)
        assertEquals(SlideDirection.LEFT, plan(slide, PlaybackDirection.NEXT).direction)
        assertEquals(SlideDirection.RIGHT, plan(slide, PlaybackDirection.PREVIOUS).direction)
        // Restore, sync and re-decode paths count as a step forward.
        assertEquals(SlideDirection.LEFT, plan(slide, direction = null).direction)
    }

    @Test
    fun fixedDirectionsIgnoreThePlaybackDirection() {
        val fixed = mapOf(
            SlideDirectionChoice.LEFT to SlideDirection.LEFT,
            SlideDirectionChoice.RIGHT to SlideDirection.RIGHT,
            SlideDirectionChoice.UP to SlideDirection.UP,
            SlideDirectionChoice.DOWN to SlideDirection.DOWN,
        )
        fixed.forEach { (choice, direction) ->
            PlaybackDirection.entries.forEach { playback ->
                assertEquals(direction, plan(TransitionSettings(TransitionChoice.SLIDE, direction = choice), playback).direction)
            }
        }
    }

    @Test
    fun randomEffectNeverRepeatsAndCoversEveryAnimatedEffect() {
        val random = TransitionSettings(TransitionChoice.RANDOM, direction = SlideDirectionChoice.RANDOM)
        val effects = (1..400).map { plan(random).effect }
        effects.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        assertEquals(TransitionEffect.ANIMATED.toSet(), effects.toSet())
    }

    @Test
    fun randomDirectionCoversAllFourDirections() {
        val slide = TransitionSettings(TransitionChoice.SLIDE, direction = SlideDirectionChoice.RANDOM)
        val directions = (1..200).map { plan(slide).direction }.toSet()
        assertEquals(SlideDirection.entries.toSet(), directions)
    }

    @Test
    fun aSameImageReloadDoesNotBreakTheNoRepeatRule() {
        val random = TransitionSettings(TransitionChoice.RANDOM)
        val first = plan(random).effect
        assertEquals(TransitionEffect.CROSSFADE, plan(random, sameImage = true).effect)
        assertNotEquals(first, plan(random).effect)
    }
}
