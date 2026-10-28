package dev.abdus.apps.shimmer.p4

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** P4 event table 6: which catalog playback uses while rules match. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EffectiveCatalogTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ShimmerDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.p1SourceDao()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val noon = Instant.parse("2026-10-02T04:00:00Z")

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun noMatchingRuleUsesTheEnabledCatalog() = runBlocking {
        seed("a", "a1", "a2")
        seed("b", "b1")
        dao.setSourceEnabled("b", false, null)
        val evening = rule("r1", listOf("b"), windowStart = 18 * 60, windowEnd = 23 * 60)

        val resolved = EffectiveCatalog.resolve(dao, ScheduleSettings(rules = listOf(evening)), noon, zone)
        assertNull(resolved.rule)
        assertEquals(listOf("a:a1", "a:a2"), resolved.images.map { it.id })
    }

    @Test
    fun matchingRuleUsesItsSourcesEvenWhenDisabledInP1AndKeepsFolderExclusions() = runBlocking {
        seed("a", "a1")
        seed("b", "keep/b1", "stop/b2", "b3")
        dao.setSourceEnabled("b", false, null)
        dao.setDirectoryExcluded("b", "stop", true, null)
        val allDay = rule("r1", listOf("b"))

        val resolved = EffectiveCatalog.resolve(dao, ScheduleSettings(rules = listOf(allDay)), noon, zone)
        assertEquals("r1", resolved.rule?.id)
        assertEquals(listOf("b:keep/b1", "b:b3"), resolved.images.map { it.id })
    }

    @Test
    fun firstMatchingRuleWithImagesWinsInListOrder() = runBlocking {
        seed("a", "a1")
        seed("b", "b1")
        seed("c", "c1")
        val deleted = rule("gone", listOf("deleted-source"))
        val second = rule("second", listOf("c", "b"))
        val third = rule("third", listOf("a"))

        val settings = ScheduleSettings(rules = listOf(deleted, second, third))
        val resolved = EffectiveCatalog.resolve(dao, settings, noon, zone)
        // A rule whose sources hold no images is skipped; source order comes from P1, not the rule.
        assertEquals("second", resolved.rule?.id)
        assertEquals(listOf("b:b1", "c:c1"), resolved.images.map { it.id })

        val paused = EffectiveCatalog.resolve(dao, settings.copy(pauseAll = true), noon, zone)
        assertEquals("second", paused.rule?.id)
    }

    @Test
    fun matchingRulesWithoutImagesFallBackToTheEnabledCatalog() = runBlocking {
        seed("a", "a1")
        val resolved = EffectiveCatalog.resolve(dao, ScheduleSettings(rules = listOf(rule("r1", listOf("missing")))), noon, zone)
        assertNull(resolved.rule)
        assertEquals(listOf("a:a1"), resolved.images.map { it.id })
    }

    private fun rule(id: String, sources: List<String>, windowStart: Int? = null, windowEnd: Int? = null) =
        PlaybackRule(id, sourceIds = sources, windowStart = windowStart, windowEnd = windowEnd)

    private suspend fun seed(source: String, vararg paths: String) {
        dao.registerSource(P1SourceEntity(source, "file:///$source", "DIRECTORY_TREE"))
        dao.commitScan(source, paths.map { path ->
            P1ImageRecordEntity(
                id = "$source:$path",
                uri = "file:///$source/$path",
                displayName = path.substringAfterLast('/'),
                relativePath = "$path.png",
            )
        }, "APP_OWNED", "2026-09-27T00:00:00Z")
    }
}
