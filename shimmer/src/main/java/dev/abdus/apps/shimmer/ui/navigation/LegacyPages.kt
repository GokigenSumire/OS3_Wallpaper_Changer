package dev.abdus.apps.shimmer.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.abdus.apps.shimmer.p0.P0DiagnosticsActivity
import dev.abdus.apps.shimmer.p1.P1SourcesActivity
import dev.abdus.apps.shimmer.p2.P2PlaybackActivity
import dev.abdus.apps.shimmer.p3.P3EditActivity
import dev.abdus.apps.shimmer.p4.P4ScheduleActivity
import dev.abdus.apps.shimmer.p5.P5TransitionActivity

/**
 * P8 entry points into the verified P0–P5 pages.
 *
 * MainActivity is the only launcher, so these pages are reachable only from the four tabs. Each
 * page keeps its own behaviour; for example P2 turns static acceptance mode on whenever it is
 * created, and the P3 button is the backup entry, not the two-finger double tap.
 */
object LegacyPages {
    fun openSources(context: Context) = start(context, P1SourcesActivity::class.java)

    fun openPlayback(context: Context) = start(context, P2PlaybackActivity::class.java)

    fun openEditor(context: Context) = P3EditActivity.launch(context, targetId = null, gestureUptime = null)

    fun openSchedule(context: Context) = start(context, P4ScheduleActivity::class.java)

    fun openTransitions(context: Context) = start(context, P5TransitionActivity::class.java)

    fun openDiagnostics(context: Context) = start(context, P0DiagnosticsActivity::class.java)

    private fun start(context: Context, page: Class<*>) {
        context.startActivity(Intent(context, page))
    }
}

/**
 * Calls [onResume] whenever the tab is shown while the activity is resumed and each time the
 * activity comes back to the foreground. The tab ViewModels live as long as MainActivity, so this
 * is what makes a tab re-read settings that a P1–P5 page or another tab changed.
 */
@Composable
fun RefreshOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner) {
        // Adding the observer to a resumed lifecycle replays ON_RESUME, which loads a newly shown tab.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latest()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
