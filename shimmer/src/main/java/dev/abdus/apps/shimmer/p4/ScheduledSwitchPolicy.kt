package dev.abdus.apps.shimmer.p4

/** Decides, before loading, whether a scheduled switch should change the image at all. */
object ScheduledSwitchPolicy {
    /**
     * Null when the Engine should advance. [catalogIds] is the effective catalog the switch will
     * use, [displayedId] the committed image on screen (null for the default image). An empty
     * catalog is left to the normal no-candidate path.
     */
    fun decide(dispatch: ScheduleDispatch, catalogIds: Collection<String>, displayedId: String?): SwitchOutcome? {
        if (displayedId == null || catalogIds.isEmpty()) return null
        val displayedInCatalog = displayedId in catalogIds
        return when {
            // Rule change only: keep the image while it still belongs to the new catalog.
            dispatch.conditional && displayedInCatalog -> SwitchOutcome.RULE_KEEP
            // "Fixed single image": the only playable image is already showing.
            catalogIds.size == 1 && displayedInCatalog -> SwitchOutcome.SKIP_SINGLE
            else -> null
        }
    }
}
