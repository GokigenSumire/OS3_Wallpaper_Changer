package dev.abdus.apps.shimmer.p10.capability

import dev.abdus.apps.shimmer.p10.EngineRoute
import dev.abdus.apps.shimmer.p10.WallpaperTarget

/** Numeric, process-local observations only. A component's marker is never a target claim. */
data class CapabilityEngineObservation(
    val id: String,
    val markerTarget: WallpaperTarget,
    val route: EngineRoute,
    val alive: Boolean = true,
    val touchRequested: Boolean = false,
    val visible: Boolean = false,
    val surfaceReady: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val rawTouchEvents: Long = 0,
    val downEvents: Long = 0,
    val upEvents: Long = 0,
    val pointerDownEvents: Long = 0,
    val pointerUpEvents: Long = 0,
    val cancelEvents: Long = 0,
    val moveEvents: Long = 0,
    val maxPointerCount: Int = 0,
    val doubleTaps: Long = 0,
    val tripleTaps: Long = 0,
    val twoFingerDoubleTaps: Long = 0,
    val surfaceCreates: Long = 0,
    val surfaceDestroys: Long = 0,
    val framesDrawn: Long = 0,
    val flagChanges: Long = 0,
    val failureCount: Long = 0,
    val createdAtMs: Long,
    val lastObservedAtMs: Long,
)

enum class CapabilityEventKind {
    CREATED, FLAGS_CHANGED, VISIBILITY, TOUCH_REQUEST, SURFACE_CREATED, SURFACE_CHANGED, SURFACE_DESTROYED,
    TOUCH, DOUBLE_TAP, TRIPLE_TAP, TWO_FINGER_DOUBLE_TAP, FRAME_POSTED, DESTROYED, FAILURE,
}

enum class CapabilityTouchAction { DOWN, UP, POINTER_DOWN, POINTER_UP, MOVE, CANCEL, OTHER }

data class CapabilityEvent(
    val sequence: Long,
    val elapsedMs: Long,
    val engineId: String,
    val kind: CapabilityEventKind,
    val detail: String,
)

data class CapabilitySnapshot(
    val session: String,
    val revision: Long = 0,
    val engines: List<CapabilityEngineObservation> = emptyList(),
    val events: List<CapabilityEvent> = emptyList(),
    val droppedEvents: Long = 0,
    val rejectedEngineSamples: Long = 0,
)

