package dev.abdus.apps.shimmer.p0

/** Pure gesture probe. Outputs are diagnostic actions, not playback operations. */
class P0GestureMachine(
    val thresholds: Thresholds,
    private val onGesture: (String) -> Unit,
) {
    data class Thresholds(
        val doubleTapTimeoutMillis: Long,
        val longPressTimeoutMillis: Long,
        val pointerJoinTimeoutMillis: Long,
        val touchSlop: Float,
        val doubleTapSlop: Float,
    ) {
        init {
            require(doubleTapTimeoutMillis > 0 && longPressTimeoutMillis > 0)
            require(pointerJoinTimeoutMillis > 0)
            require(touchSlop.isFinite() && touchSlop >= 0)
            require(doubleTapSlop.isFinite() && doubleTapSlop >= 0)
        }
    }

    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }
    data class Pointer(val id: Int, val x: Float, val y: Float)
    data class Touch(
        val action: Action,
        val uptimeMillis: Long,
        val pointers: List<Pointer>,
        val changedPointerId: Int = pointers.firstOrNull()?.id ?: -1,
    )

    companion object {
        const val DOUBLE_NEXT = "DOUBLE_NEXT"
        const val TRIPLE_PREV = "TRIPLE_PREV"
        const val TWO_FINGER_EDIT = "TWO_FINGER_EDIT"
    }

    private class Tap(val startedAt: Long, first: Pointer) {
        val origins = linkedMapOf(first.id to first)
        val activeIds = mutableSetOf(first.id)
        var pointerLifted = false
        var invalid = false
    }

    private data class Sequence(
        val fingers: Int,
        val count: Int,
        val lastUp: Long,
        val x: Float,
        val y: Float,
    )

    private var tap: Tap? = null
    private var sequence: Sequence? = null
    private var lastTime = -1L
    private var lastTouch: Touch? = null

    /** Adapter schedules this uptime deadline; no polling or wall-clock timing is needed. */
    val nextDeadlineMillis: Long?
        get() {
            val active = tap
            if (active != null) {
                return if (active.invalid) null
                else active.startedAt + thresholds.longPressTimeoutMillis
            }
            // The extra millisecond keeps a DOWN at the exact system timeout in the sequence.
            return sequence?.let { it.lastUp + thresholds.doubleTapTimeoutMillis + 1 }
        }

    fun onTouchEvent(event: Touch) {
        if (event == lastTouch) return // The same delivered event must not count twice.
        if (event.uptimeMillis < 0 || event.uptimeMillis < lastTime) {
            reset()
            return
        }
        advanceTime(event.uptimeMillis)
        lastTouch = event
        if (event.action == Action.CANCEL) {
            invalidateSequence()
            tap = null
            return
        }
        if (event.pointers.isEmpty() || event.pointers.map { it.id }.toSet().size != event.pointers.size ||
            event.pointers.any { !it.x.isFinite() || !it.y.isFinite() }
        ) {
            invalidateSequence()
            return
        }
        val ids = event.pointers.map { it.id }.toSet()
        if (event.action == Action.DOWN) {
            if (tap != null) invalidateSequence()
            if (event.pointers.size != 1 || event.changedPointerId !in ids) {
                invalidateSequence()
                tap = null
                return
            }
            tap = Tap(event.uptimeMillis, event.pointers.single())
            return
        }

        val active = tap ?: run {
            invalidateSequence()
            return
        }
        when (event.action) {
            Action.POINTER_DOWN -> {
                val newId = event.changedPointerId
                val valid = newId !in active.activeIds &&
                    ids == active.activeIds + newId &&
                    !active.pointerLifted && active.origins.size < 2 &&
                    event.uptimeMillis - active.startedAt <= thresholds.pointerJoinTimeoutMillis
                if (!valid) {
                    invalidateSequence()
                    return
                }
                checkMovement(active, event.pointers.filter { it.id != newId })
                active.origins[newId] = event.pointers.first { it.id == newId }
                active.activeIds.add(newId)
            }
            Action.MOVE -> {
                if (ids != active.activeIds) invalidateSequence()
                else checkMovement(active, event.pointers)
            }
            Action.POINTER_UP -> {
                if (ids != active.activeIds || active.activeIds.size < 2 || event.changedPointerId !in ids) {
                    invalidateSequence()
                    return
                }
                checkMovement(active, event.pointers)
                active.activeIds.remove(event.changedPointerId)
                active.pointerLifted = true
            }
            Action.UP -> {
                if (ids != active.activeIds || ids.size != 1 || event.changedPointerId !in ids) {
                    invalidateSequence()
                    tap = null
                    return
                }
                checkMovement(active, event.pointers)
                tap = null
                if (!active.invalid) completeTap(active, event.uptimeMillis)
            }
            Action.DOWN, Action.CANCEL -> Unit // Handled above.
        }
    }

    /** Called by the adapter's timer or a test's injected monotonic clock. */
    fun advanceTime(uptimeMillis: Long) {
        if (uptimeMillis < lastTime || uptimeMillis < 0) {
            reset()
            return
        }
        lastTime = uptimeMillis
        val active = tap
        if (active != null) {
            if (uptimeMillis - active.startedAt >= thresholds.longPressTimeoutMillis) invalidateSequence()
            return // A third DOWN inside the window suppresses the pending double while held.
        }
        val pending = sequence ?: return
        if (uptimeMillis - pending.lastUp > thresholds.doubleTapTimeoutMillis) {
            sequence = null
            if (pending.fingers == 1 && pending.count == 2) onGesture(DOUBLE_NEXT)
        }
    }

    fun reset() {
        tap = null
        sequence = null
        lastTime = -1L
        lastTouch = null
    }

    private fun checkMovement(active: Tap, pointers: List<Pointer>) {
        if (pointers.any { point ->
                val origin = active.origins[point.id]
                origin == null || distanceSquared(origin.x, origin.y, point.x, point.y) >
                    thresholds.touchSlop * thresholds.touchSlop
            }
        ) invalidateSequence()
    }

    private fun completeTap(completed: Tap, now: Long) {
        val fingers = completed.origins.size
        val x = completed.origins.values.sumOf { it.x.toDouble() }.toFloat() / fingers
        val y = completed.origins.values.sumOf { it.y.toDouble() }.toFloat() / fingers
        val previous = sequence
        if (previous == null) {
            sequence = Sequence(fingers, 1, now, x, y)
            return
        }
        // Mixed finger counts or widely separated taps abort; they do not seed a new sequence.
        if (previous.fingers != fingers ||
            distanceSquared(previous.x, previous.y, x, y) > thresholds.doubleTapSlop * thresholds.doubleTapSlop
        ) {
            sequence = null
            return
        }
        val count = previous.count + 1
        when {
            fingers == 1 && count == 3 -> {
                sequence = null
                onGesture(TRIPLE_PREV)
            }
            fingers == 2 && count == 2 -> {
                sequence = null
                onGesture(TWO_FINGER_EDIT)
            }
            else -> sequence = Sequence(fingers, count, now, previous.x, previous.y)
        }
    }

    private fun invalidateSequence() {
        sequence = null
        tap?.invalid = true
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
}
