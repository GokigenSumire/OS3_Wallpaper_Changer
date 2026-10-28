package dev.abdus.apps.shimmer.p3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class EditorPanelPolicyTest {
    @Test fun toolsStartCollapsedAndKeepTwoDistinctGroups() {
        assertFalse(EditorPanelPolicy.INITIAL_EXPANDED)
        assertEquals(listOf("旋转镜像", "调试"), EditorToolGroup.entries.map { it.label })
        assertEquals(48, EditorPanelPolicy.MIN_TOUCH_DP)
    }

    @Test fun toolsUseThirtyEightPercentAfterSystemInsets() {
        assertEquals(912, EditorPanelPolicy.maxToolHeight(2656, 100, 156))
    }

    @Test fun shortWindowHasNoForcedMinimumThatCouldHideActions() {
        assertEquals(76, EditorPanelPolicy.maxToolHeight(300, 40, 60))
    }

    @Test fun negativeOrUnavailableViewportHasNoToolHeight() {
        assertEquals(0, EditorPanelPolicy.maxToolHeight(-1, 0, 0))
        assertEquals(0, EditorPanelPolicy.maxToolHeight(100, 80, 80))
    }

    @Test fun negativeInsetsCannotIncreaseHeightBeyondTheWindow() {
        assertEquals(380, EditorPanelPolicy.maxToolHeight(1000, -30, -10))
    }

    @Test fun heightArithmeticDoesNotOverflowAnInt() {
        assertEquals(816043785, EditorPanelPolicy.maxToolHeight(Int.MAX_VALUE, 0, 0))
        assertEquals(0, EditorPanelPolicy.maxToolHeight(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun upwardAndLeftwardStepsAreViewportFractions() {
        val base = ImageTransform(offsetX = 0.25f, offsetY = -0.2f)
        val up = EditorPositionControls.adjust(base, EditorPositionAction.UP, 1220, 2656)
        val left = EditorPositionControls.adjust(base, EditorPositionAction.LEFT, 1220, 2656)
        assertEquals(-0.25f, up.offsetY, 1e-6f)
        assertEquals(0.2f, left.offsetX, 1e-6f)
        assertEquals(base.userScale, left.userScale, 0f)
    }

    @Test fun downwardAndRightwardStepsAreViewportFractions() {
        val base = ImageTransform()
        assertEquals(0.05f, EditorPositionControls.adjust(base, EditorPositionAction.RIGHT, 100, 200).offsetX, 1e-6f)
        assertEquals(0.05f, EditorPositionControls.adjust(base, EditorPositionAction.DOWN, 100, 200).offsetY, 1e-6f)
    }

    @Test fun buttonZoomExactlyReusesTouchMathAtViewportCentre() {
        val base = ImageTransform(offsetX = 0.15f, offsetY = -0.3f, rotationDegrees = 90f)
        assertEquals(TransformMath.zoomAround(base, 1.1f, 610f, 1328f, 1220, 2656),
            EditorPositionControls.adjust(base, EditorPositionAction.ZOOM_IN, 1220, 2656))
        assertEquals(TransformMath.zoomAround(base, 1f / 1.1f, 610f, 1328f, 1220, 2656),
            EditorPositionControls.adjust(base, EditorPositionAction.ZOOM_OUT, 1220, 2656))
    }

    @Test fun zoomUsesTheExistingScaleBounds() {
        assertEquals(ImageTransform.MAX_SCALE,
            EditorPositionControls.adjust(ImageTransform(userScale = ImageTransform.MAX_SCALE), EditorPositionAction.ZOOM_IN, 100, 100).userScale, 0f)
        assertEquals(ImageTransform.MIN_SCALE,
            EditorPositionControls.adjust(ImageTransform(userScale = ImageTransform.MIN_SCALE), EditorPositionAction.ZOOM_OUT, 100, 100).userScale, 0f)
    }

    @Test fun centeringOnlyResetsPositionNotFitZoomRotationMirrorsOrBackground() {
        val base = ImageTransform(FitMode.CONTAIN, 2f, 0.4f, -0.6f, 90f, true, true, false)
        assertEquals(base.copy(offsetX = 0f, offsetY = 0f),
            EditorPositionControls.adjust(base, EditorPositionAction.CENTER, 100, 200))
    }

    @Test fun panPreservesOtherTransformProperties() {
        val base = ImageTransform(FitMode.FIT_HEIGHT, 3f, 0f, 0f, 45f, true, false, false)
        assertEquals(base.copy(offsetX = 0.05f),
            EditorPositionControls.adjust(base, EditorPositionAction.RIGHT, 1200, 2400))
    }

    @Test fun missingViewportRejectsEveryButtonStepWithoutMutatingTheInput() {
        val base = ImageTransform(offsetX = 1f)
        EditorPositionAction.entries.forEach {
            assertSame(base, EditorPositionControls.adjust(base, it, 0, 200))
            assertSame(base, EditorPositionControls.adjust(base, it, 100, -1))
        }
    }
}