/** No Android types, files, production stores, input mutation, coordinates or private image data. */
class CapabilityRecorder(
    session: String,
    private val maxEngines: Int = 64,
    private val maxEvents: Int = 256,
) {
    init {
        require(session.isNotBlank())
        require(maxEngines in 1..64 && maxEvents in 1..256)
    }

    private var state = CapabilitySnapshot(session)
    private var nextSequence = 0L

    fun snapshot(): CapabilitySnapshot = state.copy(
        engines = state.engines.toList(), events = state.events.toList(),
    )

    fun created(id: String, marker: WallpaperTarget, flags: Int?, preview: Boolean, now: Long) {
        require(id.isNotBlank() && id.length <= 96)
        require(now >= 0)
        if (state.engines.any { it.id == id } ||
            (state.engines.size >= maxEngines && state.engines.all { it.alive })) {
            rejected()
            return
        }
        val retained = if (state.engines.size >= maxEngines) {
            val oldestDead = state.engines.first { !it.alive }.id
            state.engines.filterNot { it.id == oldestDead }
        } else state.engines
        val row = CapabilityEngineObservation(id, marker, EngineRoute(flags, preview),
            createdAtMs = now, lastObservedAtMs = now)
        state = state.copy(engines = retained + row)
        event(row, now, CapabilityEventKind.CREATED,
            "marker=$marker preview=$preview flags=${flags ?: "UNKNOWN"}")
    }

    fun flagsChanged(id: String, flags: Int?, callbackFlags: Int, now: Long) = update(
        id, now, CapabilityEventKind.FLAGS_CHANGED,
        "callbackFlags=$callbackFlags sampledFlags=${flags ?: "UNKNOWN"}",
    ) { it.copy(route = EngineRoute(flags, it.route.isPreview), flagChanges = it.flagChanges + 1) }

    fun visibility(id: String, visible: Boolean, now: Long) = update(
        id, now, CapabilityEventKind.VISIBILITY, "visible=$visible",
    ) { it.copy(visible = visible) }

    fun touchRequested(id: String, succeeded: Boolean, now: Long) = update(
        id, now, CapabilityEventKind.TOUCH_REQUEST, "requestSucceeded=$succeeded",
    ) { it.copy(touchRequested = succeeded) }

    fun surfaceCreated(id: String, now: Long) = update(
        id, now, CapabilityEventKind.SURFACE_CREATED, "surfaceReady=true",
    ) { it.copy(surfaceReady = true, surfaceCreates = it.surfaceCreates + 1) }

    fun surfaceChanged(id: String, width: Int, height: Int, now: Long) = update(
        id, now, CapabilityEventKind.SURFACE_CHANGED,
        "width=${width.coerceAtLeast(0)} height=${height.coerceAtLeast(0)}",
    ) { it.copy(width = width.coerceAtLeast(0), height = height.coerceAtLeast(0)) }

    fun surfaceDestroyed(id: String, now: Long) = update(
        id, now, CapabilityEventKind.SURFACE_DESTROYED, "surfaceReady=false",
    ) { it.copy(surfaceReady = false, surfaceDestroys = it.surfaceDestroys + 1) }

    fun touch(id: String, action: CapabilityTouchAction, pointerCount: Int, now: Long) = update(
        id, now, CapabilityEventKind.TOUCH, "", emitEvent = false,
    ) { it.copy(
        rawTouchEvents = it.rawTouchEvents + 1,
        downEvents = it.downEvents + if (action == CapabilityTouchAction.DOWN) 1 else 0,
        upEvents = it.upEvents + if (action == CapabilityTouchAction.UP) 1 else 0,
        pointerDownEvents = it.pointerDownEvents + if (action == CapabilityTouchAction.POINTER_DOWN) 1 else 0,
        pointerUpEvents = it.pointerUpEvents + if (action == CapabilityTouchAction.POINTER_UP) 1 else 0,
        cancelEvents = it.cancelEvents + if (action == CapabilityTouchAction.CANCEL) 1 else 0,
        moveEvents = it.moveEvents + if (action == CapabilityTouchAction.MOVE) 1 else 0,
        maxPointerCount = maxOf(it.maxPointerCount, pointerCount.coerceIn(0, 32)),
    ) }

    fun gesture(id: String, kind: CapabilityEventKind, now: Long) {
        require(kind == CapabilityEventKind.DOUBLE_TAP || kind == CapabilityEventKind.TRIPLE_TAP ||
            kind == CapabilityEventKind.TWO_FINGER_DOUBLE_TAP)
        update(id, now, kind, "diagnosticOnly=true") { it.copy(
            doubleTaps = it.doubleTaps + if (kind == CapabilityEventKind.DOUBLE_TAP) 1 else 0,
            tripleTaps = it.tripleTaps + if (kind == CapabilityEventKind.TRIPLE_TAP) 1 else 0,
            twoFingerDoubleTaps = it.twoFingerDoubleTaps + if (kind == CapabilityEventKind.TWO_FINGER_DOUBLE_TAP) 1 else 0,
        ) }
    }

    fun drawn(id: String, now: Long) = update(
        id, now, CapabilityEventKind.FRAME_POSTED, "canvasPosted=true",
    ) { it.copy(framesDrawn = it.framesDrawn + 1) }

    fun failure(id: String, now: Long) = update(
        id, now, CapabilityEventKind.FAILURE, "platformOperationFailed=true",
    ) { it.copy(failureCount = it.failureCount + 1) }

    fun destroyed(id: String, now: Long) = update(
        id, now, CapabilityEventKind.DESTROYED, "alive=false",
    ) { it.copy(alive = false, visible = false, surfaceReady = false) }

    /** Clears only diagnostic history; cumulative engine observations and production data survive. */
    fun clearEvents() {
        state = state.copy(events = emptyList(), revision = state.revision + 1)
    }

    private fun update(
        id: String, now: Long, kind: CapabilityEventKind, detail: String,
        emitEvent: Boolean = true,
        transform: (CapabilityEngineObservation) -> CapabilityEngineObservation,
    ) {
        require(now >= 0)
        val old = state.engines.firstOrNull { it.id == id }
        if (old == null || !old.alive || now < old.lastObservedAtMs) {
            rejected()
            return
        }
        val changed = transform(old).copy(lastObservedAtMs = now)
        state = state.copy(engines = state.engines.map { if (it.id == id) changed else it })
        if (emitEvent) event(changed, now, kind, detail)
        else state = state.copy(revision = state.revision + 1)
    }

    private fun event(row: CapabilityEngineObservation, now: Long, kind: CapabilityEventKind, detail: String) {
        val appended = state.events + CapabilityEvent(++nextSequence, now, row.id, kind, detail)
        val excess = (appended.size - maxEvents).coerceAtLeast(0)
        state = state.copy(events = appended.takeLast(maxEvents), revision = state.revision + 1,
            droppedEvents = state.droppedEvents + excess)
    }

    private fun rejected() {
        state = state.copy(rejectedEngineSamples = state.rejectedEngineSamples + 1,
            revision = state.revision + 1)
    }
}
