package dev.abdus.apps.shimmer.p3

/** Translate the already-clipped root rectangle to screen space, never expand to full bounds. */
internal object EditorOverlayBounds {
    fun contains(
        left: Int, top: Int, right: Int, bottom: Int,
        rootScreenX: Int, rootScreenY: Int, rawX: Float, rawY: Float,
    ): Boolean {
        if (right <= left || bottom <= top || !rawX.isFinite() || !rawY.isFinite()) return false
        val screenLeft = left.toLong() + rootScreenX.toLong()
        val screenTop = top.toLong() + rootScreenY.toLong()
        val screenRight = right.toLong() + rootScreenX.toLong()
        val screenBottom = bottom.toLong() + rootScreenY.toLong()
        val x = rawX.toDouble()
        val y = rawY.toDouble()
        return x >= screenLeft && x < screenRight && y >= screenTop && y < screenBottom
    }
}
