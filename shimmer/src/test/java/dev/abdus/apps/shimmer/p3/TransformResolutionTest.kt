package dev.abdus.apps.shimmer.p3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformResolutionTest {
    private val saved = ImageTransform(fitMode = FitMode.CONTAIN, userScale = 2f)
    private val entry = ImageTransform(offsetX = 0.3f)
    private val previous = ImageTransform(rotationDegrees = 90f, userScale = 1.5f)
    private val defaults = P3Defaults(fitMode = FitMode.FIT_WIDTH, blurFill = false)

    @Test
    fun freshAdvanceInheritsTheDisplayedPlacementOnlyWhenEnabled() {
        val inherited = TransformResolution.resolve(
            freshAdvance = true,
            entryTransform = null,
            saved = saved,
            defaults = defaults.copy(inheritPrevious = true),
            previousDisplayed = previous,
        )
        assertEquals(previous, inherited.transform)
        assertTrue(inherited.annotateHistory)

        val notInherited = TransformResolution.resolve(true, null, saved, defaults, previous)
        assertEquals(saved, notInherited.transform)
        assertFalse(notInherited.annotateHistory)
    }

    @Test
    fun revisitingHistoryUsesTheEntryPlacementNeverInheritance() {
        val revisited = TransformResolution.resolve(
            freshAdvance = false,
            entryTransform = entry,
            saved = saved,
            defaults = defaults.copy(inheritPrevious = true),
            previousDisplayed = previous,
        )
        assertEquals(entry, revisited.transform)
        assertFalse(revisited.annotateHistory)

        val plain = TransformResolution.resolve(false, null, saved, defaults.copy(inheritPrevious = true), previous)
        assertEquals(saved, plain.transform)
        assertFalse(plain.annotateHistory)
    }

    @Test
    fun savedConfigWinsOverTheGlobalDefaultAndDefaultIsTheLastFallback() {
        assertEquals(saved, TransformResolution.resolveCurrent(null, saved, defaults))
        assertEquals(
            ImageTransform(fitMode = FitMode.FIT_WIDTH, blurFill = false),
            TransformResolution.resolveCurrent(null, null, defaults),
        )
        assertEquals(entry, TransformResolution.resolveCurrent(entry, saved, defaults))
    }

    @Test
    fun freshAdvanceWithoutAPreviousPlacementFallsBackToSavedOrDefault() {
        val first = TransformResolution.resolve(true, null, null, defaults.copy(inheritPrevious = true), null)
        assertEquals(defaults.toTransform(), first.transform)
        assertFalse(first.annotateHistory)
    }

    @Test
    fun inheritedPanIsKeptOnScreenForTheNewImageOnly() {
        // A pan to one end of a zoomed panorama, inherited by a portrait photo.
        val panorama = ImageTransform(userScale = 5f, offsetX = 30f)
        val inherited = ResolvedTransform(panorama, annotateHistory = true)
        val fitted = TransformResolution.fitInherited(inherited, 3000, 4000, 1220, 2656)
        assertEquals(TransformMath.keepVisible(panorama, 3000, 4000, 1220, 2656), fitted.transform)
        assertTrue(fitted.transform.offsetX in 1f..10f)
        assertEquals(5f, fitted.transform.userScale, 0f)
        assertTrue(fitted.annotateHistory)

        // Saved configs and history entries are shown exactly as recorded.
        val own = ResolvedTransform(panorama, annotateHistory = false)
        assertEquals(own, TransformResolution.fitInherited(own, 3000, 4000, 1220, 2656))
    }

    @Test
    fun resolvedPlacementsAreAlwaysSanitized() {
        val wild = ImageTransform(userScale = 99f, offsetX = Float.NaN, rotationDegrees = 540f)
        val resolved = TransformResolution.resolveCurrent(wild, null, defaults)
        assertEquals(ImageTransform.MAX_SCALE, resolved.userScale, 0f)
        assertEquals(0f, resolved.offsetX, 0f)
        assertEquals(180f, resolved.rotationDegrees, 0f)
    }
}
