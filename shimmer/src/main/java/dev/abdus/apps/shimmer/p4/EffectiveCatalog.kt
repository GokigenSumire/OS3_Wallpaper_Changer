package dev.abdus.apps.shimmer.p4

import dev.abdus.apps.shimmer.database.P1PlaybackImage
import dev.abdus.apps.shimmer.database.P1SourceDao
import dev.abdus.apps.shimmer.database.P1PlaybackCursorReader
import dev.abdus.apps.shimmer.database.P1RevisionCatalogReader
import dev.abdus.apps.shimmer.p2.PlaybackItem
import java.time.Instant
import java.time.ZoneId

/** The catalog playback uses now, and the rule that supplied it (null: the enabled catalog). */
data class PlaybackCatalog(val items: List<PlaybackItem>, val activeRule: PlaybackRule?)

object EffectiveCatalog {
    data class Resolved(val images: List<P1PlaybackImage>, val rule: PlaybackRule?)

    /**
     * P4 event table 6: the first matching rule whose sources hold playable images replaces the
     * enabled catalog. A rule may target a source disabled in P1; directory exclusions still
     * apply. Pause-all does not change the result.
     */
    suspend fun resolve(dao: P1SourceDao, settings: ScheduleSettings, instant: Instant, zone: ZoneId): Resolved {
        for (rule in ScheduleRules.activeRules(settings, instant, zone)) {
            val images = dao.getRulePlaybackImages(rule.sourceIds)
            if (images.isNotEmpty()) return Resolved(images, rule)
        }
        return Resolved(dao.getEnabledPlaybackImages(), null)
    }

    /** Production cursor path: same first nonempty rule and enabled fallback as the DAO baseline. */
    suspend fun resolve(reader: P1PlaybackCursorReader, settings: ScheduleSettings, instant: Instant, zone: ZoneId): Resolved {
        for (rule in ScheduleRules.activeRules(settings, instant, zone)) {
            val images = reader.getRulePlaybackImages(rule.sourceIds)
            if (images.isNotEmpty()) return Resolved(images, rule)
        }
        return Resolved(reader.getEnabledPlaybackImages(), null)
    }

    /** Exact transactional revision reader; rule matching is still evaluated using the current clock. */
    suspend fun resolve(reader: P1RevisionCatalogReader, settings: ScheduleSettings, instant: Instant, zone: ZoneId): Resolved {
        for (rule in ScheduleRules.activeRules(settings, instant, zone)) {
            val images = reader.getRulePlaybackImages(rule.sourceIds)
            if (images.isNotEmpty()) return Resolved(images, rule)
        }
        return Resolved(reader.getEnabledPlaybackImages(), null)
    }
}
