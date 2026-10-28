package dev.abdus.apps.shimmer.p10.editor

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p2.PlaybackCoordinator
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p3.P3Defaults
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real LOCK Room catalog + real named preferences/CAS. No fake repository or store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LockEngineEditControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = ShimmerDatabase.getInstance(context, WallpaperTarget.LOCK)
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val repository = ImageFolderRepository(context, scope, WallpaperTarget.LOCK)
    private val preferences = WallpaperPreferences.create(context, WallpaperTarget.LOCK)
    private val store = PlaybackSnapshotStore(context, WallpaperTarget.LOCK)
    private val home = PlaybackSnapshotStore(context, WallpaperTarget.HOME)
    private val eligible = AtomicBoolean(true)
    private val acquires = AtomicInteger()
    private val releases = AtomicInteger()
    private var acquireAllowed = true
    private var capturedId = A
    private val decoded = Collections.synchronizedList(mutableListOf<String>())
    private var decode: suspend (Uri, ImageTransform) -> LockEngineEditController.DecodedInfo? = { uri, _ ->
        decoded += uri.toString()
        LockEngineEditController.DecodedInfo(4000, 3000)
    }
    private lateinit var controller: LockEngineEditController
    private val released = CompletableDeferred<Unit>()
    private var publicationPermit: (() -> Boolean) -> Boolean = { publish -> publish() }

    @Before fun seedRealLockCatalogAndCurrentSnapshot() = runBlocking {
        val dao = database.p1SourceDao()
        dao.registerSource(P1SourceEntity("source", "content://roots/LOCK", "DIRECTORY_TREE"))
        dao.commitScan("source", listOf(A, B, C).mapIndexed { index, uri ->
            P1ImageRecordEntity("record-$index", uri, "${index + 1}.png", "${index + 1}.png")
        }, "APP_OWNED", "2026-10-17")
        val coordinator = PlaybackCoordinator(0x504C41594241434BL)
        coordinator.setItems(repository.getPlaybackItems())
        assertTrue(coordinator.commit(coordinator.requestSelected(A).version))
        store.save(coordinator.snapshot())
        home.save(coordinator.snapshot())
        controller = LockEngineEditController(context, scope, repository, preferences, store,
            capture = {
                LockEngineEditController.Captured(LiveEditBridge.EditImage(capturedId, 4000, 3000, 1200, 2400,
                    ImageTransform(), P3Defaults()), Uri.parse(capturedId))
            }, isEligible = eligible::get,
            acquireHold = { acquires.incrementAndGet(); acquireAllowed },
            releaseHold = { releases.incrementAndGet(); released.complete(Unit) },
            decodeInfo = { uri, placement -> decode(uri, placement) },
            publicationGuard = { publish -> publicationPermit(publish) })
    }

    @After fun close() = runBlocking {
        if (::controller.isInitialized) controller.close()
        scope.cancel()
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("LOCK_INSTANCE").apply { isAccessible = true; set(null, null) }
        Unit
    }

    private fun session(): LockEditorBridge.Session {
        val pending = controller.prepareLockEdit()!!
        assertEquals(1, acquires.get())
        val result = pending.authenticate()!!
        assertFalse(pending.active)
        return result
    }

    private suspend fun step(session: LockEditorBridge.Session, forward: Boolean = true,
        save: Boolean = false, transform: ImageTransform? = null): LiveEditBridge.StepResult? {
        val completion = CompletableDeferred<LiveEditBridge.StepResult?>()
        session.step(forward, save, transform) { completion.complete(it) }
        return completion.await()
    }

    @Test fun pendingAcquiresHoldBeforeAuthenticationAndDoesNotDecodeOrWrite() {
        val before = store.load()
        val pending = controller.prepareLockEdit()!!
        assertTrue(pending.active)
        assertEquals(1, acquires.get())
        assertEquals(0, releases.get())
        assertTrue(decoded.isEmpty())
        assertEquals(before, store.load())
        assertNull(store.loadImageTransform(A))
    }

    @Test fun successfulAuthenticationTransfersSameHoldAndCanOnlyHappenOnce() {
        val pending = controller.prepareLockEdit()!!
        val session = pending.authenticate()!!
        assertTrue(session.active)
        assertEquals(A, session.previewUri.toString())
        assertFalse(pending.active)
        assertNull(pending.authenticate())
        assertTrue(session.active)
        // A duplicate failed authenticate must not silently grant a replacement session.
        assertEquals(1, acquires.get())
    }

    @Test fun cancelPendingReleasesExactlyOnceAndCannotAuthenticate() {
        val pending = controller.prepareLockEdit()!!
        pending.cancel(); pending.cancel()
        assertEquals(1, releases.get())
        assertNull(pending.authenticate())
    }

    @Test fun pendingAfterDeepSleepExpiresByElapsedClockAndReleasesHoldOnQuery() {
        val pending = controller.prepareLockEdit()!!
        org.robolectric.shadows.ShadowSystemClock.simulateDeepSleep(java.time.Duration.ofMillis(60_001L))
        assertFalse(pending.active)
        assertNull(pending.authenticate())
        assertEquals(1, releases.get())
    }

    @Test fun expectedBouncerDoesNotNeedFormalEngineVisibility() {
        // isEligible contains route/flags/alive, deliberately not engineVisible or keyguard.
        val pending = controller.prepareLockEdit()!!
        assertTrue(pending.active)
        val session = pending.authenticate()!!
        assertTrue(session.active)
        assertEquals(0, releases.get())
    }

    @Test fun routeOrFlagsRevocationCancelsPendingAndCannotChangeHome() {
        val homeBefore = home.load()
        val pending = controller.prepareLockEdit()!!
        eligible.set(false)
        assertFalse(pending.active)
        assertNull(pending.authenticate())
        assertEquals(1, releases.get())
        assertEquals(homeBefore, home.load())
    }

    @Test fun explicitEpochInvalidationRejectsLateAuthenticationAndLateSessionUse() {
        val pending = controller.prepareLockEdit()!!
        controller.invalidate()
        assertNull(pending.authenticate())
        assertEquals(1, releases.get())
        val next = controller.prepareLockEdit()!!.authenticate()!!
        controller.invalidate()
        assertFalse(next.active)
        next.save(ImageTransform(rotationDegrees = 90f), 1L)
        assertNull(store.loadImageTransform(A))
        assertEquals(2, releases.get())
    }

    @Test fun capturedImageMustBeExactCurrentCommittedOriginal() {
        capturedId = B
        assertNull(controller.prepareLockEdit())
        assertEquals(1, acquires.get())
        assertEquals(1, releases.get())
        assertEquals(A, store.load()!!.currentId)
    }

    @Test fun failedHoldOrEligibilityCannotCreatePending() {
        eligible.set(false)
        assertNull(controller.prepareLockEdit())
        assertEquals(0, acquires.get())
        eligible.set(true)
        acquireAllowed = false
        assertNull(controller.prepareLockEdit())
        assertEquals(1, acquires.get())
        assertEquals(0, releases.get())
    }

    @Test fun privateDraftAndCancelWriteNeitherLockNorHomeTransform() {
        val before = store.load()
        val homeBefore = home.load()
        val session = session()
        session.preview(ImageTransform(rotationDegrees = 90f), 1L)
        session.cancel(); session.cancel()
        assertEquals(before, store.load())
        assertEquals(homeBefore, home.load())
        assertNull(store.loadImageTransform(A))
        assertNull(home.loadImageTransform(A))
        assertEquals(1, releases.get())
    }

    @Test fun saveCommitsOnlyLockThenReleasesHold() = runBlocking {
        val homeBefore = home.load()
        val transform = ImageTransform(rotationDegrees = 90f, mirrorHorizontal = true)
        val session = session()
        session.save(transform, 1L)
        released.await()
        assertEquals(transform, store.loadImageTransform(A))
        assertNull(home.loadImageTransform(A))
        assertEquals(homeBefore, home.load())
        assertFalse(session.active)
        assertEquals(1, releases.get())
    }

    @Test fun diagnosticSavedCallbackOccursOnlyAfterRealStoreCommit() = runBlocking {
        val session = session()
        val transform = ImageTransform(rotationDegrees=90f)
        val observed = mutableListOf<Boolean>()
        (session as LockEditSaveResultSource).observeSaveResult { saved ->
            observed += saved
            if (saved) assertEquals(transform,store.loadImageTransform(A))
        }
        assertTrue(observed.isEmpty())
        session.save(transform,1); released.await()
        assertEquals(listOf(true),observed)
    }

    @Test fun diagnosticRejectedPublicationNeverReportsSaved() = runBlocking {
        val session = session(); val observed = mutableListOf<Boolean>()
        (session as LockEditSaveResultSource).observeSaveResult { observed += it }
        publicationPermit = { false }
        session.save(ImageTransform(rotationDegrees=90f),1); released.await()
        assertEquals(listOf(false),observed); assertNull(store.loadImageTransform(A))
    }

    @Test fun resetRemovesOnlyLockImageOverride() = runBlocking {
        val transform = ImageTransform(userScale = 2f)
        store.commitImageTransform(A, transform)
        home.commitImageTransform(A, transform)
        val session = session()
        session.save(null, 1L)
        released.await()
        assertNull(store.loadImageTransform(A))
        assertEquals(transform, home.loadImageTransform(A))
    }

    @Test fun ownCurrentImageDisableAndReenableDoNotRevokeSession() {
        val session = session()
        preferences.setImageEnabled(A, false)
        assertTrue(session.active)
        preferences.setImageEnabled(A, true)
        assertTrue(session.active)
        assertEquals(0, releases.get())
    }

    @Test fun hiddenIndependentStepCommitsHistoryAndCanReturnPrevious() = runBlocking {
        val homeBefore = home.load()
        val session = session()
        assertTrue(step(session) is LiveEditBridge.StepResult.Success)
        assertEquals(B, session.image.imageId)
        assertEquals(B, session.previewUri.toString())
        assertEquals(B, store.load()!!.currentId)
        assertTrue(session.active)
        assertTrue(step(session, forward = false) is LiveEditBridge.StepResult.Success)
        assertEquals(A, session.image.imageId)
        assertEquals(A, store.load()!!.currentId)
        assertEquals(homeBefore, home.load())
        assertEquals(0, releases.get())
    }

    @Test fun stepSkipsUnreadableItemsWithBoundedAttempts() = runBlocking {
        decode = { uri, _ -> decoded += uri.toString(); if (uri.toString() == B) null else LockEngineEditController.DecodedInfo(4000, 3000) }
        val session = session()
        assertTrue(step(session) is LiveEditBridge.StepResult.Success)
        assertEquals(C, session.image.imageId)
        assertEquals(listOf(B, C), decoded.toList())
        assertEquals(listOf(A, C), store.load()!!.historyIds)
    }

    @Test fun noReadableCandidateKeepsSnapshotAndCurrentOriginal() = runBlocking {
        decode = { uri, _ -> decoded += uri.toString(); null }
        val before = store.load()
        val session = session()
        assertEquals(LiveEditBridge.StepResult.NoCandidate, step(session))
        assertEquals(before, store.load())
        assertEquals(A, session.image.imageId)
        assertTrue(decoded.size <= 2)
        assertTrue(session.active)
    }

    @Test fun saveBeforeStepPersistsOldOverrideAndNewHistorySeparatelyInLock() = runBlocking {
        val session = session()
        val transform = ImageTransform(rotationDegrees = 90f)
        assertTrue(step(session, save = true, transform = transform) is LiveEditBridge.StepResult.Success)
        assertEquals(transform, store.loadImageTransform(A))
        assertNull(home.loadImageTransform(A))
        assertEquals(B, store.load()!!.currentId)
    }

    @Test fun savedTargetPlacementWinsAndStepUsesActualDecodedDimensions() = runBlocking {
        val target = ImageTransform(rotationDegrees = 90f, mirrorVertical = true)
        store.commitImageTransform(B, target)
        decode = { _, _ -> LockEngineEditController.DecodedInfo(800, 3200) }
        val session = session()
        val result = step(session) as LiveEditBridge.StepResult.Success
        assertEquals(target, result.image.original)
        assertEquals(800, result.image.sourceWidth)
        assertEquals(3200, result.image.sourceHeight)
    }

    @Test fun manualOrderIsUsedWithoutReorderingEitherDomainsConfiguration() = runBlocking {
        val items = repository.getPlaybackItems()
        val coordinator = PlaybackCoordinator(0x504C41594241434BL)
        coordinator.setItemsAndRestore(items, store.load()!!)
        coordinator.setOrder(PlaybackOrder.MANUAL)
        coordinator.reorder(listOf(A, C, B))
        store.save(coordinator.snapshot())
        val homeBefore = home.load()
        val session = session()
        assertTrue(step(session) is LiveEditBridge.StepResult.Success)
        assertEquals(C, session.image.imageId)
        assertEquals(listOf(A, C, B), store.load()!!.queueIds)
        assertEquals(homeBefore, home.load())
    }

    @Test fun lateDecodeCannotOverwriteExternalSelectedCurrentSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        decode = { _, _ -> entered.complete(Unit); gate.await(); LockEngineEditController.DecodedInfo(4000, 3000) }
        val session = session()
        val result = CompletableDeferred<LiveEditBridge.StepResult?>()
        session.step(true, false, null) { result.complete(it) }
        entered.await()
        val external = store.update { it!!.copy(currentId = C, historyIds = listOf(A, C), historyCursor = 1) }
        gate.complete(Unit)
        assertNull(result.await())
        assertEquals(external, store.load())
        assertFalse(session.active)
        assertEquals(1, releases.get())
    }

    @Test fun invalidatingDuringDecodePreventsAnyLateHistoryCommit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        decode = { _, _ -> entered.complete(Unit); gate.await(); LockEngineEditController.DecodedInfo(4000, 3000) }
        val before = store.load()
        val session = session()
        val result = CompletableDeferred<LiveEditBridge.StepResult?>()
        session.step(true, false, null) { result.complete(it) }
        entered.await()
        controller.invalidate()
        gate.complete(Unit)
        assertNull(result.await())
        assertEquals(before, store.load())
        assertEquals(1, releases.get())
    }

    @Test fun explicitExternalSnapshotChangeReleasesHoldWhenUiPollSeesInvalidity() {
        val session = session()
        store.update { it!!.copy(historyResetSerial = it.historyResetSerial + 1) }
        assertFalse(session.active)
        assertEquals(1, releases.get())
        session.cancel()
        assertEquals(1, releases.get())
    }

    @Test fun finalCloseRejectsNewPendingAndReleaseRemainsOnce() {
        session()
        controller.close(); controller.close()
        assertEquals(1, releases.get())
        assertNull(controller.prepareLockEdit())
    }

    @Test fun constructingControllerWithHomeStoreIsRejectedBeforeAnyHold() {
        try {
            LockEngineEditController(context, scope, repository, preferences, home, { null }, { true }, { true }, {}, { _, _ -> null })
            fail("Expected explicit LOCK store check")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, acquires.get())
    }

    @Test fun deniedPublicationGuardDoesNotWriteQueuedSaveInEitherDomain() = runBlocking {
        var calls = 0
        publicationPermit = { calls++; false }
        val homeBefore = home.load()
        val session = session()
        session.save(ImageTransform(rotationDegrees = 90f), 1L)
        released.await()
        assertEquals(1, calls)
        assertNull(store.loadImageTransform(A))
        assertNull(home.loadImageTransform(A))
        assertEquals(homeBefore, home.load())
        assertEquals(1, releases.get())
    }

    @Test fun admittedPublicationGuardIsInvokedExactlyOnceForOneTransformSave() = runBlocking {
        var calls = 0
        var publications = 0
        publicationPermit = { publish -> calls++; publications++; publish() }
        val transform = ImageTransform(mirrorHorizontal = true)
        session().save(transform, 1L)
        released.await()
        assertEquals(1, calls)
        assertEquals(1, publications)
        assertEquals(transform, store.loadImageTransform(A))
        assertNull(home.loadImageTransform(A))
    }

    @Test fun deniedPublicationGuardDoesNotCommitProvisionalStepHistory() = runBlocking {
        var calls = 0
        publicationPermit = { calls++; false }
        val before = store.load()
        val homeBefore = home.load()
        val session = session()
        assertNull(step(session))
        assertEquals(1, calls)
        assertEquals(before, store.load())
        assertEquals(homeBefore, home.load())
        assertEquals(A, session.image.imageId)
        assertFalse(session.active)
        assertEquals(1, releases.get())
    }

    private companion object {
        const val A = "content://lock-images/A"
        const val B = "content://lock-images/B"
        const val C = "content://lock-images/C"
    }
}
