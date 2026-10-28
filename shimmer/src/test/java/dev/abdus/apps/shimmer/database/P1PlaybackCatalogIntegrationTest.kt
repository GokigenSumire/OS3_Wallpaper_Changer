package dev.abdus.apps.shimmer.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p2.PlaybackCatalogTiming
import dev.abdus.apps.shimmer.p2.PlaybackCatalogMeasurement
import dev.abdus.apps.shimmer.p4.PlaybackRule
import dev.abdus.apps.shimmer.p4.ScheduleSettings
import dev.abdus.apps.shimmer.p4.ScheduleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real Repository -> EffectiveCatalog -> compact transactional Room projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1PlaybackCatalogIntegrationTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(application)
    private val dao = database.p1SourceDao()
    private val preferences = WallpaperPreferences.create(application)
    private val schedule = ScheduleStore(application)
    // The repository calls below use their caller's coroutine. Its unrelated startup sync is not
    // part of this fixture, so its launch scope is already cancelled rather than silently mocked.
    private val initializationScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val repository = ImageFolderRepository(application, initializationScope)

    @After
    fun closeDatabase() {
        initializationScope.cancel()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun noP1SourcesRetainsLegacyBranchAndDisabledUriFiltering() = runBlocking {
        val legacy = database.imageDao()
        val folder = legacy.insertFolder(FolderEntity(uri = android.net.Uri.parse("file:///legacy"),
            isEnabled = true, createdAt = "created"))
        legacy.insertImages(listOf(
            ImageEntity(folderId = folder, uri = android.net.Uri.parse("file:///legacy/one.png"), createdAt = "created"),
            ImageEntity(folderId = folder, uri = android.net.Uri.parse("file:///legacy/two.png"), createdAt = "created"),
        ))
        preferences.setImageEnabled("file:///legacy/two.png", false)
        val catalog = repository.getPlaybackCatalog()
        assertNull(catalog.activeRule)
        assertEquals(listOf("file:///legacy/one.png"), catalog.items.map { it.id })
        assertEquals(folder.toString(), catalog.items.single().sourceId)
    }

    @Test
    fun globalDisabledImagesAreFilteredAfterTheFirstNonemptyRuleIsChosen() = runBlocking {
        seed("enabled", "fallback")
        seed("rule-source", "rule-image")
        dao.setSourceEnabled("rule-source", false, null)
        preferences.setImageEnabled(uri("rule-image"), false)
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("winner", sourceIds = listOf("rule-source")))))

        val catalog = repository.getPlaybackCatalog()
        assertEquals("winner", catalog.activeRule?.id)
        assertTrue(catalog.items.isEmpty())
        assertEquals(2, dao.getAllImages().size)
    }

    @Test
    fun aRuleWhoseDatabaseCatalogIsEmptyStillFallsBackToEnabledImages() = runBlocking {
        seed("enabled", "fallback")
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("empty", sourceIds = listOf("missing")))))
        val catalog = repository.getPlaybackCatalog()
        assertNull(catalog.activeRule)
        assertEquals(listOf(uri("fallback")), catalog.items.map { it.id })
    }

    @Test
    fun eachCallSeesDisabledPreferenceAndRuleChangesWithoutAListenerOrCacheWait() = runBlocking {
        seed("a", "one", "two")
        seed("b", "three")
        assertEquals(listOf(uri("one"), uri("two"), uri("three")), repository.getPlaybackItems().map { it.id })
        preferences.setImageEnabled(uri("two"), false)
        assertEquals(listOf(uri("one"), uri("three")), repository.getPlaybackItems().map { it.id })
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("changed", sourceIds = listOf("b")))))
        val ruleCatalog = repository.getPlaybackCatalog()
        assertEquals("changed", ruleCatalog.activeRule?.id)
        assertEquals(listOf(uri("three")), ruleCatalog.items.map { it.id })
        preferences.setImageEnabled(uri("two"), true)
        schedule.saveSettings(ScheduleSettings())
        assertEquals(listOf(uri("one"), uri("two"), uri("three")), repository.getPlaybackItems().map { it.id })
    }

    @Test
    fun playbackItemsKeepTheCanonicalMetadataReferencesProducedByTheCompactProjection() = runBlocking {
        seed("source", "one", "two", "three")
        val items = repository.getPlaybackItems()
        assertEquals(3, items.size)
        for (item in items) {
            assertSame(items.first().sourceId, item.sourceId)
            assertSame(items.first().sourceKind, item.sourceKind)
            assertSame(items.first().sourceRootUri, item.sourceRootUri)
        }
    }

    @Test fun nestedRepositoryDiagnosticsPreserveRealP1CatalogAndRuleAndDisabledDecisions() = runBlocking {
        seed("a", "one", "two")
        seed("b", "three")
        dao.setSourceEnabled("b", false, null)
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("actual-rule", sourceIds = listOf("b")))))
        val baseline = repository.getPlaybackCatalog()
        var now = 0L
        val timing = PlaybackCatalogTiming { now += 1_000_000; now }
        val measured = repository.getPlaybackCatalog(timing)
        assertEquals(baseline, measured)
        assertEquals("actual-rule", measured.activeRule?.id)
        assertEquals(listOf(uri("three")), measured.items.map { it.id })
        assertEquals(PlaybackCatalogMeasurement(1, 1, 1), timing.finish())
        preferences.setImageEnabled(uri("three"), false)
        assertTrue(repository.getPlaybackCatalog(PlaybackCatalogTiming()).items.isEmpty())
        assertEquals(3, dao.getAllImages().size)
    }

    @Test fun nestedRepositoryDiagnosticsPreserveLegacyBranchWithoutP1Sources() = runBlocking {
        val legacy = database.imageDao()
        val folder = legacy.insertFolder(FolderEntity(uri = android.net.Uri.parse("file:///legacy"),
            isEnabled = true, createdAt = "created"))
        legacy.insertImages(listOf(ImageEntity(folderId = folder,
            uri = android.net.Uri.parse("file:///legacy/one.png"), createdAt = "created")))
        var now = 0L
        val timing = PlaybackCatalogTiming { now += 1_000_000; now }
        val measured = repository.getPlaybackCatalog(timing)
        assertEquals(listOf("file:///legacy/one.png"), measured.items.map { it.id })
        assertNull(measured.activeRule)
        assertEquals(PlaybackCatalogMeasurement(1, 1, 1), timing.finish())
        assertFalse(dao.hasSources())
    }

    private suspend fun seed(source: String, vararg ids: String) {
        dao.registerSource(P1SourceEntity(source, "content://roots/$source", "DIRECTORY_TREE"))
        dao.commitScan(source, ids.map { P1ImageRecordEntity(it, uri(it), "$it.png", "$it.png") },
            "APP_OWNED", "2026-10-10T00:00:00Z")
    }

    private fun uri(id: String) = "content://images/$id"
}
