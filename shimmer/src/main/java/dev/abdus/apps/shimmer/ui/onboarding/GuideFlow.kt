package dev.abdus.apps.shimmer.ui.onboarding

import kotlinx.serialization.Serializable
import dev.abdus.apps.shimmer.p0.P0GestureMachine

@Serializable
enum class GuideStep { TARGET, SOURCE, PLAYBACK, ORDER, WALLPAPER, GESTURE_NEXT, GESTURE_PREVIOUS, GESTURE_EDIT, FINISH }

@Serializable
data class GuideProgress(
    val flowVersion: Int = 4,
    val practiceSession: String? = null,
    val active: Boolean = true,
    val step: Int = 0,
    val initialTarget: String? = null,
    val visitedOtherTarget: Boolean = false,
    val ready: Boolean = false,
    val sourceTarget: String? = null,
    val sourceBaseline: List<String>? = null,
    val hasSources: Boolean = false,
    val sourceBusy: Boolean = false,
    val completed: Int = 0,
    val skipped: Int = 0,
) {
    val current: GuideStep get() = GuideStep.entries.getOrElse(step) { GuideStep.TARGET }
}

/** Only observed user actions can complete a practice; navigation and cancellation are not success. */
object GuideFlow {
    fun context(state: GuideProgress, target: String): GuideProgress =
        if (state.active && state.initialTarget == null) state.copy(initialTarget = target) else state

    fun targetSelected(state: GuideProgress, target: String): GuideProgress {
        if (!state.active || state.current != GuideStep.TARGET || state.initialTarget == null) return state
        val away = state.visitedOtherTarget || target != state.initialTarget
        return state.copy(visitedOtherTarget = away, ready = away && target == state.initialTarget)
    }

    fun sources(state: GuideProgress, target: String, ids: List<String>, busy: Boolean): GuideProgress {
        if (!state.active || state.current != GuideStep.SOURCE) return state
        if (busy) return state.copy(sourceBusy = true)
        if (state.sourceBaseline == null || state.sourceTarget != target) return state.copy(
            sourceTarget = target, sourceBaseline = ids.toList(), hasSources = ids.isNotEmpty(), sourceBusy = false,
            ready = false,
        )
        return state.copy(hasSources = ids.isNotEmpty(), sourceBusy = false,
            ready = state.ready || ids.any { it !in state.sourceBaseline })
    }

    fun existingSources(state: GuideProgress): GuideProgress =
        if (state.active && state.current == GuideStep.SOURCE && state.hasSources && !state.sourceBusy)
            state.copy(ready = true) else state

    fun routeSelected(state: GuideProgress, route: String): GuideProgress =
        if (state.active && state.current == GuideStep.PLAYBACK && route == "playback") state.copy(ready = true) else state

    fun orderApplied(state: GuideProgress): GuideProgress =
        if (state.active && state.current == GuideStep.ORDER) state.copy(ready = true) else state

    fun wallpaperPageOpened(state: GuideProgress): GuideProgress =
        if (state.active && state.current == GuideStep.WALLPAPER) state.copy(ready = true) else state

    fun expectedGesture(step: GuideStep): String? = when (step) {
            GuideStep.GESTURE_NEXT -> P0GestureMachine.DOUBLE_NEXT
            GuideStep.GESTURE_PREVIOUS -> P0GestureMachine.TRIPLE_PREV
            GuideStep.GESTURE_EDIT -> P0GestureMachine.TWO_FINGER_EDIT
            else -> null
        }

    fun practiceCompleted(state: GuideProgress, proof: GuidePracticeRequest?, target: String): GuideProgress {
        if (!state.active || proof == null || !proof.completed || proof.id != state.practiceSession ||
            proof.target != target || proof.step != state.current || expectedGesture(state.current) == null) return state
        return state.copy(ready = true)
    }

    fun advance(state: GuideProgress, skip: Boolean = false): GuideProgress {
        if (!state.active || state.current == GuideStep.FINISH || !state.ready && !skip) return state
        val bit = 1 shl state.step
        return state.copy(step = state.step + 1, ready = false, practiceSession = null,
            completed = if (skip) state.completed else state.completed or bit,
            skipped = if (skip) state.skipped or bit else state.skipped)
    }
}
