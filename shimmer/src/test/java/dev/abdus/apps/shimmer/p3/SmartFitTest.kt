package dev.abdus.apps.shimmer.p3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFitTest {
    // Target-device portrait viewport.
    private val viewWidth = 1220
    private val viewHeight = 2656
    private val smart = P3Defaults(fitMode = FitMode.COVER, blurFill = true, smartFit = true)

    @Test
    fun coverIsKeptWhileAtLeastHalfOfTheImageStaysVisible() {
        assertEquals(FitMode.COVER, SmartFit.choose(3000, 4000, viewWidth, viewHeight))
        assertEquals(FitMode.COVER, SmartFit.choose(1080, 2400, viewWidth, viewHeight))
        assertEquals(FitMode.COVER, SmartFit.choose(viewWidth, viewHeight, viewWidth, viewHeight))
        // Exactly half visible still counts as COVER.
        assertEquals(FitMode.COVER, SmartFit.choose(1000, 1000, 1000, 2000))
    }

    @Test
    fun widerImagesFitWidthAndTallerImagesFitHeight() {
        assertEquals(FitMode.FIT_WIDTH, SmartFit.choose(4000, 3000, viewWidth, viewHeight))
        assertEquals(FitMode.FIT_WIDTH, SmartFit.choose(8000, 2000, viewWidth, viewHeight))
        assertEquals(FitMode.FIT_WIDTH, SmartFit.choose(1001, 1000, 1000, 2000))
        assertEquals(FitMode.FIT_HEIGHT, SmartFit.choose(1000, 6000, viewWidth, viewHeight))
    }

    @Test
    fun adaptedDefaultsCarryTheChosenModeAndStopAdapting() {
        val adapted = smart.adaptedTo(8000, 2000, viewWidth, viewHeight)
        assertEquals(FitMode.FIT_WIDTH, adapted.fitMode)
        assertFalse(adapted.smartFit)
        assertTrue(adapted.blurFill)

        val fixed = smart.copy(smartFit = false)
        assertSame(fixed, fixed.adaptedTo(8000, 2000, viewWidth, viewHeight))
    }

    @Test
    fun onlyTheGlobalDefaultIsMarkedAsDefault() {
        val saved = ImageTransform(fitMode = FitMode.FIT_HEIGHT)
        val entry = ImageTransform(fitMode = FitMode.CONTAIN, offsetX = 0.2f)
        val previous = ImageTransform(fitMode = FitMode.FIT_WIDTH, userScale = 2f)

        assertTrue(TransformResolution.resolveCurrentPlacement(null, null, smart).fromDefault)
        assertFalse(TransformResolution.resolveCurrentPlacement(null, saved, smart).fromDefault)
        assertFalse(TransformResolution.resolveCurrentPlacement(entry, null, smart).fromDefault)
        val inherited = TransformResolution.resolve(true, null, null, smart.copy(inheritPrevious = true), previous)
        assertTrue(inherited.annotateHistory)
        assertFalse(inherited.fromDefault)
    }

    @Test
    fun smartFitAdaptsTheDefaultPlacementToTheDecodedImage() {
        val resolved = TransformResolution.resolveCurrentPlacement(null, null, smart)
        assertTrue(TransformResolution.smartFitApplies(resolved, smart))

        val panorama = TransformResolution.adaptFit(resolved, smart, 8000, 2000, viewWidth, viewHeight)
        assertEquals(ImageTransform(fitMode = FitMode.FIT_WIDTH, blurFill = true), panorama.transform)
        assertTrue(panorama.fromDefault)

        val photo = TransformResolution.adaptFit(resolved, smart, 3000, 4000, viewWidth, viewHeight)
        assertEquals(FitMode.COVER, photo.transform.fitMode)
    }

    @Test
    fun smartFitNeverOverridesASavedHistoryOrInheritedPlacement() {
        // A panorama the user saved as FIT_HEIGHT, which smart fit would show as FIT_WIDTH.
        val saved = ImageTransform(fitMode = FitMode.FIT_HEIGHT, userScale = 1.5f)
        val own = TransformResolution.resolveCurrentPlacement(null, saved, smart)
        assertFalse(TransformResolution.smartFitApplies(own, smart))
        assertEquals(own, TransformResolution.adaptFit(own, smart, 8000, 2000, viewWidth, viewHeight))

        val entry = ImageTransform(fitMode = FitMode.CONTAIN)
        val revisited = TransformResolution.resolveCurrentPlacement(entry, saved, smart)
        assertEquals(revisited, TransformResolution.adaptFit(revisited, smart, 8000, 2000, viewWidth, viewHeight))

        val previous = ImageTransform(fitMode = FitMode.FIT_WIDTH, userScale = 2f)
        val inherited = TransformResolution.resolve(true, null, null, smart.copy(inheritPrevious = true), previous)
        val fitted = TransformResolution.fitInherited(inherited, 3000, 4000, viewWidth, viewHeight)
        assertEquals(FitMode.FIT_WIDTH, TransformResolution.adaptFit(fitted, smart, 3000, 4000, viewWidth, viewHeight).transform.fitMode)
    }

    @Test
    fun withoutSmartFitTheDefaultPlacementIsShownAsConfigured() {
        val fixed = P3Defaults(fitMode = FitMode.CONTAIN, blurFill = false)
        val resolved = TransformResolution.resolveCurrentPlacement(null, null, fixed)
        assertTrue(resolved.fromDefault)
        assertFalse(TransformResolution.smartFitApplies(resolved, fixed))
        assertEquals(resolved, TransformResolution.adaptFit(resolved, fixed, 8000, 2000, viewWidth, viewHeight))
    }
}
