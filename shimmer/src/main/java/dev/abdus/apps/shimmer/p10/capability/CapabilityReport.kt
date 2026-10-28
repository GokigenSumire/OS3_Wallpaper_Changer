package dev.abdus.apps.shimmer.p10.capability

/** Explicit fixed-field diagnostic export; no framework object toString, URI or coordinates. */
object CapabilityReport {
    fun format(snapshot: CapabilitySnapshot, identity: String): String = buildString {
        appendLine("Hyper Wallpaper Changer / P10-00")
        appendLine("identity=$identity")
        appendLine("scope=DISPLAY_AND_INPUT_CAPABILITY_ONLY")
        appendLine("productionLockPlayback=NOT_EXERCISED_BY_THIS_PROBE")
        appendLine("rootLockInput=NOT_EXERCISED_BY_THIS_PROBE")
        appendLine("deviceAcceptance=NOT_RUN")
        appendLine("compatibilityBaseline=USER_CONFIRMATION_REQUIRED")
        appendLine("markerIsNotActualTarget=true sharedFlagsDoNotProveIndependence=true")
        appendLine("session=${snapshot.session} revision=${snapshot.revision}")
        appendLine("engineRows=${snapshot.engines.size} events=${snapshot.events.size} droppedEvents=${snapshot.droppedEvents} rejectedEngineSamples=${snapshot.rejectedEngineSamples}")
        snapshot.engines.forEach { row ->
            appendLine("ENGINE id=${row.id} marker=${row.markerTarget} flags=${row.route.observedFlags ?: "UNKNOWN"} exposure=${row.route.exposure} preview=${row.route.isPreview}")
            appendLine("alive=${row.alive} visible=${row.visible} surfaceReady=${row.surfaceReady} width=${row.width} height=${row.height} frames=${row.framesDrawn} failures=${row.failureCount}")
            appendLine("touchRequested=${row.touchRequested} raw=${row.rawTouchEvents} down=${row.downEvents} up=${row.upEvents} pointerDown=${row.pointerDownEvents} pointerUp=${row.pointerUpEvents} move=${row.moveEvents} cancel=${row.cancelEvents} maxPointers=${row.maxPointerCount}")
            appendLine("double=${row.doubleTaps} triple=${row.tripleTaps} twoFingerDouble=${row.twoFingerDoubleTaps} surfacesCreated=${row.surfaceCreates} surfacesDestroyed=${row.surfaceDestroys} flagsChanged=${row.flagChanges}")
            appendLine("createdElapsedMs=${row.createdAtMs} lastElapsedMs=${row.lastObservedAtMs}")
        }
        snapshot.events.forEach { event ->
            appendLine("EVENT sequence=${event.sequence} elapsedMs=${event.elapsedMs} engine=${event.engineId} kind=${event.kind} ${event.detail}")
        }
    }
}
