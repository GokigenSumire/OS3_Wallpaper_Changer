package dev.abdus.apps.shimmer.p10

import android.content.Context
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.GestureAction
import dev.abdus.apps.shimmer.TapGesture
import dev.abdus.apps.shimmer.ShimmerWallpaperService
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p2.PlaybackSelectionBridge
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p0.P0Diagnostics
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.P3Defaults
import dev.abdus.apps.shimmer.p4.ScheduleCoordinator
import dev.abdus.apps.shimmer.p5.TransitionSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/** Executes the production adapter without creating a Surface/EGL context; no rendering claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ProductionTargetEngineTest.FlagsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProductionTargetEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engines = mutableListOf<ShimmerWallpaperService.ShimmerWallpaperEngine>()
    private val services = mutableListOf<org.robolectric.android.controller.ServiceController<out ShimmerWallpaperService>>()

    @Before fun resetFlags() { FlagsShadow.flags = 2; FlagsShadow.preview = false }

    private fun engine(target: WallpaperTarget): ShimmerWallpaperService.ShimmerWallpaperEngine {
        val controller = if (target == WallpaperTarget.HOME) Robolectric.buildService(ShimmerWallpaperService::class.java).create()
            else Robolectric.buildService(LockWallpaperService::class.java).create()
        services.add(controller)
        return (controller.get().onCreateEngine() as ShimmerWallpaperService.ShimmerWallpaperEngine).also { engines.add(it) }
    }

    @After fun cleanup() {
        engines.forEach { it.onDestroy() }
        services.forEach { it.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        P0Diagnostics.setEnabled(false)
        listOf("INSTANCE", "LOCK_INSTANCE").forEach { name ->
            ShimmerDatabase::class.java.getDeclaredField(name).apply {
                isAccessible = true
                (get(null) as? ShimmerDatabase)?.close()
                set(null, null)
            }
        }
    }

    @Test fun productionDependenciesBindToTheFixedServiceRole() {
        WallpaperTarget.entries.forEach { target ->
            val e = engine(target)
            assertEquals(target, field<WallpaperPreferences>(e, "preferences").target)
            assertEquals(target, field<ImageFolderRepository>(e, "folderRepository").target)
            assertEquals(target, field<PlaybackSnapshotStore>(e, "playbackSnapshotStore").target)
            assertEquals(target, field<ScheduleCoordinator>(e, "schedule").wallpaperTarget)
            // Different preference files are also exercised by TargetDomainPreferencesTest.
            assertNotNull(field<TransitionSettingsStore>(e, "transitionStore"))
            assertFalse(field<Boolean>(e, "runtimeInitialized"))
        }
    }

    @Test fun lockAssignedSharedRejectsBeforeRendererOrBridgeRegistration() {
        FlagsShadow.flags = 3
        val e = engine(WallpaperTarget.LOCK)
        e.onCreate(SurfaceView(context).holder)
        assertFalse(field<Boolean>(e, "routeAllowed"))
        assertFalse(field<Boolean>(e, "runtimeInitialized"))
        assertNull(field<Any?>(e, "renderer"))
        assertEquals(PlaybackSelectionBridge.Result.NO_ENGINE, PlaybackSelectionBridge.submit("same.png", WallpaperTarget.LOCK))
        assertNull(LiveEditBridge.find(field<String>(e, "diagnosticId"), WallpaperTarget.LOCK))
        assertNull(PlaybackSnapshotStore(context, WallpaperTarget.LOCK).load())
    }

    @Test fun homeAssignedOnlyLockRejectsInsteadOfWritingHomeHistory() {
        FlagsShadow.flags = 2
        val e = engine(WallpaperTarget.HOME)
        e.onCreate(SurfaceView(context).holder)
        e.onVisibilityChanged(true)
        assertFalse(field<Boolean>(e, "routeAllowed"))
        assertFalse(field<Boolean>(e, "engineVisible"))
        assertNull(field<Any?>(e, "renderer"))
        assertNull(PlaybackSnapshotStore(context).load())
    }

    @Test fun rejectedRouteReportsObservedFlagsNotTheServiceMarker() {
        P0Diagnostics.resetAndStart()
        FlagsShadow.flags = 3
        val e = engine(WallpaperTarget.LOCK)
        e.onCreate(SurfaceView(context).holder)
        val report = P0Diagnostics.export()
        assertTrue(report.contains("P10_ROUTE"))
        assertTrue(report.contains("target=LOCK flags=3 preview=false allowed=false"))
        assertFalse(field<Boolean>(e, "runtimeInitialized"))
    }

    @Test fun revokedLockCannotRestartFromVisibilityRendererCallbackOrTouch() {
        FlagsShadow.flags = 1
        val e = engine(WallpaperTarget.LOCK)
        // Simulate an admitted runtime with no GL Surface. Only production callbacks run below.
        set(e, "diagnosticAlive", true)
        set(e, "runtimeInitialized", true)
        set(e, "rendererReady", true)
        e.onWallpaperFlagsChanged(1)
        e.onVisibilityChanged(true)
        e.onRendererReady()
        val event = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        try { e.onTouchEvent(event) } finally { event.recycle() }
        assertFalse(field<Boolean>(e, "routeAllowed"))
        assertFalse(field<Boolean>(e, "engineVisible"))
        assertFalse(field<Boolean>(e, "rendererReady"))
        assertTrue(field<Boolean>(e, "routeRevoked"))
        assertFalse(field<CoroutineScope>(e, "scope").coroutineContext[Job]!!.isActive)
        FlagsShadow.flags = 2
        e.onWallpaperFlagsChanged(2)
        assertFalse("A revoked Engine needs a fresh system application, not reuse of cancelled jobs", field<Boolean>(e, "routeAllowed"))
        assertNull(field<Job?>(e, "imageCycleJob"))
        assertNull(PlaybackSnapshotStore(context, WallpaperTarget.LOCK).load())
    }

    @Test fun lockCannotEnterTransparentHomeEditorEvenWithReadyRenderer() {
        val e = engine(WallpaperTarget.LOCK)
        set(e, "rendererReady", true)
        assertNull(e.beginEdit())
        e.javaClass.getDeclaredMethod("openEditor").apply { isAccessible = true; invoke(e) }
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test fun homeEditingTicketIsRevokedWithTheSystemTarget() {
        val e = engine(WallpaperTarget.HOME)
        val session = editSession(e)
        val id = field<String>(e, "diagnosticId")
        LiveEditBridge.register(id, e, WallpaperTarget.HOME)
        assertTrue(session.active)
        set(e, "diagnosticAlive", true)
        FlagsShadow.flags = 2
        e.onWallpaperFlagsChanged(2)
        assertFalse(session.active)
        assertNull(LiveEditBridge.find(id, WallpaperTarget.HOME))
        session.preview(ImageTransform(userScale = 3f), 0)
        session.apply(ImageTransform(userScale = 3f))
        session.save(ImageTransform(userScale = 3f), 0)
        assertNull(PlaybackSnapshotStore(context).loadImageTransform("file:///same.png"))
    }

    @Test fun queuedSaveLosesItsTicketBeforeItCanWrite() {
        val e = engine(WallpaperTarget.HOME)
        field<CoroutineScope>(e, "scope").cancel()
        val dispatcher = QueuedDispatcher()
        val queuedScope = CoroutineScope(SupervisorJob() + dispatcher)
        set(e, "scope", queuedScope)
        val session = editSession(e)
        val store = PlaybackSnapshotStore(context)
        val old = ImageTransform(userScale = 1.5f)
        store.commitImageTransform(session.image.imageId, old)
        session.save(ImageTransform(userScale = 3f), 0)
        assertFalse(session.active)
        set(e, "diagnosticAlive", true)
        FlagsShadow.flags = 2
        e.onWallpaperFlagsChanged(2)
        while (dispatcher.runOne()) Unit
        assertEquals(old, store.loadImageTransform(session.image.imageId))
        assertNull(field<Any?>(e, "editSession"))
    }

    @Test fun previewCustomGestureCannotWriteGlobalAppearance() {
        FlagsShadow.preview = true
        val e = engine(WallpaperTarget.HOME)
        val prefs = field<WallpaperPreferences>(e, "preferences")
        val before = prefs.getDuotonePresetIndex()
        set(e, "gestureActionMap", mapOf(TapGesture.TWO_FINGER_DOUBLE_TAP to GestureAction.RANDOM_DUOTONE))
        e.javaClass.getDeclaredMethod("handleGesture", TapGesture::class.java).apply {
            isAccessible = true
            invoke(e, TapGesture.TWO_FINGER_DOUBLE_TAP)
        }
        assertEquals(before, prefs.getDuotonePresetIndex())
        assertNull(PlaybackSnapshotStore(context).load())
    }

    private fun editSession(e: ShimmerWallpaperService.ShimmerWallpaperEngine): LiveEditBridge.EditSession {
        val type = e.javaClass.declaredClasses.single { it.simpleName == "EngineEditSession" }
        val session = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(e,
            LiveEditBridge.EditImage("file:///same.png", 100, 200, 100, 200, ImageTransform(), P3Defaults())) as LiveEditBridge.EditSession
        set(e, "editSession", session)
        return session
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(e: Any, name: String): T =
        e.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(e) as T

    private fun set(e: Any, name: String, value: Any) {
        e.javaClass.getDeclaredField(name).apply { isAccessible = true; set(e, value) }
    }

    @Implements(WallpaperService.Engine::class)
    class FlagsShadow {
        companion object { var flags = 2; var preview = false }
        @Implementation fun isPreview(): Boolean = preview
        @Implementation fun getWallpaperFlags(): Int = flags
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runOne(): Boolean {
            val next = queue.poll() ?: return false
            next.run()
            return true
        }
    }
}
