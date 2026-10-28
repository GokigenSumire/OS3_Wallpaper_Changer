package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p2.CertifiedPlaybackItems
import dev.abdus.apps.shimmer.p2.CoordinatorPreparationWork
import dev.abdus.apps.shimmer.p2.PlaybackCoordinator
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.forPersistence
import dev.abdus.apps.shimmer.p4.PlaybackRule
import dev.abdus.apps.shimmer.p4.ScheduleSettings
import dev.abdus.apps.shimmer.p4.ScheduleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room→revision/clock/disabled→mapper→Coordinator path; no decode/GPU/phone claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1StableCoordinatorIntegrationTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(application)
    private val dao = database.p1SourceDao()
    private val preferences = WallpaperPreferences.create(application)
    private val schedule = ScheduleStore(application)
    private val stoppedScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val repository = ImageFolderRepository(application, stoppedScope)
    private val coordinator = PlaybackCoordinator(63)

    @After fun closeDatabase() {
        stoppedScope.cancel()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }
    @Test fun realRepositoryProducesCertifiedCatalogAndFourLargeStableModesVisitNoItems() = runBlocking {
        seed("root", *(1..11890).map { "image-$it" }.toTypedArray())
        val first = repository.getPlaybackItems()
        assertTrue(first is CertifiedPlaybackItems)
        assertEquals(11890, first.size)
        for (mode in PlaybackOrder.entries) {
            val own = PlaybackCoordinator(17)
            assertEquals(11890L, own.setItemsAndRestore(first, PlaybackSnapshot(order = mode)).catalogItemsVisited)
            play(own)
            repeat(4) {
                val currentRead = repository.getPlaybackItems()
                assertSame(first, currentRead)
                assertEquals(CoordinatorPreparationWork(), own.setItemsAndRestore(currentRead, own.snapshot().forPersistence()))
                play(own)
            }
        }
        assertEquals(dao.getEnabledPlaybackImages().map { it.uri }, first.map { it.id })
    }
    @Test fun sameCountSourceReplacementAndRawMetadataChangesForceRealValidation() = runBlocking {
        seed("a", "one"); seed("b", "two")
        dao.setSourceEnabled("b", false, null)
        val first = repository.getPlaybackItems()
        coordinator.setItems(first)
        dao.setSourceEnabled("a", false, null)
        dao.setSourceEnabled("b", true, null)
        val swapped = repository.getPlaybackItems()
        assertEquals(first.size, swapped.size)
        assertEquals(1, coordinator.setItemsAndRestore(swapped, coordinator.snapshot().forPersistence()).fullRestoreCount)
        assertEquals(uri("two"), coordinator.catalogItems().single().id)
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'changed.png' WHERE id = 'two'")
        val renamed = repository.getPlaybackItems()
        assertEquals(1, coordinator.setItemsAndRestore(renamed, coordinator.snapshot().forPersistence()).fullRestoreCount)
        assertEquals("changed.png", coordinator.catalogItems().single().displayName)
    }
    @Test fun directSameSizeDisabledPreferenceChangesCannotReuseThePreviousCertificate() = runBlocking {
        seed("root", "Aa", "BB", "third")
        val raw = application.getSharedPreferences("shimmer_wallpaper_prefs", Context.MODE_PRIVATE)
        raw.edit().putStringSet(WallpaperPreferences.KEY_DISABLED_IMAGES, setOf(uri("Aa"))).commit()
        val first = repository.getPlaybackItems()
        coordinator.setItems(first)
        raw.edit().putStringSet(WallpaperPreferences.KEY_DISABLED_IMAGES, setOf(uri("BB"))).commit()
        val changed = repository.getPlaybackItems()
        assertNotSame(first, changed)
        assertEquals(1, coordinator.setItemsAndRestore(changed, coordinator.snapshot().forPersistence()).fullRestoreCount)
        assertEquals(listOf(uri("Aa"), uri("third")), coordinator.catalogItems().map { it.id })
        preferences.setImageEnabled(uri("BB"), true)
        assertEquals(3, repository.getPlaybackItems().size)
    }
    @Test fun rulesAreReadAgainEvenWhenMetadataCanSafelyUseTheSameCertificate() = runBlocking {
        seed("a", "one"); seed("b", "two")
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("first", sourceIds = listOf("a")))))
        val first = repository.getPlaybackCatalog()
        coordinator.setItems(first.items)
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("second", sourceIds = listOf("a")))))
        val sameImages = repository.getPlaybackCatalog()
        assertEquals("second", sameImages.activeRule!!.id)
        assertSame(first.items, sameImages.items)
        assertEquals(CoordinatorPreparationWork(), coordinator.setItemsAndRestore(sameImages.items, coordinator.snapshot().forPersistence()))
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("other", sourceIds = listOf("b")))))
        val changed = repository.getPlaybackCatalog()
        assertEquals("other", changed.activeRule!!.id)
        assertEquals(1, coordinator.setItemsAndRestore(changed.items, coordinator.snapshot().forPersistence()).fullRestoreCount)
        assertEquals(uri("two"), coordinator.catalogItems().single().id)
    }
    @Test fun missingRevisionGuardForcesFreshCertificatesAndCompleteMetadataComparison() = runBlocking {
        seed("a", "one", "two")
        coordinator.setItems(repository.getPlaybackItems())
        val name = P1CatalogRevision.triggerDefinitions.keys.single { it.contains("image_records") && it.endsWith("update") }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
        val firstFallback = repository.getPlaybackItems()
        val work = coordinator.setItemsAndRestore(firstFallback, coordinator.snapshot().forPersistence())
        assertEquals(2L, work.catalogItemsVisited)
        assertEquals(0, work.fullRestoreCount)
        val secondFallback = repository.getPlaybackItems()
        assertNotSame(firstFallback, secondFallback)
        assertEquals(2L, coordinator.setItemsAndRestore(secondFallback, coordinator.snapshot().forPersistence()).catalogItemsVisited)
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'unguarded.png' WHERE id = 'one'")
        val changed = repository.getPlaybackItems()
        assertEquals(1, coordinator.setItemsAndRestore(changed, coordinator.snapshot().forPersistence()).fullRestoreCount)
        assertEquals("unguarded.png", coordinator.catalogItems().first().displayName)
    }
    @Test fun rolledBackProvisionalCatalogNeverRemainsAcceptedAsTheActualCatalog() = runBlocking {
        seed("a", "one")
        val original = repository.getPlaybackItems()
        coordinator.setItems(original)
        try {
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'temporary.png' WHERE id = 'one'")
                val temporary = withContext(Dispatchers.Default) { repository.getPlaybackItems() }
                assertEquals(1, coordinator.setItemsAndRestore(temporary, coordinator.snapshot().forPersistence()).fullRestoreCount)
                assertEquals("temporary.png", coordinator.catalogItems().single().displayName)
                throw Rollback()
            }
        } catch (_: Rollback) { }
        val authoritative = repository.getPlaybackItems()
        assertEquals(1, coordinator.setItemsAndRestore(authoritative, coordinator.snapshot().forPersistence()).fullRestoreCount)
        assertEquals("one.png", coordinator.catalogItems().single().displayName)
        assertEquals(CoordinatorPreparationWork(), coordinator.setItemsAndRestore(repository.getPlaybackItems(), coordinator.snapshot().forPersistence()))
    }
    @Test fun actualLegacyDatabaseCatalogRemainsOrdinaryAndReadsCompleteContentsEveryTime() = runBlocking {
        val legacy = database.imageDao()
        val folder = legacy.insertFolder(FolderEntity(uri = Uri.parse("file:///legacy"), isEnabled = true, createdAt = "created"))
        legacy.insertImages(listOf(ImageEntity(folderId = folder, uri = Uri.parse("file:///legacy/a.png"), createdAt = "created")))
        val first = repository.getPlaybackItems()
        assertFalse(first is CertifiedPlaybackItems)
        coordinator.setItems(first)
        val again = repository.getPlaybackItems()
        assertNotSame(first, again)
        assertEquals(1L, coordinator.setItemsAndRestore(again, coordinator.snapshot().forPersistence()).catalogItemsVisited)
        legacy.insertImages(listOf(ImageEntity(folderId = folder, uri = Uri.parse("file:///legacy/b.png"), createdAt = "created")))
        assertEquals(1, coordinator.setItemsAndRestore(repository.getPlaybackItems(), coordinator.snapshot().forPersistence()).fullRestoreCount)
        assertEquals(2, coordinator.catalogItems().size)
    }
    private suspend fun seed(source: String, vararg ids: String) {
        dao.registerSource(P1SourceEntity(source, "content://roots/$source", "DIRECTORY_TREE"))
        dao.commitScan(source, ids.map { P1ImageRecordEntity(it, uri(it), "$it.png", "$it.png") }, "APP_OWNED", "2026-10-12T00:00:00Z")
    }
    private fun play(own: PlaybackCoordinator) { val request = own.requestNext(); assertNotNull(request.item); assertTrue(own.commit(request.version)) }
    private fun uri(id: String) = "content://images/$id"
    private class Rollback : RuntimeException()
}
