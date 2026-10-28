package dev.abdus.apps.shimmer.p3

import android.content.Context
import android.widget.ScrollView

/** Bounded overlay only. The sibling wallpaper gesture surface keeps its full-screen bounds. */
internal class EditorToolScroll(context: Context) : ScrollView(context) {
    var maximumHeightPixels: Int = Int.MAX_VALUE
        set(value) {
            val bounded = value.coerceAtLeast(0)
            if (field == bounded) return
            field = bounded
            requestLayout()
        }

    init {
        isClickable = true // Empty padding must not dispatch a drag to the wallpaper below.
        isFillViewport = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maximumHeightPixels
        } else {
            minOf(MeasureSpec.getSize(heightMeasureSpec), maximumHeightPixels)
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
    }
}
