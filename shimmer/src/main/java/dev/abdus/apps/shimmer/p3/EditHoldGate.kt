package dev.abdus.apps.shimmer.p3

import dev.abdus.apps.shimmer.p2.PlaybackDirection
import dev.abdus.apps.shimmer.p4.ScheduleDispatch

/** Work held back while an image is being edited, replayed in [EditHoldGate.release] order. */
sealed interface HeldAction {
    /** Re-read the committed snapshot, catalog and placements. */
    object Refresh : HeldAction

    /** [dispatch] is set for a P4 scheduled switch, so its outcome can be reported. */
    data class Step(val direction: PlaybackDirection, val dispatch: ScheduleDispatch? = null) : HeldAction

    /** A direct "show this image" request, e.g. the set-image shortcut. */
    data class SetImage(val itemId: String) : HeldAction
}

/**
 * Keeps the edited image on screen until the editor closes. Main thread only.
 *
 * - Interval ticks are dropped: the interval restarts when the hold ends, so replaying one would
 *   switch away from the image the user just saved.
 * - Wallpaper gestures are dropped: while the editor window shows the wallpaper, the system may
 *   also deliver the editor's own touches to the Engine, and those are not wallpaper taps.
 * - Other scheduled, shortcut and direct switches are deferred and only the newest is kept.
 * - Refreshes are deferred and collapse into one.
 *
 * On release the refresh runs first so a switch is resolved against the latest committed state,
 * then at most one switch.
 */
class EditHoldGate {
    var holding = false
        private set
    private var refreshPending = false
    private var switchPending: HeldAction? = null

    fun begin() {
        holding = true
    }

    /** True when a timer tick may advance; false drops it. */
    fun admitTimerTick(): Boolean = !holding

    /** True when a wallpaper gesture may act; false drops it. */
    fun admitGesture(): Boolean = !holding

    /** True when [action] may run now; false when it was deferred, replacing an older switch. */
    fun admitSwitch(action: HeldAction): Boolean {
        if (!holding) return true
        if (action == HeldAction.Refresh) refreshPending = true else switchPending = action
        return false
    }

    fun admitRefresh(): Boolean = admitSwitch(HeldAction.Refresh)

    /** The switch currently deferred, so a caller can report the one a newer switch replaces. */
    val deferredSwitch: HeldAction? get() = switchPending

    /** Ends the hold and returns the deferred work: refresh first, then at most one switch. */
    fun release(): List<HeldAction> {
        val actions = listOfNotNull(HeldAction.Refresh.takeIf { refreshPending }, switchPending)
        holding = false
        refreshPending = false
        switchPending = null
        return actions
    }
}
