package dev.abdus.apps.shimmer.p0

import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

data class DiagnosticToken(val runId: String, val epoch: Long)
data class DiagnosticClockValue(val wallMs: Long, val monoMs: Long, val uptimeMs: Long)

/** Only owner-thread values or correctly synchronized caches; never stores an Engine or GL context. */
data class EngineDiagnosticState(
    val scope: String,
    val serviceType: String,
    val isPreview: Boolean? = null,
    val alive: Boolean? = null,
    val touchRequested: Boolean? = null,
    val visible: Boolean? = null,
    val surfaceValid: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rendererReady: Boolean? = null,
    val editHold: Boolean? = null,
    val source: String = "OWNER_CACHE",
)

data class DiagnosticPointer(val id: Int, val x: Float, val y: Float)
data class DiagnosticTouchData(
    val actionMasked: Int,
    val actionIndex: Int,
    val eventTime: Long,
    val downTime: Long,
    val pointerCount: Int,
    val pointers: List<DiagnosticPointer>,
    val historySize: Int,
    val editHold: Boolean,
)

/** Detached scalars and a run token only. A routing observation cannot count the same event twice. */
class DiagnosticTouchTicket internal constructor(
    internal val token: DiagnosticToken,
    internal val scope: String,
    internal val sequence: Long,
) {
    internal val resolved = AtomicBoolean(false)
}

data class DiagnosticEngineCounts(
    val received: Long = 0,
    val forwarded: Long = 0,
    val ignored: Long = 0,
    val ignoredEditHold: Long = 0,
    val ignoredOther: Long = 0,
    val tapCommands: Long = 0,
    val secondaryTapCommands: Long = 0,
    val otherCommands: Long = 0,
) {
    val commands: Long get() = tapCommands + secondaryTapCommands + otherCommands
}

data class DiagnosticEngineSnapshot(
    val state: EngineDiagnosticState,
    val counts: DiagnosticEngineCounts,
    val sampledAt: DiagnosticClockValue,
    val sampleSource: String,
    val ownerSampledAt: DiagnosticClockValue? = null,
)

data class DiagnosticStoredSnapshot(
    val token: DiagnosticToken,
    val processSession: String,
    val startedAtWallMs: Long,
    val enabled: Boolean,
    val revision: Long,
    val tail: String,
)

data class DiagnosticSnapshot(
    val token: DiagnosticToken,
    val processSession: String,
    val startedAtWallMs: Long,
    val cutoffWallMs: Long,
    val enabled: Boolean,
    val revision: Long,
    val summary: String,
    val content: String,
    val fileName: String,
    val persistenceStatus: String,
    val engines: List<DiagnosticEngineSnapshot>,
    val events: List<String>,
    val overwrittenRecords: Long,
    val restoredRecords: Int,
) {
    val runId: String get() = token.runId
    val persistencePending: Boolean get() = persistenceStatus == "PENDING"
    val tail: String get() = events.takeLast(18).joinToString("\n")
}

