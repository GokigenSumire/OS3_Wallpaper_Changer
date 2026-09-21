package dev.abdus.apps.shimmer.p0

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-machine evidence only: these cases do not prove the launcher forwards touch events. */
class P0GestureMachineTest {
    private class Probe {
        val actions = mutableListOf<String>()
        val machine = P0GestureMachine(
            P0GestureMachine.Thresholds(
                doubleTapTimeoutMillis = 300,
                longPressTimeoutMillis = 500,
                pointerJoinTimeoutMillis = 100,
                touchSlop = 10f,
                doubleTapSlop = 100f,
            ),
            { actions.add(it) },
        )

        fun touch(
            action: P0GestureMachine.Action,
            time: Long,
            vararg pointers: P0GestureMachine.Pointer,
            changedId: Int = pointers.firstOrNull()?.id ?: -1,
        ) = machine.onTouchEvent(P0GestureMachine.Touch(action, time, pointers.toList(), changedId))

        fun singleTap(time: Long, duration: Long = 20, id: Int = 0, x: Float = 50f, y: Float = 50f) {
            val point = point(id, x, y)
            touch(P0GestureMachine.Action.DOWN, time, point)
            touch(P0GestureMachine.Action.UP, time + duration, point)
        }

        fun twoFingerTap(time: Long, liftFirstPointer: Boolean = false, firstId: Int = 0, secondId: Int = 9) {
            val first = point(firstId)
            val second = point(secondId, 80f)
            touch(P0GestureMachine.Action.DOWN, time, first)
            touch(P0GestureMachine.Action.POINTER_DOWN, time + 10, first, second, changedId = secondId)
            touch(
                P0GestureMachine.Action.POINTER_UP, time + 30, second, first,
                changedId = if (liftFirstPointer) firstId else secondId,
            )
            touch(P0GestureMachine.Action.UP, time + 40, if (liftFirstPointer) second else first)
        }

        fun settle(time: Long = 2_000) = machine.advanceTime(time)
    }

