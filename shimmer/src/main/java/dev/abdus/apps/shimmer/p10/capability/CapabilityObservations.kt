package dev.abdus.apps.shimmer.p10.capability

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** In-memory capability evidence, intentionally unrelated to production wallpaper preferences. */
object CapabilityObservations {
    private val recorder = CapabilityRecorder(UUID.randomUUID().toString())
    private val state = MutableStateFlow(recorder.snapshot())
    private val handler = Handler(Looper.getMainLooper())
    private var touchPublicationPending = false
    private val publishTouch = Runnable {
        synchronized(this) {
            touchPublicationPending = false
            state.value = recorder.snapshot()
        }
    }
    val snapshots: StateFlow<CapabilitySnapshot> = state.asStateFlow()

    @Synchronized fun snapshot(): CapabilitySnapshot = recorder.snapshot()

    @Synchronized fun record(block: CapabilityRecorder.(Long) -> Unit) {
        recorder.block(SystemClock.elapsedRealtime())
        handler.removeCallbacks(publishTouch)
        touchPublicationPending = false
        state.value = recorder.snapshot()
    }

    /** Raw touch updates counters only; at most one pending 100 ms UI publication is kept. */
    @Synchronized fun recordTouch(block: CapabilityRecorder.(Long) -> Unit) {
        recorder.block(SystemClock.elapsedRealtime())
        if (!touchPublicationPending) {
            touchPublicationPending = true
            handler.postDelayed(publishTouch, 100L)
        }
    }

    @Synchronized fun clearEvents() {
        recorder.clearEvents()
        handler.removeCallbacks(publishTouch)
        touchPublicationPending = false
        state.value = recorder.snapshot()
    }
}
