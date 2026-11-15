package dev.abdus.apps.shimmer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import dev.abdus.apps.shimmer.p10.WallpaperTarget

/** Reference identity also rejects a stale request when the user returns to the same target. */
class TargetTransitionRequests(initial: WallpaperTarget) {
    class Ticket internal constructor(val target: WallpaperTarget)
    private var current = Ticket(initial)
    fun select(target: WallpaperTarget): Ticket {
        if (current.target != target) current = Ticket(target)
        return current
    }
    fun isCurrent(ticket: Ticket): Boolean = current === ticket
}

data class TargetContentTransition(val alpha: Float = 1f, val blocked: Boolean = false)
val LocalTargetContentTransition = staticCompositionLocalOf { TargetContentTransition() }
val LocalWallpaperPillProgress = staticCompositionLocalOf<Float?> { null }

/** Fade the body only. A shield blocks old-target operations while the header remains available. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WallpaperTargetContent(content: @Composable BoxScope.() -> Unit) {
    val transition = LocalTargetContentTransition.current
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = transition.alpha }
        .semantics { if (transition.blocked) invisibleToUser() }) {
        content()
        if (transition.blocked) Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        })
    }
}
