package dev.abdus.apps.shimmer.p1

/** Bound main-thread progress deliveries independently of catalog size; never throttles cancellation. */
internal class P1ScanProgressReporter(
    private val deliver: (P1ScanProgress) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    private var previousTime: Long? = null
    private var previousPhase: P1ScanPhase? = null

    @Synchronized fun report(progress: P1ScanProgress, force: Boolean = false) {
        val now = nanoTime()
        val previous = previousTime
        if (force || previous == null || previousPhase != progress.phase || now - previous >= 150_000_000L) {
            previousTime = now
            previousPhase = progress.phase
            deliver(progress.copy(elapsedMillis = ((now - started) / 1_000_000L).coerceAtLeast(0L)))
        }
    }
}
