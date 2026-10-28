package dev.abdus.apps.shimmer.ui.navigation

import kotlin.math.abs
import kotlin.math.max

/** 实际页面横滑的纯判定；已让出的手势不重新接管，单次至多选择相邻一页。 */
internal class TabSwipeGesture(
    private val selectedIndex: Int,
    private val tabCount: Int,
    private val isLtr: Boolean,
    startX: Float,
    width: Float,
    edgeExclusion: Float,
    private val threshold: Float,
    private val touchSlop: Float,
    private val longPressTimeoutMillis: Long,
) {
    internal data class Decision(
        val consume: Boolean = false,
        val targetIndex: Int? = null,
        val finished: Boolean = false,
    )

    private enum class Phase { OBSERVING, HORIZONTAL, FINISHED }

    private var phase = if (
        tabCount > 1 && selectedIndex in 0 until tabCount &&
        startX.isFinite() && width.isFinite() && edgeExclusion.isFinite() &&
        edgeExclusion >= 0f && width > edgeExclusion * 2f &&
        startX >= edgeExclusion && startX <= width - edgeExclusion &&
        threshold.isFinite() && threshold > 0f && touchSlop.isFinite() && touchSlop >= 0f &&
        longPressTimeoutMillis > 0
    ) Phase.OBSERVING else Phase.FINISHED

    private var totalX = 0f
    private var totalY = 0f

    /** 位移和时长采用真实事件的像素／毫秒；子级优先条件在任何阈值判断前生效。 */
    fun update(
        deltaX: Float,
        deltaY: Float,
        elapsedMillis: Long,
        pointerCount: Int = 1,
        consumedByChild: Boolean = false,
        released: Boolean = false,
    ): Decision {
        if (phase == Phase.FINISHED) return Decision(finished = true)
        if (pointerCount != 1 || consumedByChild || elapsedMillis < 0 ||
            !deltaX.isFinite() || !deltaY.isFinite()
        ) return finish()

        // 长按没有先取得横向意图时，保留给页内长按或调序。
        if (phase == Phase.OBSERVING && elapsedMillis >= longPressTimeoutMillis) return finish()

        totalX += deltaX
        totalY += deltaY
        if (!totalX.isFinite() || !totalY.isFinite()) return finish()

        val horizontal = abs(totalX)
        val vertical = abs(totalY)
        if (phase == Phase.OBSERVING) {
            // 小位移既不抢点击，也不因最初的纵向抖动永久误判。
            if (released) return finish()
            if (max(horizontal, vertical) <= touchSlop) return Decision()
            if (vertical > horizontal * VerticalTolerance) return finish()
            if (horizontal <= touchSlop) return Decision()
            phase = Phase.HORIZONTAL
        }

        if (vertical > horizontal * VerticalTolerance) return finish()
        if (horizontal >= threshold) {
            val forward = if (isLtr) totalX < 0f else totalX > 0f
            val target = if (forward) selectedIndex + 1 else selectedIndex - 1
            phase = Phase.FINISHED
            return Decision(consume = true, targetIndex = target.takeIf { it in 0 until tabCount }, finished = true)
        }
        if (released) return finish(consume = true)
        return Decision(consume = true)
    }

    private fun finish(consume: Boolean = false): Decision {
        phase = Phase.FINISHED
        return Decision(consume = consume, finished = true)
    }

    private companion object {
        const val VerticalTolerance = 1.5f
    }
}
