package dev.abdus.apps.shimmer.p5

import kotlin.math.roundToInt

/**
 * What one transition frame draws, as defined in the P5 plan ("效果定义"). A "composite" is one
 * image's fill plus foreground with its own placement.
 *
 * @property drawOld the outgoing composite, always opaque and unscaled.
 * @property newAlpha opacity of the incoming composite over the outgoing one.
 * @property newScale scale of the incoming composite about the screen centre.
 * @property blackOverlay opacity of a solid black layer drawn last.
 * @property slide eased fraction of the screen the content has moved (SLIDE only).
 */
data class TransitionFrame(
    val drawOld: Boolean,
    val drawNew: Boolean,
    val newAlpha: Float = 1f,
    val newScale: Float = 1f,
    val blackOverlay: Float = 0f,
    val slide: Float = 0f,
) {
    /** The incoming composite must go through an offscreen target to fade or scale as one layer. */
    val needsTarget: Boolean get() = drawNew && (newAlpha < 1f || newScale != 1f)
}

object TransitionTimeline {
    const val ZOOM_START_SCALE = 1.12f

    /** Decelerate: d(p) = 1 - (1 - p)². */
    fun decelerate(p: Float): Float = 1f - (1f - p) * (1f - p)

    /** Smoothstep: s(x) = x²(3 - 2x). */
    fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    /** Frame at linear progress [progress] (0 → 1) of [effect]. */
    fun frame(effect: TransitionEffect, progress: Float): TransitionFrame {
        val p = progress.coerceIn(0f, 1f)
        return when (effect) {
            TransitionEffect.NONE -> TransitionFrame(drawOld = false, drawNew = true)
            TransitionEffect.CROSSFADE -> TransitionFrame(drawOld = true, drawNew = true, newAlpha = decelerate(p))
            TransitionEffect.FADE_THROUGH ->
                if (p < 0.5f) TransitionFrame(drawOld = true, drawNew = false, blackOverlay = smooth(2f * p))
                else TransitionFrame(drawOld = false, drawNew = true, blackOverlay = smooth(2f - 2f * p))
            TransitionEffect.SLIDE -> TransitionFrame(drawOld = true, drawNew = true, slide = smooth(p))
            TransitionEffect.ZOOM -> {
                val d = decelerate(p)
                TransitionFrame(
                    drawOld = true, drawNew = true, newAlpha = d,
                    // Written so the scale is exactly 1 at the end, never 0.99999994.
                    newScale = 1f + (ZOOM_START_SCALE - 1f) * (1f - d),
                )
            }
        }
    }

    /**
     * Whole-pixel window offsets of the outgoing and incoming composites for a slide of [moved]
     * (0 → 1) along [direction] on a [width]×[height] surface. They always differ by exactly one
     * screen, so the two composites meet edge to edge with no gap or overlap.
     */
    fun slideOffsets(direction: SlideDirection, moved: Float, width: Int, height: Int): SlideOffsets {
        val extent = if (direction.dx != 0) width else height
        val travelled = (moved.coerceIn(0f, 1f) * extent).roundToInt()
        return SlideOffsets(
            oldX = direction.dx * travelled,
            oldY = direction.dy * travelled,
            newX = direction.dx * (travelled - extent),
            newY = direction.dy * (travelled - extent),
        )
    }
}

data class SlideOffsets(val oldX: Int, val oldY: Int, val newX: Int, val newY: Int)
