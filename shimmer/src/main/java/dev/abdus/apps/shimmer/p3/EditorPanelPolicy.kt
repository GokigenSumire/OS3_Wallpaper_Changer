package dev.abdus.apps.shimmer.p3

/** Session-only tool groups. These names are never written to transform or playback storage. */
internal enum class EditorToolGroup(val label: String) {
    ROTATE_MIRROR("旋转镜像"), DEBUG("调试"),
}

/** Layout policy only; expanding or paging tools must not mutate the edit draft. */
internal object EditorPanelPolicy {
    const val INITIAL_EXPANDED = false
    const val TOOL_HEIGHT_PERCENT = 38
    const val MIN_TOUCH_DP = 48
    const val OVERLAY_COLOR = 0x70000000

    /** The footer remains separate. Include every tool control inside this bounded scroll area. */
    fun maxToolHeight(viewportHeight: Int, topInset: Int, bottomInset: Int): Int {
        val available = (viewportHeight.toLong().coerceAtLeast(0L) -
            topInset.toLong().coerceAtLeast(0L) - bottomInset.toLong().coerceAtLeast(0L)).coerceAtLeast(0L)
        return (available * TOOL_HEIGHT_PERCENT / 100L).toInt()
    }
}

internal enum class EditorPositionAction { ZOOM_IN, ZOOM_OUT, LEFT, RIGHT, UP, DOWN, CENTER }

/** Button and accessibility steps share the same viewport-centred operations as touch editing. */
internal object EditorPositionControls {
    const val PAN_FRACTION = 0.05f
    const val ZOOM_FACTOR = 1.1f

    fun adjust(transform: ImageTransform, action: EditorPositionAction, width: Int, height: Int): ImageTransform {
        if (width <= 0 || height <= 0) return transform
        return when (action) {
            EditorPositionAction.ZOOM_IN -> TransformMath.zoomAround(transform, ZOOM_FACTOR, width / 2f, height / 2f, width, height)
            EditorPositionAction.ZOOM_OUT -> TransformMath.zoomAround(transform, 1f / ZOOM_FACTOR, width / 2f, height / 2f, width, height)
            EditorPositionAction.LEFT -> TransformMath.pan(transform, -width * PAN_FRACTION, 0f, width, height)
            EditorPositionAction.RIGHT -> TransformMath.pan(transform, width * PAN_FRACTION, 0f, width, height)
            EditorPositionAction.UP -> TransformMath.pan(transform, 0f, -height * PAN_FRACTION, width, height)
            EditorPositionAction.DOWN -> TransformMath.pan(transform, 0f, height * PAN_FRACTION, width, height)
            EditorPositionAction.CENTER -> transform.copy(offsetX = 0f, offsetY = 0f)
        }
    }
}