    @Test fun doubleWaitsForThirdTapWindowAndDispatchesOnlyOnce() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        assertTrue(p.actions.isEmpty())
        assertEquals(421L, p.machine.nextDeadlineMillis ?: -1L)
        p.settle(420)
        assertTrue(p.actions.isEmpty())
        p.settle(421)
        p.settle(2_000)
        assertEquals(listOf(P0GestureMachine.DOUBLE_NEXT), p.actions)
        assertNull(p.machine.nextDeadlineMillis)
    }

    @Test fun tripleNeverDispatchesNextBeforeOrAfterPrevious() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        p.singleTap(200)
        assertEquals(listOf(P0GestureMachine.TRIPLE_PREV), p.actions)
        p.settle()
        assertEquals(listOf(P0GestureMachine.TRIPLE_PREV), p.actions)
    }

    @Test fun thirdDownAtExactTimeoutRemainsTripleEvenWhenUpIsAfterDeadline() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        p.touch(P0GestureMachine.Action.DOWN, 420, point())
        p.settle(421)
        assertTrue(p.actions.isEmpty())
        p.touch(P0GestureMachine.Action.UP, 450, point())
        p.settle()
        assertEquals(listOf(P0GestureMachine.TRIPLE_PREV), p.actions)
    }

    @Test fun thirdDownAfterTimeoutDoesNotTurnPreviousDoubleIntoTriple() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        p.singleTap(421)
        p.settle()
        assertEquals(listOf(P0GestureMachine.DOUBLE_NEXT), p.actions)
    }

    @Test fun twoFingerDoubleAcceptsEitherLiftOrderAndPointerIndexOrder() {
        val p = Probe()
        p.twoFingerTap(0, liftFirstPointer = true)
        p.twoFingerTap(100, liftFirstPointer = false, firstId = 5, secondId = 7)
        p.settle()
        assertEquals(listOf(P0GestureMachine.TWO_FINGER_EDIT), p.actions)
    }

    @Test fun thirtyOfEachValidGestureProduceExactlyTheirOwnThirtyActions() {
        val p = Probe()
        repeat(30) { index ->
            val base = index * 3_000L
            p.singleTap(base)
            p.singleTap(base + 100)
            p.settle(base + 421)
            p.singleTap(base + 1_000)
            p.singleTap(base + 1_100)
            p.singleTap(base + 1_200)
            p.twoFingerTap(base + 2_000, liftFirstPointer = index % 2 == 0)
            p.twoFingerTap(base + 2_100, liftFirstPointer = index % 2 != 0)
            p.settle(base + 2_800)
        }
        assertEquals(90, p.actions.size)
        assertEquals(30, p.actions.count { it == P0GestureMachine.DOUBLE_NEXT })
        assertEquals(30, p.actions.count { it == P0GestureMachine.TRIPLE_PREV })
        assertEquals(30, p.actions.count { it == P0GestureMachine.TWO_FINGER_EDIT })
        assertEquals(
            List(30) { listOf(P0GestureMachine.DOUBLE_NEXT, P0GestureMachine.TRIPLE_PREV, P0GestureMachine.TWO_FINGER_EDIT) }.flatten(),
            p.actions,
        )
    }

    @Test fun duplicateCallbacksCannotIncreaseTheCount() {
        val p = Probe()
        listOf(0L, 100L, 200L).forEach { time ->
            val down = P0GestureMachine.Touch(P0GestureMachine.Action.DOWN, time, listOf(point()))
            val up = P0GestureMachine.Touch(P0GestureMachine.Action.UP, time + 20, listOf(point()))
            p.machine.onTouchEvent(down)
            p.machine.onTouchEvent(down.copy())
            p.machine.onTouchEvent(up)
            p.machine.onTouchEvent(up.copy())
        }
        p.settle()
        assertEquals(listOf(P0GestureMachine.TRIPLE_PREV), p.actions)
    }

    @Test fun sameTimestampWithDifferentActionsIsNotDiscardedAsADuplicate() {
        val p = Probe()
        p.singleTap(0, duration = 0)
        p.singleTap(100, duration = 0)
        p.settle()
        assertEquals(listOf(P0GestureMachine.DOUBLE_NEXT), p.actions)
    }

    @Test fun cancelWhileDoubleIsPendingDropsItAndFreshSequenceCanRecover() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        p.touch(P0GestureMachine.Action.CANCEL, 150)
        assertNull(p.machine.nextDeadlineMillis)
        p.settle(1_000)
        assertTrue(p.actions.isEmpty())
        p.singleTap(1_100)
        p.singleTap(1_200)
        p.settle()
        assertEquals(listOf(P0GestureMachine.DOUBLE_NEXT), p.actions)
    }

    @Test fun cancelDuringThirdTapDoesNotFallBackToDouble() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        p.touch(P0GestureMachine.Action.DOWN, 200, point())
        p.touch(P0GestureMachine.Action.CANCEL, 210)
        p.touch(P0GestureMachine.Action.UP, 220, point())
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun longPressAtSystemThresholdIsRejectedWithOrWithoutTimer() {
        listOf(false, true).forEach { runTimer ->
            val p = Probe()
            p.singleTap(0)
            p.singleTap(100)
            p.touch(P0GestureMachine.Action.DOWN, 200, point())
            if (runTimer) p.settle(700)
            p.touch(P0GestureMachine.Action.UP, 700, point())
            p.settle()
            assertTrue("runTimer=$runTimer", p.actions.isEmpty())
        }
    }

    @Test fun movementAndReturnToOriginRejectTheTap() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        p.touch(P0GestureMachine.Action.DOWN, 200, point())
        p.touch(P0GestureMachine.Action.MOVE, 205, point(x = 61f))
        p.touch(P0GestureMachine.Action.MOVE, 210, point())
        p.touch(P0GestureMachine.Action.UP, 220, point())
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun upDisplacementIsCheckedEvenWithoutMoveEvents() {
        val p = Probe()
        p.singleTap(0)
        p.touch(P0GestureMachine.Action.DOWN, 100, point())
        p.touch(P0GestureMachine.Action.UP, 120, point(x = 61f))
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun touchSlopBoundaryIsInclusiveAndDiagonalMovementUsesEuclideanDistance() {
        val p = Probe()
        p.singleTap(0)
        p.touch(P0GestureMachine.Action.DOWN, 100, point())
        p.touch(P0GestureMachine.Action.MOVE, 110, point(x = 56f, y = 58f))
        p.touch(P0GestureMachine.Action.UP, 120, point(x = 56f, y = 58f))
        p.settle()
        assertEquals(listOf(P0GestureMachine.DOUBLE_NEXT), p.actions)
    }

    @Test fun separatedTapLocationsDoNotCountAsADouble() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100, x = 151f)
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun tapsOutsideTimeoutDoNotCombine() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(321)
        p.singleTap(642)
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun mixedFingerCountsCannotFallBackToSingleOrDouble() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        p.twoFingerTap(200)
        p.settle(800)
        p.twoFingerTap(1_000)
        p.singleTap(1_100)
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun lateJoiningSecondFingerIsRejected() {
        val p = Probe()
        p.twoFingerTap(0)
        p.touch(P0GestureMachine.Action.DOWN, 100, point())
        p.touch(P0GestureMachine.Action.POINTER_DOWN, 201, point(), point(1, 80f), changedId = 1)
        p.touch(P0GestureMachine.Action.POINTER_UP, 210, point(), point(1, 80f), changedId = 1)
        p.touch(P0GestureMachine.Action.UP, 220, point())
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun replacementFingerAfterLiftIsRejectedEvenWhenMaximumCountIsTwo() {
        val p = Probe()
        p.twoFingerTap(0)
        p.touch(P0GestureMachine.Action.DOWN, 100, point())
        p.touch(P0GestureMachine.Action.POINTER_DOWN, 110, point(), point(1, 80f), changedId = 1)
        p.touch(P0GestureMachine.Action.POINTER_UP, 120, point(), point(1, 80f), changedId = 1)
        p.touch(P0GestureMachine.Action.POINTER_DOWN, 130, point(), point(2, 80f), changedId = 2)
        p.touch(P0GestureMachine.Action.POINTER_UP, 140, point(), point(2, 80f), changedId = 2)
        p.touch(P0GestureMachine.Action.UP, 150, point())
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun threeFingersRejectTheSequence() {
        val p = Probe()
        p.twoFingerTap(0)
        p.touch(P0GestureMachine.Action.DOWN, 100, point())
        p.touch(P0GestureMachine.Action.POINTER_DOWN, 110, point(), point(1, 80f), changedId = 1)
        p.touch(P0GestureMachine.Action.POINTER_DOWN, 120, point(), point(1, 80f), point(2, 100f), changedId = 2)
        p.touch(P0GestureMachine.Action.POINTER_UP, 130, point(), point(1, 80f), point(2, 100f), changedId = 2)
        p.touch(P0GestureMachine.Action.POINTER_UP, 140, point(), point(1, 80f), changedId = 1)
        p.touch(P0GestureMachine.Action.UP, 150, point())
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun missingPointerDownOrPointerUpCannotProduceEdit() {
        listOf(false, true).forEach { skipPointerDown ->
            val p = Probe()
            p.twoFingerTap(0)
            p.touch(P0GestureMachine.Action.DOWN, 100, point())
            if (skipPointerDown) {
                p.touch(P0GestureMachine.Action.MOVE, 110, point(), point(1, 80f))
                p.touch(P0GestureMachine.Action.POINTER_UP, 120, point(), point(1, 80f), changedId = 1)
            } else {
                p.touch(P0GestureMachine.Action.POINTER_DOWN, 110, point(), point(1, 80f), changedId = 1)
            }
            p.touch(P0GestureMachine.Action.UP, 130, point())
            p.settle()
            assertTrue("skipPointerDown=$skipPointerDown", p.actions.isEmpty())
        }
    }

    @Test fun orphanUpDoesNotCountAsATap() {
        val p = Probe()
        p.touch(P0GestureMachine.Action.UP, 0, point())
        p.touch(P0GestureMachine.Action.UP, 100, point())
        p.touch(P0GestureMachine.Action.UP, 200, point())
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    @Test fun malformedPointerIdentityAndNonFiniteCoordinatesAreRejected() {
        listOf(
            listOf(point(), point()),
            listOf(point(x = Float.NaN)),
            listOf(point(y = Float.POSITIVE_INFINITY)),
            emptyList(),
        ).forEach { malformed ->
            val p = Probe()
            p.singleTap(0)
            p.machine.onTouchEvent(P0GestureMachine.Touch(P0GestureMachine.Action.DOWN, 100, malformed))
            p.touch(P0GestureMachine.Action.UP, 120, point())
            p.settle()
            assertTrue("malformed=$malformed", p.actions.isEmpty())
        }
    }

    @Test fun outOfOrderEventAndClockRollbackDropPendingActions() {
        listOf(false, true).forEach { timerRollback ->
            val p = Probe()
            p.singleTap(0)
            p.singleTap(100)
            if (timerRollback) p.settle(110)
            else p.touch(P0GestureMachine.Action.DOWN, 110, point())
            p.settle()
            assertTrue("timerRollback=$timerRollback", p.actions.isEmpty())
        }
    }

    @Test fun resetDropsPendingDeadlineAndGesture() {
        val p = Probe()
        p.singleTap(0)
        p.singleTap(100)
        p.machine.reset()
        assertNull(p.machine.nextDeadlineMillis)
        p.settle()
        assertTrue(p.actions.isEmpty())
    }

    companion object {
        private fun point(id: Int = 0, x: Float = 50f, y: Float = 50f) = P0GestureMachine.Pointer(id, x, y)
    }
}
