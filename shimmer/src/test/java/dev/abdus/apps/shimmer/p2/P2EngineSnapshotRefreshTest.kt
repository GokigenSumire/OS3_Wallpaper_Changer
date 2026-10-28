package dev.abdus.apps.shimmer.p2

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.service.wallpaper.WallpaperService
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageSet
import dev.abdus.apps.shimmer.ShimmerWallpaperService
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p3.EditHoldGate
import dev.abdus.apps.shimmer.p3.HeldAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 运行真实 Engine 的目录/快照刷新路径，在解码开始前观察是否正确安排加载。
 * 不创建 Surface 或 EGL，不将此测试当成图片呈现或目标机生命周期验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [P2EngineSnapshotRefreshTest.FormalEngineShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class P2EngineSnapshotRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = ShimmerDatabase.getInstance(context)
    private val store = PlaybackSnapshotStore(context)
    private val dispatcher = StepDispatcher()
    private val testScope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var service: ServiceController<ShimmerWallpaperService>
    private lateinit var engine: ShimmerWallpaperService.ShimmerWallpaperEngine

    @Before
    fun createEngineWithoutGlSurface() {
        runBlocking {
            val dao = database.p1SourceDao()
            dao.registerSource(P1SourceEntity("source", "file:///source", "DIRECTORY_TREE"))
            dao.commitScan("source", listOf("one", "two", "three").map { name ->
                P1ImageRecordEntity(name, uri(name), "$name.png", "$name.png")
            }, "APP_OWNED", "2026-09-25T00:00:00Z")
        }
        service = Robolectric.buildService(ShimmerWallpaperService::class.java).create()
        engine = service.get().onCreateEngine() as ShimmerWallpaperService.ShimmerWallpaperEngine

        // 构造器的目录初始化可以使用真实 Room；结束其原作用域后，刷新由可控队列驱动。
        val originalScope = field<CoroutineScope>("scope")
        originalScope.cancel()
        awaitCondition { originalScope.coroutineContext[Job]!!.isCompleted }
        setField("scope", testScope)
        setField("rendererReady", true)
        setField("engineVisible", false)
        setField("currentImageUri", Uri.parse(uri("one")))
        setField("cachedImageSet", ImageSet(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), id = uri("one")))
        // 不调用 Engine.onCreate：该回调会启动真正的 GL 线程。
    }

    @After
    fun closeEngineAndDatabase() {
        try {
            if (::engine.isInitialized) engine.onDestroy()
        } finally {
            testScope.cancel()
            while (dispatcher.runOne()) Unit
            shadowOf(Looper.getMainLooper()).idle()
            if (::service.isInitialized) service.destroy()
            database.close()
            ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply {
                isAccessible = true
                set(null, null)
            }
        }
    }

    @Test
    fun becomingVisibleWithoutCacheRestoresCurrentInsteadOfRequestingNext() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        field<ImageSet>("cachedImageSet").original.recycle()
        setField("cachedImageSet", null)
        setField("currentImageUri", null)

        engine.onVisibilityChanged(true)
        awaitRefresh()

        assertNotNull(field<Job?>("imageLoadingJob"))
        assertTrue(field<Job>("imageLoadingJob").isActive)
        assertNull("已有快照时不得并行发起 NEXT", field<Job?>("imageCycleJob"))
        assertEquals(committed, coordinator().snapshot())
        assertEquals(committed, store.load())
        assertEquals(uri("two"), coordinator().current()!!.id)
    }

    @Test
    fun becomingVisibleLoadsCurrentEvenWhenHiddenRefreshAlreadyRestoredIdenticalSnapshot() {
        store.save(snapshotAt("one", 0))
        refreshAndAwait()

        val newer = snapshotAt("three", 2)
        store.save(newer)
        refreshAndAwait()
        assertEquals(uri("three"), coordinator().current()!!.id)
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
        assertNull(field<Job?>("imageLoadingJob"))
        val hiddenGeneration = field<Long>("loadGeneration")

        // 目录与快照都已相同，但真正显示的仍是 one；再次可见必须补发 three 的加载。
        engine.onVisibilityChanged(true)
        awaitRefresh()

        assertTrue(field<Long>("loadGeneration") > hiddenGeneration)
        assertNotNull(field<Job?>("imageLoadingJob"))
        assertTrue(field<Job>("imageLoadingJob").isActive)
        assertEquals(newer, coordinator().snapshot())
        assertEquals(newer, store.load())
    }

    @Test
    fun losingCommitResyncsDisplayedImageAfterMemoryHasAlreadyRecoveredWinningSnapshot() {
        val old = snapshotAt("one", 0)
        store.save(old)
        refreshAndAwait()

        val winner = snapshotAt("three", 2)
        store.save(winner)
        assertFalse(store.compareAndSave(old, snapshotAt("two", 1)))
        // 复现 CAS 失败分支已恢复内存，而 GPU/缓存 URI 尚未同步的边界。
        coordinator().restore(store.load()!!)
        setField("engineVisible", true)
        val beforeRefresh = field<Long>("loadGeneration")

        refreshAndAwait()

        assertTrue(field<Long>("loadGeneration") > beforeRefresh)
        assertNotNull(field<Job?>("imageLoadingJob"))
        assertTrue(field<Job>("imageLoadingJob").isActive)
        assertEquals(winner, coordinator().snapshot())
        assertEquals(winner, store.load())
        // 在解码任务执行前停下；旧 URI 在此刻仍旧，不能把安排加载误报成已呈现。
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
    }

    @Test
    fun identicalSaveNotificationKeepsPendingRequestAndCommittedHistory() {
        val committed = snapshotAt("three", 2)
        store.save(committed)
        refreshAndAwait()
        setField("currentImageUri", Uri.parse(uri("three")))
        setField("engineVisible", true)
        val pending = coordinator().requestPrevious()
        assertEquals(uri("two"), pending.item!!.id)
        val pendingCycle = Job()
        val pendingLoad = Job()
        setField("imageCycleJob", pendingCycle)
        setField("imageLoadingJob", pendingLoad)
        val generation = field<Long>("loadGeneration")

        // 直接投递真实监听器，避免重复写入相同字符串时平台是否发通知的差异。
        val listener = field<SharedPreferences.OnSharedPreferenceChangeListener>("playbackSnapshotListener")
        listener.onSharedPreferenceChanged(
            context.getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE),
            PlaybackSnapshotStore.SNAPSHOT_KEY,
        )
        awaitRefresh()

        assertEquals(generation, field<Long>("loadGeneration"))
        assertSame(pendingCycle, field<Job>("imageCycleJob"))
        assertSame(pendingLoad, field<Job>("imageLoadingJob"))
        assertTrue(pendingCycle.isActive)
        assertTrue(pendingLoad.isActive)
        assertEquals(committed, coordinator().snapshot())
        assertEquals(committed, store.load())
        assertTrue("同值通知不得取消协调器中的待提交请求", coordinator().commit(pending.version))
        assertEquals(listOf(uri("one"), uri("two"), uri("three")), coordinator().snapshot().historyIds)
        assertEquals(1, coordinator().snapshot().historyCursor)
        assertEquals(uri("two"), coordinator().current()!!.id)
    }

    @Test
    fun clearHistoryCancelsOldPendingRequestWithoutReloadingOrAdvancingTheShownImage() {
        val placement = dev.abdus.apps.shimmer.p3.ImageTransform(userScale = 2f, offsetX = 0.25f)
        val committed = snapshotAt("three", 2).copy(historyTransforms = listOf(null, null, placement))
        store.save(committed)
        refreshAndAwait()
        setField("currentImageUri", Uri.parse(uri("three")))
        setField("engineVisible", true)
        val pending = coordinator().requestPrevious()
        val pendingCycle = Job()
        setField("imageCycleJob", pendingCycle)

        val cleared = store.clearHistory()
        refreshAndAwait()

        assertEquals(cleared, store.load())
        assertEquals(cleared, coordinator().snapshot())
        assertEquals(uri("three"), coordinator().current()!!.id)
        assertEquals(uri("three"), field<Uri>("currentImageUri").toString())
        assertEquals(placement, coordinator().snapshot().currentEntryTransform(uri("three")))
        assertFalse("清空前的协调器请求必须失效", coordinator().commit(pending.version))
        assertTrue(pendingCycle.isCancelled)
        assertNull("当前同图不得为清历史发起重新解码", field<Job?>("imageLoadingJob"))
    }

    @Test
    fun clearWithSortingResetKeepsCurrentAndSchedulesNoAutomaticNextOrDecode() {
        val committed = snapshotAt("three", 2)
        store.save(committed)
        refreshAndAwait()
        setField("currentImageUri", Uri.parse(uri("three")))
        setField("engineVisible", true)

        val cleared = store.clearHistory(resetManualOrder = true)
        refreshAndAwait()

        assertEquals(PlaybackOrder.NATURAL_ASC, coordinator().snapshot().order)
        assertEquals(cleared, store.load())
        assertEquals(uri("three"), coordinator().current()!!.id)
        assertEquals(uri("three"), field<Uri>("currentImageUri").toString())
        assertEquals(listOf(uri("three")), coordinator().snapshot().historyIds)
        assertNull("改排序但保留当前同图，不应隐式 NEXT", field<Job?>("imageCycleJob"))
        assertNull("改排序但保留当前同图，不应隐式重载画面", field<Job?>("imageLoadingJob"))
    }

    @Test
    fun replacementRootRefreshSchedulesRestoreWithoutPublishingSavedPointBeforeDecode() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two", "other-three"))
        setRootEnabled("source", false)
        setField("engineVisible", true)
        val cachedBefore = field<ImageSet>("cachedImageSet")

        refreshAndAwait()

        assertTrue(coordinator().sourceRestoreNeeded())
        assertNull("旧来源URI不在新目录，尚未解码不能先改当前点", coordinator().current())
        assertNotNull(field<Job?>("imageCycleJob"))
        assertTrue(field<Job>("imageCycleJob").isActive)
        assertNull("来源恢复经过待提交播放通道，不直接安排已提交当前图加载", field<Job?>("imageLoadingJob"))
        assertEquals("安排恢复不得提前持久化尚未呈现的来源点", committed, store.load())
        assertSame(cachedBefore, field<ImageSet>("cachedImageSet"))
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
    }

    @Test
    fun becomingVisibleSchedulesReplacementRestoreEvenAfterHiddenCatalogWasApplied() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two", "other-three"))
        setRootEnabled("source", false)
        refreshAndAwait()
        assertNull(field<Job?>("imageCycleJob"))
        val hiddenGeneration = field<Long>("loadGeneration")

        engine.onVisibilityChanged(true)
        awaitRefresh()

        assertTrue(coordinator().sourceRestoreNeeded())
        assertTrue(field<Long>("loadGeneration") > hiddenGeneration)
        assertNotNull(field<Job?>("imageCycleJob"))
        assertTrue(field<Job>("imageCycleJob").isActive)
        assertNull(field<Job?>("imageLoadingJob"))
        assertEquals(committed, store.load())
    }

    @Test
    fun neverPlayedReplacementRootStillPreparesRestoreRatherThanPublishingFirstItem() {
        val committed = replacementSnapshot().copy(sourcePositions = mapOf("source" to uri("two")))
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two", "other-three"))
        setRootEnabled("source", false)
        setField("engineVisible", true)

        refreshAndAwait()

        assertTrue(coordinator().sourceRestoreNeeded())
        assertNotNull(field<Job?>("imageCycleJob"))
        assertNull(coordinator().current())
        assertEquals(committed, store.load())
        assertFalse("新来源首项尚未解码，不得提前登记自己的位置",
            store.load()!!.sourcePositions.containsKey("other"))
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
    }

    @Test
    fun replacementWhileHiddenUpdatesCatalogButSchedulesNeitherRestoreNorDecode() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two", "other-three"))
        setRootEnabled("source", false)
        val shown = field<ImageSet>("cachedImageSet")

        refreshAndAwait()

        assertTrue(coordinator().sourceRestoreNeeded())
        assertNull(field<Job?>("imageCycleJob"))
        assertNull(field<Job?>("imageLoadingJob"))
        assertSame(shown, field<ImageSet>("cachedImageSet"))
        assertFalse(shown.original.isRecycled)
        assertEquals(committed, store.load())
    }

    @Test
    fun emptyCatalogKeepsShownPictureWithoutRestoreOrAutomaticNext() {
        val committed = replacementSnapshot()
        store.save(committed)
        setRootEnabled("source", false)
        setField("engineVisible", true)
        val shown = field<ImageSet>("cachedImageSet")

        refreshAndAwait()

        assertTrue(coordinator().catalogItems().isEmpty())
        assertFalse(coordinator().sourceRestoreNeeded())
        assertNull(field<Job?>("imageCycleJob"))
        assertNull(field<Job?>("imageLoadingJob"))
        assertSame(shown, field<ImageSet>("cachedImageSet"))
        assertFalse(shown.original.isRecycled)
        assertEquals(committed, store.load())
    }

    @Test
    fun mixedReplacementRootsKeepGlobalHistoryInsteadOfPickingOneArbitrarily() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two", "other-three"))
        registerRoot("third", listOf("third-one", "third-two"))
        setRootEnabled("source", false)
        setField("engineVisible", true)

        refreshAndAwait()

        assertEquals(setOf("other", "third"), coordinator().catalogItems().map { it.sourceId }.toSet())
        assertFalse(coordinator().sourceRestoreNeeded())
        assertNull(field<Job?>("imageCycleJob"))
        assertNull(field<Job?>("imageLoadingJob"))
        assertEquals(committed.historyIds, coordinator().snapshot().historyIds)
        assertEquals(committed, store.load())
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
    }

    @Test
    fun stillEffectiveCurrentWithAnotherRootDoesNotImplicitlyRestoreOrAdvance() {
        val committed = snapshotAt("one", 0)
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two"))
        setField("engineVisible", true)

        refreshAndAwait()

        assertFalse(coordinator().sourceRestoreNeeded())
        assertEquals(uri("one"), coordinator().current()!!.id)
        assertNull(field<Job?>("imageCycleJob"))
        assertNull(field<Job?>("imageLoadingJob"))
        assertEquals(committed, store.load())
    }

    @Test
    fun editingHoldDefersReplacementRefreshWithoutInventingANextAction() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two"))
        setRootEnabled("source", false)
        setField("engineVisible", true)
        val hold = field<EditHoldGate>("editHold")
        hold.begin()

        requestRefresh()

        assertNull(field<Job?>("playbackSnapshotRefreshJob"))
        assertNull(field<Job?>("imageCycleJob"))
        assertNull(field<Job?>("imageLoadingJob"))
        assertEquals(listOf(HeldAction.Refresh), hold.release())
        assertEquals(committed, store.load())
    }

    @Test
    fun directSyncDuringEditingRecordsOnlyRefreshForAnInactiveCurrentSource() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two"))
        setRootEnabled("source", false)
        refreshAndAwait()
        assertTrue(coordinator().sourceRestoreNeeded())
        setField("engineVisible", true)
        val hold = field<EditHoldGate>("editHold")
        hold.begin()

        syncDisplayAndAwait()

        assertNull(field<Job?>("imageCycleJob"))
        assertNull(field<Job?>("imageLoadingJob"))
        assertEquals("自动恢复只登记 Refresh，不能覆盖真实待切换动作",
            listOf(HeldAction.Refresh), hold.release())
        assertEquals(committed, store.load())
    }

    @Test
    fun notReadyRendererDoesNotScheduleSourceRestoreOrPublishState() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two"))
        setRootEnabled("source", false)
        refreshAndAwait()
        setField("engineVisible", true)
        setField("rendererReady", false)

        syncDisplayAndAwait()

        assertTrue(coordinator().sourceRestoreNeeded())
        assertNull(field<Job?>("imageCycleJob"))
        assertNull(field<Job?>("imageLoadingJob"))
        assertEquals(committed, store.load())
    }

    @Test
    fun automaticRestoreSchedulingCancelsOldDirectLoadButRetainsCachedPicture() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two"))
        setRootEnabled("source", false)
        setField("engineVisible", true)
        val staleLoad = Job()
        setField("imageLoadingJob", staleLoad)
        val shown = field<ImageSet>("cachedImageSet")

        refreshAndAwait()

        assertTrue(staleLoad.isCancelled)
        assertTrue(field<Job>("imageCycleJob").isActive)
        assertSame(shown, field<ImageSet>("cachedImageSet"))
        assertFalse(shown.original.isRecycled)
        assertEquals(committed, store.load())
    }

    @Test
    fun replacementRefreshDoesNotEraseAnAlreadyHeldRealPreviousAction() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two"))
        setRootEnabled("source", false)
        refreshAndAwait()
        setField("engineVisible", true)
        val hold = field<EditHoldGate>("editHold")
        hold.begin()
        assertFalse(hold.admitSwitch(HeldAction.Step(PlaybackDirection.PREVIOUS)))

        syncDisplayAndAwait()

        assertNull(field<Job?>("imageCycleJob"))
        assertEquals(
            listOf(HeldAction.Refresh, HeldAction.Step(PlaybackDirection.PREVIOUS)),
            hold.release(),
        )
        assertEquals(committed, store.load())
    }

    @Test
    fun ownSaveSyncBeforeMainHandoffKeepsActiveRestoreAndGeneration() {
        registerRoot("other", listOf("other-one", "other-two", "other-three"))
        setRootEnabled("source", false)
        val committed = replacementSnapshot().copy(
            queueIds = listOf(uri("other-one"), uri("other-two"), uri("other-three")),
            queueIndex = 1,
            historyIds = listOf(uri("one"), uri("two"), uri("three"), uri("other-two")),
            historyCursor = 3,
            currentId = uri("other-two"),
        )
        store.save(committed)
        refreshAndAwait()
        assertEquals(uri("other-two"), coordinator().current()!!.id)
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
        val activeRestore = Job()
        setField("imageCycleJob", activeRestore)
        setField("engineVisible", true)
        val beforeSync = field<Long>("loadGeneration")
        val shown = field<ImageSet>("cachedImageSet")

        // 模拟恢复已提交到快照、Main 尚未接管的边界，不执行解码或 GPU。
        // 同值 own-save 刷新的同步方法不得抢 generation、使原任务的手势重放失效。
        syncDisplayAndAwait()

        assertEquals(beforeSync, field<Long>("loadGeneration"))
        assertSame(activeRestore, field<Job>("imageCycleJob"))
        assertTrue(activeRestore.isActive)
        assertNull("正在接管的恢复任务拥有同步职责，不另发直接加载", field<Job?>("imageLoadingJob"))
        assertSame(shown, field<ImageSet>("cachedImageSet"))
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
        assertEquals(committed, store.load())
    }

    @Test
    fun changedRootRefreshCancelsOldCycleBeforeSchedulingNewRestore() {
        val committed = replacementSnapshot()
        store.save(committed)
        registerRoot("other", listOf("other-one", "other-two", "other-three"))
        setRootEnabled("other", false)
        refreshAndAwait()
        assertFalse(coordinator().sourceRestoreNeeded())
        val oldCycle = Job()
        setField("imageCycleJob", oldCycle)
        setField("engineVisible", true)
        val beforeRefresh = field<Long>("loadGeneration")

        setRootEnabled("source", false)
        setRootEnabled("other", true)
        refreshAndAwait()

        assertTrue("真正的目录变更必须取消旧接管任务", oldCycle.isCancelled)
        val replacementCycle = field<Job>("imageCycleJob")
        assertFalse("active-cycle 同步保护不能拦截真正的来源替换", replacementCycle === oldCycle)
        assertTrue(replacementCycle.isActive)
        assertTrue(field<Long>("loadGeneration") > beforeRefresh)
        assertTrue(coordinator().sourceRestoreNeeded())
        assertNull(field<Job?>("imageLoadingJob"))
        assertEquals("尚未解码的来源恢复不得提前提交位置", committed, store.load())
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
    }

    @Test fun unchangedSnapshotOnlyNotificationSkipsSecondCatalogQueryWithoutChangingState() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        refreshAndAwait()
        val before = coordinator().snapshot()
        // A real failing SQL seam proves this notification does not issue a catalog query.
        // Only this isolated test DB is altered, with restoration even on assertion failure.
        database.openHelper.writableDatabase.execSQL("ALTER TABLE image_records RENAME TO snapshot_test_records")
        try {
            requestSnapshotOnly()
            awaitRefresh()
            assertEquals(before, coordinator().snapshot())
            assertEquals(committed, store.load())
            assertFalse(field<CatalogRefreshDemand>("catalogRefreshDemand").requiresRead())
        } finally {
            database.openHelper.writableDatabase.execSQL("ALTER TABLE snapshot_test_records RENAME TO image_records")
        }
    }

    @Test fun changedStoredModeStillRefreshesEvenWithoutDirtyCatalogDemand() {
        store.save(snapshotAt("two", 1))
        refreshAndAwait()
        val changed = store.load()!!.copy(order = PlaybackOrder.NATURAL_DESC, forwardHistoryInvalidated = true)
        store.save(changed)
        requestSnapshotOnly()
        awaitRefresh()
        assertEquals(PlaybackOrder.NATURAL_DESC, coordinator().snapshot().order)
        assertTrue(coordinator().snapshot().forwardHistoryInvalidated)
        assertEquals(uri("two"), coordinator().current()!!.id)
        assertEquals(changed.forPersistence(), store.load())
    }

    @Test fun snapshotBurstCannotDowngradePendingRealSourceReplacement() {
        val committed = replacementSnapshot()
        store.save(committed)
        refreshAndAwait()
        registerRoot("other", listOf("other-one", "other-two"))
        setRootEnabled("source", false)
        requestRefresh()
        val superseded = field<Job>("playbackSnapshotRefreshJob")
        requestSnapshotOnly()
        assertTrue(superseded.isCancelled)
        awaitRefresh()
        assertEquals(listOf(uri("other-one"), uri("other-two")), coordinator().catalogItems().map { it.id })
        assertTrue(coordinator().sourceRestoreNeeded())
        assertFalse(field<CatalogRefreshDemand>("catalogRefreshDemand").requiresRead())
        assertEquals(committed, store.load())
    }

    @Test fun identicalLightweightSaveKeepsPendingActionGenerationAndCachedImage() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        refreshAndAwait()
        val request = coordinator().requestNext()
        val generation = field<Long>("loadGeneration")
        val shown = field<ImageSet>("cachedImageSet")
        requestSnapshotOnly()
        awaitRefresh()
        assertEquals(generation, field<Long>("loadGeneration"))
        assertSame(shown, field<ImageSet>("cachedImageSet"))
        assertTrue(coordinator().commit(request.version))
        assertEquals(committed, store.load())
    }

    @Test fun editingHoldRetainsStrongCatalogDemandAcrossLightweightNotification() {
        store.save(snapshotAt("two", 1))
        refreshAndAwait()
        val hold = field<EditHoldGate>("editHold")
        hold.begin()
        requestRefresh()
        requestSnapshotOnly()
        assertTrue(field<CatalogRefreshDemand>("catalogRefreshDemand").requiresRead())
        assertEquals(listOf(HeldAction.Refresh), hold.release())
        requestSnapshotOnly()
        awaitRefresh()
        assertFalse(field<CatalogRefreshDemand>("catalogRefreshDemand").requiresRead())
    }

    @Test fun imageEnablePreferenceDuringEditIsHeldAsARealCatalogDemand() {
        store.save(snapshotAt("two", 1))
        refreshAndAwait()
        val hold = field<EditHoldGate>("editHold")
        hold.begin()
        val preferences = dev.abdus.apps.shimmer.WallpaperPreferences.create(context)
        preferences.setImageEnabled(uri("two"), false)
        field<SharedPreferences.OnSharedPreferenceChangeListener>("preferenceListener")
            .onSharedPreferenceChanged(null, dev.abdus.apps.shimmer.WallpaperPreferences.KEY_DISABLED_IMAGES)
        assertTrue(field<CatalogRefreshDemand>("catalogRefreshDemand").requiresRead())
        assertEquals(listOf(HeldAction.Refresh), hold.release())
        requestSnapshotOnly()
        awaitRefresh()
        assertFalse(coordinator().catalogItems().any { it.id == uri("two") })
        assertEquals(uri("two"), store.load()!!.currentId)
        assertFalse(field<CatalogRefreshDemand>("catalogRefreshDemand").requiresRead())
    }

    private fun requestSnapshotOnly() {
        engine.javaClass.getDeclaredMethod("refreshPlaybackSnapshotOnly").apply {
            isAccessible = true
            invoke(engine)
        }
    }

    @Test fun hiddenSelectionIsOnlyAnIntentAndKeepsSnapshotAndCachedPicture() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        refreshAndAwait()
        val shown = field<ImageSet>("cachedImageSet")
        val target = field<PlaybackSelectionBridge.Target>("selectionTarget")
        assertEquals(PlaybackSelectionBridge.Result.QUEUED, target.select(uri("three")))
        assertEquals(uri("three"), field<String>("pendingSelectedImageId"))
        assertEquals(committed, store.load())
        assertSame(shown, field<ImageSet>("cachedImageSet"))
        assertNull(field<Job?>("imageCycleJob"))
    }

    @Test fun latestHiddenSelectionWinsWithoutAppendingAnyFakeHistory() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        refreshAndAwait()
        val target = field<PlaybackSelectionBridge.Target>("selectionTarget")
        target.select(uri("one")); target.select(uri("three"))
        assertEquals(uri("three"), field<String>("pendingSelectedImageId"))
        assertEquals(committed, store.load())
    }

    @Test fun selectionOfShownPictureDoesNotScheduleAnyDecodeOrAlterHistory() {
        val committed = snapshotAt("one", 0)
        store.save(committed)
        refreshAndAwait()
        val target = field<PlaybackSelectionBridge.Target>("selectionTarget")
        assertEquals(PlaybackSelectionBridge.Result.NOOP, target.select(uri("one")))
        assertNull(field<String?>("pendingSelectedImageId"))
        assertEquals(committed, store.load())
        assertNull(field<Job?>("imageCycleJob"))
    }

    @Test fun selectionDuringEditingIsRejectedWithoutReplacingHeldPrevious() {
        store.save(snapshotAt("two", 1))
        refreshAndAwait()
        val before = store.load()
        val hold = field<EditHoldGate>("editHold")
        hold.begin()
        hold.admitSwitch(HeldAction.Step(PlaybackDirection.PREVIOUS))
        val target = field<PlaybackSelectionBridge.Target>("selectionTarget")
        assertEquals(PlaybackSelectionBridge.Result.BUSY_EDIT, target.select(uri("three")))
        assertNull(field<String?>("pendingSelectedImageId"))
        assertEquals(before, store.load())
        assertEquals(listOf(HeldAction.Step(PlaybackDirection.PREVIOUS)), hold.release())
    }

    @Test fun selectingCurrentSupersedesAnInFlightDifferentSelectionAndKeepsShownPicture() {
        val committed = snapshotAt("one", 0)
        store.save(committed)
        refreshAndAwait()
        val pending = Job()
        setField("imageCycleJob", pending)
        val oldGeneration = field<Long>("loadGeneration")
        assertEquals(PlaybackSelectionBridge.Result.QUEUED,
            field<PlaybackSelectionBridge.Target>("selectionTarget").select(uri("one")))
        assertTrue(pending.isCancelled)
        assertTrue(field<Long>("loadGeneration") > oldGeneration)
        assertEquals(uri("one"), field<String?>("pendingSelectedImageId"))
        assertEquals(committed, store.load())
    }

    @Test fun sameShownSelectionAfterAnotherSnapshotCommitQueuesLatestIntentRatherThanNoop() {
        store.save(snapshotAt("one", 0))
        refreshAndAwait()
        val committedB = snapshotAt("two", 1)
        store.save(committedB)
        val inFlight = Job()
        setField("imageCycleJob", inFlight)
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
        assertEquals(PlaybackSelectionBridge.Result.QUEUED,
            field<PlaybackSelectionBridge.Target>("selectionTarget").select(uri("one")))
        assertTrue(inFlight.isCancelled)
        assertEquals(uri("one"), field<String?>("pendingSelectedImageId"))
        assertEquals(committedB, store.load()) // No fake A commit before its guarded decode.
    }

    @Test fun queuedSelectionReplaysOnlyWhenVisibleAndReadyAndKeepsSnapshotUntilDecode() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        refreshAndAwait()
        field<PlaybackSelectionBridge.Target>("selectionTarget").select(uri("three"))
        val replay = engine.javaClass.getDeclaredMethod("replaySelectedImage").apply { isAccessible = true }
        assertEquals(false, replay.invoke(engine))
        setField("engineVisible", true)
        setField("rendererReady", false)
        assertEquals(false, replay.invoke(engine))
        setField("rendererReady", true)
        assertEquals(true, replay.invoke(engine))
        assertNull(field<String?>("pendingSelectedImageId"))
        assertNotNull(field<Job?>("imageCycleJob"))
        assertEquals(committed, store.load())
    }

    @Test fun newerRealGestureSupersedesAnUnexecutedHiddenSelection() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        refreshAndAwait()
        field<PlaybackSelectionBridge.Target>("selectionTarget").select(uri("three"))
        engine.javaClass.getDeclaredMethod(
            "requestPlayback", PlaybackDirection::class.java,
            dev.abdus.apps.shimmer.p4.ScheduleDispatch::class.java,
        ).apply { isAccessible = true }.invoke(engine, PlaybackDirection.PREVIOUS, null)
        assertNull(field<String?>("pendingSelectedImageId"))
        assertEquals(committed, store.load())
    }

    @Test fun newerScheduleSupersedesQueuedSelectionWithoutPersistingEitherBeforeReady() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        refreshAndAwait()
        field<PlaybackSelectionBridge.Target>("selectionTarget").select(uri("three"))
        setField("rendererReady", false)
        val dispatch = dev.abdus.apps.shimmer.p4.ScheduleDispatch(700,
            setOf(dev.abdus.apps.shimmer.p4.ScheduleReason.INTERVAL))
        engine.onScheduledSwitch(dispatch)
        assertNull(field<String?>("pendingSelectedImageId"))
        assertSame(dispatch, field<dev.abdus.apps.shimmer.p4.ScheduleDispatch>("parkedDispatch"))
        assertEquals(committed, store.load())
    }

    @Test fun newerSelectionSupersedesOldParkedScheduleRatherThanLosingItsIntentOnReady() {
        val committed = snapshotAt("two", 1)
        store.save(committed)
        refreshAndAwait()
        val parked = dev.abdus.apps.shimmer.p4.ScheduleDispatch(701,
            setOf(dev.abdus.apps.shimmer.p4.ScheduleReason.INTERVAL))
        setField("parkedDispatch", parked)
        assertEquals(PlaybackSelectionBridge.Result.QUEUED,
            field<PlaybackSelectionBridge.Target>("selectionTarget").select(uri("three")))
        assertNull(field<dev.abdus.apps.shimmer.p4.ScheduleDispatch?>("parkedDispatch"))
        assertEquals(uri("three"), field<String?>("pendingSelectedImageId"))
        assertEquals(committed, store.load())
    }

    @Test fun lifecycleCapturesLatestSelectedIntentBeforeLateDecoderCleanupAndVisibilityReturn() {
        store.save(snapshotAt("one", 0))
        refreshAndAwait()
        setField("activeSelectedImageId", uri("three"))
        setField("activeSelectedToken", field<Any>("selectionIntentToken"))
        engine.javaClass.getDeclaredMethod("rememberSelectedImageForLifecycle")
            .apply { isAccessible = true }.invoke(engine)
        setField("engineVisible", true) // Return before old decoder/finally runs.
        assertEquals(uri("three"), field<String?>("pendingSelectedImageId"))
        assertEquals(true, engine.javaClass.getDeclaredMethod("replaySelectedImage").apply { isAccessible = true }.invoke(engine))
        assertNull(field<String?>("pendingSelectedImageId"))
        assertNotNull(field<Job?>("imageCycleJob"))
        assertEquals(uri("one"), store.load()!!.currentId)
    }

    @Test fun obsoleteInFlightSelectionCannotBeCapturedAfterNewRealGestureIntent() {
        store.save(snapshotAt("one", 0))
        refreshAndAwait()
        setField("activeSelectedImageId", uri("three"))
        setField("activeSelectedToken", field<Any>("selectionIntentToken"))
        setField("selectionIntentToken", Any()) // Genuine later action replaces identity.
        engine.javaClass.getDeclaredMethod("rememberSelectedImageForLifecycle")
            .apply { isAccessible = true }.invoke(engine)
        assertNull(field<String?>("pendingSelectedImageId"))
        assertEquals(uri("one"), store.load()!!.currentId)
    }

    @Test fun sameShownAWithCommittedBStillCapturesLatestAOnLifecycleInterruption() {
        store.save(snapshotAt("one", 0))
        refreshAndAwait()
        val committedB = snapshotAt("two", 1)
        store.save(committedB)
        setField("activeSelectedImageId", uri("one"))
        setField("activeSelectedToken", field<Any>("selectionIntentToken"))
        assertEquals(uri("one"), field<Uri>("currentImageUri").toString())
        engine.javaClass.getDeclaredMethod("rememberSelectedImageForLifecycle")
            .apply { isAccessible = true }.invoke(engine)
        assertEquals(uri("one"), field<String?>("pendingSelectedImageId"))
        assertEquals(committedB, store.load())
    }

    private fun replacementSnapshot() = snapshotAt("two", 1).copy(
        sourcePositions = mapOf("source" to uri("two"), "other" to uri("other-two")),
    )

    private fun registerRoot(sourceId: String, names: List<String>) = runBlocking {
        val dao = database.p1SourceDao()
        dao.registerSource(P1SourceEntity(sourceId, "file:///$sourceId", "DIRECTORY_TREE"))
        dao.commitScan(sourceId, names.map { name ->
            P1ImageRecordEntity(name, uri(name), "$name.png", "$name.png")
        }, "APP_OWNED", "2026-10-10T00:00:00Z")
    }

    private fun setRootEnabled(sourceId: String, enabled: Boolean) = runBlocking {
        database.p1SourceDao().setSourceEnabled(sourceId, enabled, "2026-10-10T00:00:00Z")
    }

    private fun syncDisplayAndAwait() = runBlocking {
        suspendCoroutine<Unit> { continuation ->
            try {
                val result = engine.javaClass.getDeclaredMethod(
                    "syncDisplayedImage", kotlin.coroutines.Continuation::class.java,
                ).apply { isAccessible = true }.invoke(engine, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
            } catch (failed: java.lang.reflect.InvocationTargetException) {
                continuation.resumeWithException(failed.targetException)
            } catch (failed: Throwable) {
                continuation.resumeWithException(failed)
            }
        }
    }

    private fun snapshotAt(current: String, cursor: Int) = PlaybackSnapshot(
        order = PlaybackOrder.MANUAL,
        queueIds = listOf(uri("one"), uri("two"), uri("three")),
        queueIndex = cursor,
        historyIds = listOf(uri("one"), uri("two"), uri("three")),
        historyCursor = cursor,
        currentId = uri(current),
    )

    private fun coordinator(): PlaybackCoordinator = field("playbackCoordinator")

    private fun refreshAndAwait() {
        requestRefresh()
        awaitRefresh()
    }

    private fun requestRefresh() {
        engine.javaClass.getDeclaredMethod("refreshPlaybackStateFromSnapshot").apply {
            isAccessible = true
            invoke(engine)
        }
    }

    private fun awaitRefresh() {
        val refresh = field<Job>("playbackSnapshotRefreshJob")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!refresh.isCompleted && System.nanoTime() < deadline) {
            if (!dispatcher.runOne()) Thread.sleep(5)
        }
        assertTrue("真实 Room 查询和 Engine 刷新应在限时内完成", refresh.isCompleted)
        assertFalse("刷新任务不得因异常或取消而被误判为完成", refresh.isCancelled)
        // 不清空队列：loadImage 安排的新解码任务有意留待断言之后取消。
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("构造时的原作用域应在限时内结束", condition())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = engine.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(engine) as T
    }

    private fun setField(name: String, value: Any?) {
        engine.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(engine, value)
        }
    }

    private fun uri(name: String) = "file:///$name.png"

    /** 只替代系统 attach 后提供的正式/预览标志，不替代应用 Engine 的任何业务方法。 */
    @Implements(WallpaperService.Engine::class)
    class FormalEngineShadow {
        @Implementation
        fun isPreview(): Boolean = false
    }

    private class StepDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun runOne(): Boolean {
            val next = queue.poll() ?: return false
            next.run()
            return true
        }
    }
}
