package dev.abdus.apps.shimmer.p3

/** Global P3 placement defaults, stored in [dev.abdus.apps.shimmer.WallpaperPreferences]. */
data class P3Defaults(
    val fitMode: FitMode = FitMode.COVER,
    val blurFill: Boolean = true,
    val inheritPrevious: Boolean = false,
    val smartFit: Boolean = false,
) {
    fun toTransform(): ImageTransform = ImageTransform(fitMode = fitMode, blurFill = blurFill)

    /**
     * Adapts this default to a concrete placement for the given image and view dimensions.
     * When [smartFit] is true, chooses the fit mode based on aspect ratio analysis.
     */
    fun adaptedTo(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): P3Defaults {
        if (!smartFit) return this
        val chosen = SmartFit.choose(imageWidth, imageHeight, viewWidth, viewHeight)
        return copy(fitMode = chosen, smartFit = false)
    }
}

/**
 * Smart fit: automatically chooses the best fit mode based on image and screen aspect ratios.
 *
 * - If cropping in COVER mode would preserve >= 50% of the image area visible, use COVER
 * - Otherwise use FIT_WIDTH for wider images or FIT_HEIGHT for taller images
 */
object SmartFit {
    private const val COVER_VISIBILITY_THRESHOLD = 0.5f

    fun choose(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): FitMode {
        val imageRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()

        val visibleFraction = minOf(imageRatio / viewRatio, viewRatio / imageRatio)

        return if (visibleFraction >= COVER_VISIBILITY_THRESHOLD) {
            FitMode.COVER
        } else if (imageRatio > viewRatio) {
            FitMode.FIT_WIDTH
        } else {
            FitMode.FIT_HEIGHT
        }
    }
}

/** The placement an image is shown with, and whether it must be recorded on its history entry. */
data class ResolvedTransform(
    val transform: ImageTransform,
    /** True only for an inherited placement, which lives on the history entry and nowhere else. */
    val annotateHistory: Boolean,
    /** True only when the global default supplied the placement; smart fit may adapt only these. */
    val fromDefault: Boolean = false,
)

/**
 * Decides which placement an image is shown with.
 *
 * - A history entry that already carries a placement wins; revisiting history never inherits.
 * - A fresh forward advance with inheritance on copies the previously displayed placement. Only the
 *   relative values are copied; the base scale is recomputed for the new image's size.
 * - Otherwise the image's own saved config, then the global default.
 */
object TransformResolution {
    fun resolve(
        freshAdvance: Boolean,
        entryTransform: ImageTransform?,
        saved: ImageTransform?,
        defaults: P3Defaults,
        previousDisplayed: ImageTransform?,
    ): ResolvedTransform {
        if (freshAdvance && defaults.inheritPrevious && previousDisplayed != null) {
            return ResolvedTransform(previousDisplayed.sanitized(), annotateHistory = true)
        }
        if (!freshAdvance && entryTransform != null) {
            return ResolvedTransform(entryTransform.sanitized(), annotateHistory = false)
        }
        if (saved != null) return ResolvedTransform(saved.sanitized(), annotateHistory = false)
        return ResolvedTransform(defaults.toTransform().sanitized(), annotateHistory = false, fromDefault = true)
    }

    /**
     * Keeps an inherited placement on screen once the new image's size is known. Offsets are
     * viewport fractions, so a pan that framed one end of a wide panorama can put a narrower image
     * entirely off screen. Only offsets change, so a sample size chosen before this still holds.
     */
    fun fitInherited(resolved: ResolvedTransform, imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): ResolvedTransform {
        if (!resolved.annotateHistory) return resolved
        return resolved.copy(transform = TransformMath.keepVisible(resolved.transform, imageWidth, imageHeight, viewWidth, viewHeight))
    }

    /**
     * Whether smart fit picks this placement's fit mode. A saved config, a history entry and an
     * inherited placement keep the fit mode they carry; only the global default is adapted.
     */
    fun smartFitApplies(resolved: ResolvedTransform, defaults: P3Defaults): Boolean =
        defaults.smartFit && resolved.fromDefault

    /**
     * Adapts a default placement's fit mode to the actual image dimensions when smart fit is on.
     * Used after decoding to finalize smart-fit placements.
     */
    fun adaptFit(resolved: ResolvedTransform, defaults: P3Defaults, imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): ResolvedTransform {
        if (!smartFitApplies(resolved, defaults)) return resolved
        val adapted = defaults.adaptedTo(imageWidth, imageHeight, viewWidth, viewHeight)
        return resolved.copy(transform = resolved.transform.copy(fitMode = adapted.fitMode))
    }

    /** Placement for an image reached without a playback step: restore, sync or direct set. */
    fun resolveCurrent(entryTransform: ImageTransform?, saved: ImageTransform?, defaults: P3Defaults): ImageTransform =
        resolve(false, entryTransform, saved, defaults, previousDisplayed = null).transform

    /**
     * Same as [resolveCurrent] but returns the full [ResolvedTransform] for post-decode adaptation.
     */
    fun resolveCurrentPlacement(entryTransform: ImageTransform?, saved: ImageTransform?, defaults: P3Defaults): ResolvedTransform =
        resolve(false, entryTransform, saved, defaults, previousDisplayed = null)
}
