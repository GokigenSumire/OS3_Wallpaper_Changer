package dev.abdus.apps.shimmer.database

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p4.PlaybackRule
import dev.abdus.apps.shimmer.p4.ScheduleSettings
import dev.abdus.apps.shimmer.p4.ScheduleStore
import kotlinx.coroutines.CancellationException
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

/** 实际Repo→实时规则→强事务读者→纯映射；并非目标机速度证明。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P1PlaybackMappingMemoIntegrationTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val database = ShimmerDatabase.getInstance(application)
    private val dao = database.p1SourceDao()
    private val preferences = WallpaperPreferences.create(application)
    private val schedule = ScheduleStore(application)
    private val initializationScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val repository = ImageFolderRepository(application, initializationScope)

    @After fun closeDatabase() {
        initializationScope.cancel()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
    }

    @Test fun repeatedRealRepositoryActionsReuseOnlyTheVerifiedProjectionMapping() = runBlocking {
        seed("a", "one", "two")
        val first = repository.getPlaybackItems()
        repeat(5) { assertSame(first, repository.getPlaybackItems()) }
        assertEquals(dao.getEnabledPlaybackImages().map { it.uri }, first.map { it.id })
    }

    @Test fun sourceEnableAndRawMetadataWritesAreVisibleWithoutListeners() = runBlocking {
        seed("a", "one"); seed("b", "two")
        val first = repository.getPlaybackItems()
        dao.setSourceEnabled("a", false, null)
        assertEquals(listOf(uri("two")), repository.getPlaybackItems().map { it.id })
        dao.setSourceEnabled("a", true, null)
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = '新图.png', " +
            "relativePath = 'new/目录.png' WHERE id = 'one'")
        val changed = repository.getPlaybackItems()
        assertNotSame(first, changed)
        assertEquals("新图.png", changed.first().displayName)
        assertEquals("new/目录.png", changed.first().relativePath)
        assertSame(changed, repository.getPlaybackItems())
    }

    @Test fun actualDisabledPreferencesIncludingDirectSameSizeChangesAreAlwaysRead() = runBlocking {
        seed("a", "one", "two", "three")
        val raw = application.getSharedPreferences("shimmer_wallpaper_prefs", Context.MODE_PRIVATE)
        raw.edit().putStringSet(WallpaperPreferences.KEY_DISABLED_IMAGES, setOf(uri("one"))).commit()
        val first = repository.getPlaybackItems()
        raw.edit().putStringSet(WallpaperPreferences.KEY_DISABLED_IMAGES, setOf(uri("two"))).commit()
        val second = repository.getPlaybackItems()
        assertEquals(listOf(uri("two"), uri("three")), first.map { it.id })
        assertEquals(listOf(uri("one"), uri("three")), second.map { it.id })
        assertNotSame(first, second)
        preferences.setImageEnabled(uri("two"), true)
        assertEquals(3, repository.getPlaybackItems().size)
    }

    @Test fun currentRulesAreResolvedEvenWhenTheirProjectionAndMappedItemsCanBeShared() = runBlocking {
        seed("a", "one"); seed("b", "two")
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("first", sourceIds = listOf("a")))))
        val first = repository.getPlaybackCatalog()
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("second", sourceIds = listOf("a")))))
        val sameImages = repository.getPlaybackCatalog()
        assertSame(first.items, sameImages.items)
        assertEquals("second", sameImages.activeRule?.id)
        schedule.saveSettings(ScheduleSettings(rules = listOf(PlaybackRule("other", sourceIds = listOf("b")))))
        val changed = repository.getPlaybackCatalog()
        assertEquals("other", changed.activeRule?.id)
        assertEquals(listOf(uri("two")), changed.items.map { it.id })
    }

    @Test fun droppedGuardForcesFreshRowsAndNeverHitsOldMapping() = runBlocking {
        seed("a", "one", "two")
        val first = repository.getPlaybackItems()
        val (name, sql) = imageUpdateGuard()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'unguarded.png' WHERE id = 'one'")
        val fallback = repository.getPlaybackItems()
        assertEquals("unguarded.png", fallback.first().displayName)
        assertNotSame(first, fallback)
        assertNotSame(fallback, repository.getPlaybackItems())
        database.openHelper.writableDatabase.execSQL(sql)
        val restored = repository.getPlaybackItems()
        assertEquals("unguarded.png", restored.first().displayName)
        assertSame(restored, repository.getPlaybackItems())
    }

    @Test fun repairedEpochAndNewImageNeverReuseThePreRepairMapping() = runBlocking {
        seed("a", "one")
        val first = repository.getPlaybackItems()
        val (name, _) = imageUpdateGuard()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'new.png' WHERE id = 'one'")
        P1CatalogRevision.install(database.openHelper.writableDatabase)
        val changed = repository.getPlaybackItems()
        assertNotSame(first, changed)
        assertEquals("new.png", changed.single().displayName)
        assertSame(changed, repository.getPlaybackItems())
    }

    @Test fun nestedCrossDispatcherRollbackCannotPublishProvisionalMappingAsLaterAuthority() = runBlocking {
        seed("a", "one")
        val original = repository.getPlaybackItems()
        try {
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'temporary.png' WHERE id = 'one'")
                val provisional = withContext(Dispatchers.Default) { repository.getPlaybackItems() }
                assertEquals("temporary.png", provisional.single().displayName)
                assertNotSame(original, provisional)
                throw Rollback()
            }
        } catch (_: Rollback) { }
        val restored = repository.getPlaybackItems()
        assertEquals("one.png", restored.single().displayName)
        assertSame(restored, repository.getPlaybackItems())
        database.openHelper.writableDatabase.execSQL("UPDATE image_records SET displayName = 'committed.png' WHERE id = 'one'")
        assertEquals("committed.png", repository.getPlaybackItems().single().displayName)
    }

    @Test fun legacyCatalogStillReadsRealRecordsOnEveryCall() = runBlocking {
        val legacy = database.imageDao()
        val folder = legacy.insertFolder(FolderEntity(uri = Uri.parse("file:///legacy"), isEnabled = true, createdAt = "created"))
        legacy.insertImages(listOf(ImageEntity(folderId = folder, uri = Uri.parse("file:///legacy/a.png"), createdAt = "created")))
        val first = repository.getPlaybackItems()
        assertNotSame(first, repository.getPlaybackItems())
        legacy.insertImages(listOf(ImageEntity(folderId = folder, uri = Uri.parse("file:///legacy/b.png"), createdAt = "created")))
        assertEquals(2, repository.getPlaybackItems().size)
        preferences.setImageEnabled("file:///legacy/a.png", false)
        assertEquals(listOf("file:///legacy/b.png"), repository.getPlaybackItems().map { it.id })
    }

    @Test fun cancelledCallerCannotReplaceCompleteMemoWithPartialRows() = runBlocking {
        seed("a", "one", "two")
        val first = repository.getPlaybackItems()
        try {
            withContext(Job().apply { cancel() }) { repository.getPlaybackItems() }
            fail("已取消调用不能读取并发布")
        } catch (_: CancellationException) { }
        assertSame(first, repository.getPlaybackItems())
    }

    @Test fun elevenThousandActualRowsRetainOrderAndMetadataWhileWarmMappingIsShared() = runBlocking {
        seed("a", *(1..11_890).map { "image-$it" }.toTypedArray())
        val first = repository.getPlaybackItems()
        repeat(5) { assertSame(first, repository.getPlaybackItems()) }
        val baseline = dao.getEnabledPlaybackImages()
        assertEquals(11_890, first.size)
        assertEquals(baseline.map { it.uri }, first.map { it.id })
        assertEquals(baseline.map { it.displayName }, first.map { it.displayName })
        assertEquals(baseline.map { it.relativePath }, first.map { it.relativePath })
    }

    private suspend fun seed(source: String, vararg ids: String) {
        dao.registerSource(P1SourceEntity(source, "content://roots/$source", "DIRECTORY_TREE"))
        dao.commitScan(source, ids.map { P1ImageRecordEntity(it, uri(it), "$it.png", "$it.png") },
            "APP_OWNED", "2026-10-12T00:00:00Z")
    }
    private fun uri(id: String) = "content://images/$id"
    private fun imageUpdateGuard() = P1CatalogRevision.triggerDefinitions.entries.single {
        it.key.contains("image_records") && it.key.endsWith("update")
    }.let { it.key to it.value }
    private class Rollback : RuntimeException()
}