/** Bounded observation state. Its monitor is never held across preference I/O or scheduling. */
class DiagnosticRunCore(
    val processSession: String,
    private val clock: () -> DiagnosticClockValue,
    private val maxEvents: Int = 1600,
    private val maxLine: Int = 640,
    private val maxEngines: Int = 128,
) {
    private data class Record(val text: String, val restored: Boolean)
    private val ring = ArrayDeque<Record>()
    private val registry = linkedMapOf<String, EngineDiagnosticState>()
    private val ownerTimes = linkedMapOf<String, DiagnosticClockValue>()
    private val samples = linkedMapOf<String, DiagnosticEngineSnapshot>()
    private val counts = linkedMapOf<String, DiagnosticEngineCounts>()
    private val gestures = linkedMapOf<String, Long>()
    private var epoch = 1L
    private var run = DiagnosticToken("run-$processSession-$epoch", epoch)
    private var startedAt = clock().wallMs
    private var collecting = false
    private var revision = 0L
    private var sequence = 0L
    private var overwritten = 0L
    private var engineDrops = 0L
    private var acknowledgedRevision = -1L
    private var persistenceFailure: String? = null
    private var restoredRun: String? = null

    init { require(maxEvents > 0 && maxLine >= 160 && maxEngines > 0) }

    @Synchronized fun token(): DiagnosticToken = run
    @Synchronized fun isCurrent(token: DiagnosticToken): Boolean = token == run
    @Synchronized fun enabled(): Boolean = collecting
    @Synchronized fun revision(): Long = revision
    @Synchronized fun lastEvent(): String? = ring.lastOrNull()?.text

    /** Restored lines retain their embedded old identities; their counters are UNKNOWN, never live. */
    @Synchronized fun restoreHistory(tail: String, previousRun: String?, wasEnabled: Boolean) {
        if (revision != 0L || ring.isNotEmpty()) return
        restoredRun = previousRun?.take(96)
        tail.lineSequence().filter { it.isNotBlank() }.take(maxEvents).forEach {
            ring.addLast(Record(it.take(maxLine), true))
        }
        collecting = wasEnabled
        if (wasEnabled) append("APP", "PROCESS_START", "restoredHistory=true restoredCounters=UNKNOWN")
    }

    @Synchronized fun startPreserving(): DiagnosticToken {
        collecting = true
        append("APP", "DIAGNOSTICS_START", "recordsPreserved=true")
        baseline("START_BASELINE")
        return run
    }

    @Synchronized fun stop(): DiagnosticToken {
        collecting = false
        append("APP", "DIAGNOSTICS_STOP", "recordsPreserved=true")
        return run
    }

    @Synchronized fun resetAndStart(): DiagnosticToken {
        epoch++
        run = DiagnosticToken("run-$processSession-$epoch", epoch)
        startedAt = clock().wallMs
        collecting = true
        ring.clear(); samples.clear(); counts.clear(); gestures.clear()
        sequence = 0; overwritten = 0; engineDrops = 0; restoredRun = null
        revision = 0; acknowledgedRevision = -1; persistenceFailure = null
        append("APP", "RUN_START", "diagnosticsOnly=true countersBaseline=0")
        baseline("RESET_BASELINE")
        return run
    }

    @Synchronized fun registerEngine(state: EngineDiagnosticState) {
        val safe = boundedState(state)
        if (registry.size >= maxEngines && !registry.containsKey(safe.scope)) {
            engineDrops++
            return
        }
        registry[safe.scope] = safe
        ownerTimes[safe.scope] = clock()
        if (collecting) recordSample(safe, "ENGINE_REGISTER", true)
    }

    @Synchronized fun updateEngineState(state: EngineDiagnosticState, expected: DiagnosticToken? = null): Boolean {
        // Delayed samples are rejected. Unconditional owner state updates keep the live cache current.
        if (expected != null && expected != run) return false
        val safe = boundedState(state)
        if (!registry.containsKey(safe.scope)) return false
        registry[safe.scope] = safe
        ownerTimes[safe.scope] = clock()
        if (collecting) recordSample(safe, safe.source, true)
        return true
    }

    /** A real owner update may outlive a run; update cache without pretending its old sample is new. */
    @Synchronized fun updateEngineCache(state: EngineDiagnosticState): Boolean {
        val safe = boundedState(state)
        if (!registry.containsKey(safe.scope)) return false
        registry[safe.scope] = safe
        ownerTimes[safe.scope] = clock()
        return true
    }

    @Synchronized fun unregisterEngine(scope: String) {
        val previous = registry.remove(scope) ?: return
        ownerTimes.remove(scope)
        recordSample(previous.copy(alive = false), "OWNER_DESTROY", collecting, ownerAt = clock())
    }

    @Synchronized fun event(scope: String, kind: String, detail: String = "", expected: DiagnosticToken? = null): Boolean {
        if (!collecting || expected != null && expected != run) return false
        append(scope, kind, detail)
        return true
    }

    @Synchronized fun gesture(scope: String, kind: String) {
        if (!collecting) return
        val key = "${safeLabel(scope)}/${safeLabel(kind)}"
        // Fixed gesture kinds per bounded Engine table; no arbitrary-name counter map growth.
        if (kind !in setOf("DOUBLE_NEXT", "TRIPLE_PREV", "TWO_FINGER_EDIT")) {
            append(scope, "GESTURE", "action=OTHER diagnosticOnly=true")
            return
        }
        if (engineCounts(scope) == null) return
        gestures[key] = (gestures[key] ?: 0) + 1
        append(scope, "GESTURE", "action=$kind count=${gestures[key]} diagnosticOnly=true")
    }

    @Synchronized fun beginTouch(scope: String, data: DiagnosticTouchData): DiagnosticTouchTicket? {
        if (!collecting) return null
        val old = engineCounts(scope) ?: return null
        counts[scope] = old.copy(received = old.received + 1)
        val eventSequence = ++sequence
        val pointers = data.pointers.take(32).joinToString(";") {
            "${it.id}:${coordinate(it.x)},${coordinate(it.y)}"
        }
        append(scope, "TOUCH_RECEIVED", "sequence=$eventSequence actionMasked=${data.actionMasked} " +
            "actionIndex=${data.actionIndex} eventTime=${data.eventTime} downTime=${data.downTime} " +
            "pointerCount=${data.pointerCount} historySize=${data.historySize} editHold=${data.editHold} " +
            "pointers=$pointers")
        return DiagnosticTouchTicket(run, scope, eventSequence)
    }

    @Synchronized fun routeTouch(ticket: DiagnosticTouchTicket?, forwarded: Boolean, reason: String): Boolean {
        if (ticket == null || !collecting || ticket.token != run || !ticket.resolved.compareAndSet(false, true)) return false
        val old = engineCounts(ticket.scope) ?: return false
        counts[ticket.scope] = if (forwarded) old.copy(forwarded = old.forwarded + 1)
        else old.copy(ignored = old.ignored + 1,
            ignoredEditHold = old.ignoredEditHold + if (reason == "EDIT_HOLD") 1 else 0,
            ignoredOther = old.ignoredOther + if (reason != "EDIT_HOLD") 1 else 0)
        append(ticket.scope, "TOUCH_ROUTE", "sequence=${ticket.sequence} " +
            "destination=${if (forwarded) "FORWARDED" else "IGNORED"} reason=${safeLabel(reason)}")
        return true
    }

    @Synchronized fun command(scope: String, command: String?, x: Int, y: Int, z: Int, resultRequested: Boolean) {
        if (!collecting) return
        val old = engineCounts(scope) ?: return
        val classification = when (command) {
            "android.wallpaper.tap" -> "TAP"
            "android.wallpaper.secondaryTap" -> "SECONDARY_TAP"
            else -> "OTHER"
        }
        counts[scope] = when (classification) {
            "TAP" -> old.copy(tapCommands = old.tapCommands + 1)
            "SECONDARY_TAP" -> old.copy(secondaryTapCommands = old.secondaryTapCommands + 1)
            else -> old.copy(otherCommands = old.otherCommands + 1)
        }
        val name = command?.let { if (it.length <= 96 && it.all { c -> c.isLetterOrDigit() || c in "._-" }) it else "NON_IDENTIFIER" } ?: "NULL"
        append(scope, "WALLPAPER_COMMAND", "command=$name class=$classification x=$x y=$y z=$z " +
            "resultRequested=$resultRequested observationOnly=true")
    }

    @Synchronized fun markPersistence(token: DiagnosticToken, storedRevision: Long, success: Boolean, failure: String? = null) {
        if (token != run) return
        if (success) {
            acknowledgedRevision = maxOf(acknowledgedRevision, storedRevision)
            persistenceFailure = null
        } else persistenceFailure = safeLabel(failure ?: "COMMIT_FAILED")
    }

    @Synchronized fun storedSnapshot(): DiagnosticStoredSnapshot = DiagnosticStoredSnapshot(
        run, processSession, startedAt, collecting, revision, ring.joinToString("\n") { it.text })

    @Synchronized fun snapshot(identity: String, source: String = "UI_REFRESH"): DiagnosticSnapshot {
        val at = clock()
        // Snapshot metadata is detached; it does not append events or restart collection after stop.
        val currentSamples = samples.toMutableMap()
        registry.values.forEach { state ->
            currentSamples[state.scope] = DiagnosticEngineSnapshot(state,
                counts[state.scope] ?: DiagnosticEngineCounts(), at, "${safeLabel(source)}:${state.source}",
                ownerSampledAt = ownerTimes[state.scope])
        }
        val engineList = Collections.unmodifiableList(currentSamples.values.map {
            it.copy(counts = counts[it.state.scope] ?: it.counts)
        })
        val eventList = Collections.unmodifiableList(ring.map { it.text })
        val restored = ring.count { it.restored }
        val status = when {
            persistenceFailure != null -> "FAILED"
            acknowledgedRevision >= revision -> "SAVED"
            revision == 0L -> "NOT_REQUESTED"
            else -> "PENDING"
        }
        val summary = buildString {
            appendLine(identity)
            appendLine("session=$processSession processSession=$processSession diagnosticRunId=${run.runId} epoch=${run.epoch} enabled=$collecting")
            appendLine("startedAtWallMs=$startedAt cutoffWallMs=${at.wallMs} ring=${ring.size}/$maxEvents overwrittenRecords=$overwritten restoredRecords=$restored")
            appendLine("persistence=$status revision=$revision acknowledgedRevision=$acknowledgedRevision failure=${persistenceFailure ?: "NONE"}")
            appendLine("Counters are per diagnostic run / Engine; PREVIEW and FORMAL are separate. Touch requested is not guaranteed delivery.")
            appendLine("restoredHistory=${restored > 0} restoredRunId=${restoredRun ?: "UNKNOWN"} restoredCounters=UNKNOWN engineCapacityDrops=$engineDrops snapshotSource=${safeLabel(source)}")
            engineList.forEach { engine ->
                val s = engine.state; val c = engine.counts
                appendLine("engine=${s.scope} serviceType=${s.serviceType} preview=${known(s.isPreview)} alive=${known(s.alive)} " +
                    "touchRequested=${known(s.touchRequested)} visible=${known(s.visible)} surfaceValid=${known(s.surfaceValid)} " +
                    "width=${known(s.width)} height=${known(s.height)} rendererReady=${known(s.rendererReady)} editHold=${known(s.editHold)} " +
                    "received=${c.received} forwarded=${c.forwarded} ignored=${c.ignored} ignoredEditHold=${c.ignoredEditHold} ignoredOther=${c.ignoredOther} " +
                    "unresolvedRouting=${c.received - c.forwarded - c.ignored} " +
                    "commands=${c.commands} tap=${c.tapCommands} secondaryTap=${c.secondaryTapCommands} otherCommands=${c.otherCommands} " +
                    "sampleWallMs=${engine.sampledAt.wallMs} sampleMonoMs=${engine.sampledAt.monoMs} sampleUptimeMs=${engine.sampledAt.uptimeMs} sampleSource=${engine.sampleSource}")
                appendLine("engine=${s.scope} ownerCacheWallMs=${known(engine.ownerSampledAt?.wallMs)} " +
                    "ownerCacheMonoMs=${known(engine.ownerSampledAt?.monoMs)} ownerCacheUptimeMs=${known(engine.ownerSampledAt?.uptimeMs)} " +
                    "metadataSampleIsNotOwnerRefresh=true")
            }
            gestures.forEach { (key, count) -> appendLine("$key = $count") }
        }
        val content = buildString {
            append(summary)
            if (restored > 0) {
                appendLine("\nRESTORED_HISTORY_BEGIN runId=${restoredRun ?: "UNKNOWN"} counters=UNKNOWN notCurrentObservation=true")
                ring.filter { it.restored }.forEach { appendLine(it.text) }
                appendLine("RESTORED_HISTORY_END")
            }
            appendLine("\nCURRENT_RUN_EVENTS diagnosticRunId=${run.runId}")
            ring.filterNot { it.restored }.forEach { appendLine(it.text) }
        }
        return DiagnosticSnapshot(run, processSession, startedAt, at.wallMs, collecting, revision,
            summary, content,
            "WallpaperP0-${run.runId}-${at.wallMs}.txt", status, engineList, eventList, overwritten, restored)
    }

    private fun baseline(source: String) { registry.values.forEach { recordSample(it, source, true) } }
    private fun recordSample(state: EngineDiagnosticState, source: String, emit: Boolean,
        ownerAt: DiagnosticClockValue? = ownerTimes[state.scope]) {
        val at = clock()
        val counters = engineCounts(state.scope) ?: return
        samples[state.scope] = DiagnosticEngineSnapshot(state, counters, at, source, ownerSampledAt = ownerAt)
        if (emit) append(state.scope, "ENGINE_STATE", "sampleSource=$source ownerSource=${state.source} " +
            "ownerCacheWallMs=${known(ownerAt?.wallMs)} ownerCacheMonoMs=${known(ownerAt?.monoMs)} " +
            "alive=${known(state.alive)} touchRequested=${known(state.touchRequested)} visible=${known(state.visible)} " +
            "surfaceValid=${known(state.surfaceValid)} width=${known(state.width)} height=${known(state.height)} " +
            "rendererReady=${known(state.rendererReady)} editHold=${known(state.editHold)} preview=${known(state.isPreview)} serviceType=${state.serviceType}")
    }
    private fun engineCounts(scope: String): DiagnosticEngineCounts? {
        counts[scope]?.let { return it }
        if (counts.size >= maxEngines) { engineDrops++; return null }
        return DiagnosticEngineCounts().also { counts[scope] = it }
    }
    private fun append(scope: String, kind: String, detail: String) {
        val at = clock()
        val state = registry[scope] ?: samples[scope]?.state
        val line = "wallMs=${at.wallMs} monoMs=${at.monoMs} uptimeMs=${at.uptimeMs} " +
            "processSession=$processSession diagnosticRunId=${run.runId} scope=${safeLabel(scope)} event=${safeLabel(kind)} " +
            "serviceType=${state?.serviceType ?: "APP_OR_UNKNOWN"} preview=${known(state?.isPreview)} " +
            detail.replace('\n', ' ').replace('\r', ' ')
        ring.addLast(Record(line.take(maxLine), false))
        while (ring.size > maxEvents) { ring.removeFirst(); overwritten++ }
        revision++
    }
    private fun boundedState(s: EngineDiagnosticState) = s.copy(scope = safeLabel(s.scope),
        serviceType = safeLabel(s.serviceType), source = safeLabel(s.source),
        width = s.width?.takeIf { it >= 0 }, height = s.height?.takeIf { it >= 0 })
    private fun safeLabel(text: String): String = text.replace('\n', '_').replace('\r', '_').take(96)
    private fun known(value: Any?): String = value?.toString() ?: "UNKNOWN"
    private fun coordinate(value: Float): String = if (value.isFinite()) value.toInt().toString() else "UNKNOWN"
}
