package dev.abdus.apps.shimmer.p10.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p0.P0Diagnostics
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p2.PlaybackCoordinator
import dev.abdus.apps.shimmer.p2.PlaybackDirection
import dev.abdus.apps.shimmer.p2.PlaybackImageResolver
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.p2.withoutTransformsFor
import dev.abdus.apps.shimmer.p2.forPersistence
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p3.TransformResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One formal LOCK Engine owns this controller. Public methods run on Main, while catalog,
 * decoding and preference work run off Main. No path needs engineVisible or an Engine GL queue:
 * authentication hides the formal surface, and the editor owns its own pixels/context.
 */
class LockEngineEditController(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val scope: CoroutineScope,
    private val repository: ImageFolderRepository,
    private val preferences: WallpaperPreferences,
    private val store: PlaybackSnapshotStore,
    private val capture: () -> Captured?,
    private val isEligible: () -> Boolean,
    private val acquireHold: () -> Boolean,
    private val releaseHold: () -> Unit,
    private val decodeInfo: suspend (Uri, ImageTransform) -> DecodedInfo?,
    private val publicationGuard: ((() -> Boolean) -> Boolean) = { publish -> publish() },
) : LockEditorBridge.Target {
    data class Captured(val image: LiveEditBridge.EditImage, val uri: Uri)
    /** Upright original dimensions, not the sampled bitmap dimensions. */
    data class DecodedInfo(val width: Int, val height: Int)

    private enum class Phase { PENDING, EDITING, SAVING, CLOSED }
    private inner class Owner(val epoch: Long, val captured: Captured, initial: PlaybackSnapshot) {
        @Volatile var phase = Phase.PENDING
        @Volatile var expected: PlaybackSnapshot = initial
        @Volatile var writing = false
        @Volatile var released = false
        var operation: Job? = null
        var timeout: Job? = null
        var busy = false
        lateinit var session: Session
        val deadline = SystemClock.elapsedRealtime() + LockEditAuthentication.MAX_AUTH_MILLIS
    }

    @Volatile private var epoch = 0L
    @Volatile private var closed = false
    @Volatile private var owner: Owner? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scopeCompletion: DisposableHandle? = null

    init {
        require(repository.target == WallpaperTarget.LOCK && preferences.target == WallpaperTarget.LOCK &&
            store.target == WallpaperTarget.LOCK) { "LOCK editor requires explicit LOCK repositories and stores" }
        scopeCompletion = scope.coroutineContext[Job]?.invokeOnCompletion {
            if (Looper.myLooper() == Looper.getMainLooper()) close()
            else mainHandler.post { close() }
        }
    }

    override fun prepareLockEdit(): LockEditorBridge.Pending? {
        if (closed || owner != null || !isEligible() || !scope.isActive || !acquireHold()) return null
        val captured = try { capture() } catch (_: RuntimeException) { null }
        val expected = try { store.load() } catch (_: RuntimeException) { null }
        if (captured == null || expected == null || expected.currentId != captured.image.imageId ||
            captured.uri.toString() != captured.image.imageId ||
            captured.image.sourceWidth <= 0 || captured.image.sourceHeight <= 0 ||
            captured.image.viewWidth <= 0 || captured.image.viewHeight <= 0 || !isEligible()) {
            releaseHold()
            return null
        }
        val current = Owner(++epoch, captured, expected)
        owner = current
        current.timeout = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(LockEditAuthentication.MAX_AUTH_MILLIS)
            if (owner === current && current.phase == Phase.PENDING) finish(current)
        }
        return object : LockEditorBridge.Pending {
            override val active: Boolean get() {
                if (current.phase != Phase.PENDING) return false
                if (SystemClock.elapsedRealtime() >= current.deadline) {
                    finish(current)
                    return false
                }
                return valid(current)
            }
            override val deadlineElapsedRealtimeMillis get() = current.deadline
            override fun authenticate(): LockEditorBridge.Session? {
                if (current.phase != Phase.PENDING) return null
                if (!active || SystemClock.elapsedRealtime() >= current.deadline) {
                    finish(current)
                    return null
                }
                current.timeout?.cancel()
                current.timeout = null
                current.phase = Phase.EDITING
                return Session(current).also { current.session = it }
            }
            override fun cancel() = finish(current)
        }
    }

    /** Explicit flags/Engine/connection/catalog revocation. Expected bouncer must not call it. */
    fun invalidate() {
        epoch++
        owner?.let(::finish)
    }

    fun close() {
        if (closed) return
        closed = true
        scopeCompletion?.dispose()
        scopeCompletion = null
        invalidate()
    }

    private fun valid(current: Owner, compareSnapshot: Boolean = !current.writing): Boolean =
        !closed && owner === current && epoch == current.epoch && current.phase != Phase.CLOSED &&
            !current.released && scope.isActive && isEligible() &&
            (!compareSnapshot || store.load() == current.expected)

    private fun finish(current: Owner) {
        if (current.released) return
        current.released = true
        current.phase = Phase.CLOSED
        current.timeout?.cancel()
        current.timeout = null
        current.operation?.cancel()
        current.operation = null
        current.busy = false
        if (owner === current) owner = null
        releaseHold()
    }

    private inner class Session(private val current: Owner) : LockEditorBridge.Session, LockEditSaveResultSource {
        private var saveResultObserver: ((Boolean) -> Unit)? = null
        override fun observeSaveResult(observer: (Boolean) -> Unit) { saveResultObserver = observer }
        private fun reportSaveResult(saved: Boolean) { try { saveResultObserver?.invoke(saved) } catch (_: RuntimeException) { } }
        override var image = current.captured.image
        override val previewUri get() = Uri.parse(image.imageId)
        override val active: Boolean get() {
            val editing = current.phase == Phase.EDITING
            val live = editing && valid(current)
            // This public session query runs on Main. A stale external snapshot must release
            // the hold even if no Engine invalidation callback reached it before the UI poll.
            // SAVING is intentionally not cancelled: its revocable IO ticket owns that hold.
            if (editing && !live) finish(current)
            return live
        }
        // Private UI owns all transient drafts. No formal renderer is touched here.
        override fun preview(transform: ImageTransform, inputUptimeMillis: Long) = Unit
        override fun statusLine() = "LOCK 独立编辑；本域编辑持有 ${!current.released}；正在切换 ${current.busy}"

        override fun apply(transform: ImageTransform?) {
            if (!active || current.busy) return
            current.busy = true
            current.operation = scope.launch {
                try {
                    if (writeTransform(current, image.imageId, transform)) {
                        image = image.copy(original = (transform ?: image.defaults.toTransform()).sanitized())
                    } else finish(current)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { finish(current) }
                finally { current.busy = false }
            }
        }

        override fun save(transform: ImageTransform?, saveUptimeMillis: Long) {
            if (!active || current.busy) { reportSaveResult(false); return }
            current.phase = Phase.SAVING
            current.busy = true
            current.operation = scope.launch {
                var persistedResult: Boolean? = null
                try {
                    val saved = writeTransform(current, image.imageId, transform)
                    persistedResult = saved
                    reportSaveResult(saved)
                    P0Diagnostics.event("P10_LOCK_EDIT", if (saved) "SAVE_COMMITTED" else "SAVE_REJECTED", "reset=${transform == null}")
                } catch (cancelled: CancellationException) {
                    if (persistedResult == null) reportSaveResult(false)
                    throw cancelled
                } catch (_: Exception) {
                    if (persistedResult == null) reportSaveResult(false)
                    P0Diagnostics.event("P10_LOCK_EDIT", "SAVE_REJECTED", "reason=STORAGE")
                } finally { finish(current) }
            }
        }

        override fun cancel() = finish(current)

        override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
            if (!active || current.busy) { onDone(null); return }
            current.busy = true
            current.operation = scope.launch {
                try {
                    if (saveFirst) {
                        if (!writeTransform(current, image.imageId, transform)) {
                            finish(current)
                            onDone(null)
                            return@launch
                        }
                        image = image.copy(original = (transform ?: image.defaults.toTransform()).sanitized())
                    }
                    currentCoroutineContext().ensureActive()
                    if (!active) { finish(current); onDone(null); return@launch }
                    val expected = current.expected
                    val before = image
                    val defaults = preferences.getP3Defaults()
                    val catalog = repository.getPlaybackCatalog()
                    val result = withContext(Dispatchers.Default) {
                        currentCoroutineContext().ensureActive()
                        if (!valid(current)) return@withContext null
                        val coordinator = PlaybackCoordinator(randomSeed = 0x504C41594241434BL)
                        coordinator.setItemsAndRestore(catalog.items, expected)
                        val loaded = PlaybackImageResolver<Pair<DecodedInfo, dev.abdus.apps.shimmer.p3.ResolvedTransform>>(coordinator)
                            .resolveRequest(if (forward) PlaybackDirection.NEXT else PlaybackDirection.PREVIOUS) { request, item ->
                                if (item.id == before.imageId) return@resolveRequest null
                                val placement = TransformResolution.resolve(request.freshAdvance, request.entryTransform,
                                    store.loadImageTransform(item.id), defaults, previousDisplayed = before.original)
                                val decoded = decodeInfo(Uri.parse(item.id), placement.transform) ?: return@resolveRequest null
                                if (decoded.width <= 0 || decoded.height <= 0) return@resolveRequest null
                                val fitted = TransformResolution.fitInherited(placement, decoded.width, decoded.height,
                                    before.viewWidth, before.viewHeight)
                                decoded to TransformResolution.adaptFit(fitted, defaults, decoded.width, decoded.height,
                                    before.viewWidth, before.viewHeight)
                            } ?: return@withContext LiveEditBridge.StepResult.NoCandidate
                        currentCoroutineContext().ensureActive()
                        if (!valid(current) || !coordinator.commit(loaded.request.version,
                                loaded.image.second.transform.takeIf { loaded.image.second.annotateHistory })) return@withContext null
                        val next = coordinator.snapshot()
                        val task = currentCoroutineContext()[Job]
                        current.writing = true
                        val accepted = try {
                            withContext(Dispatchers.IO) {
                                store.compareAndSaveIfAllowed(expected, next, publicationGuard) {
                                    task?.isActive == true && valid(current, compareSnapshot = false) && current.expected == expected
                                }
                            }.also { if (it) current.expected = next.forPersistence() }
                        } finally { current.writing = false }
                        if (!accepted || !valid(current)) return@withContext null
                        val item = loaded.request.item ?: return@withContext null
                        val (dimensions, resolved) = loaded.image
                        LiveEditBridge.StepResult.Success(LiveEditBridge.EditImage(item.id, dimensions.width, dimensions.height,
                            before.viewWidth, before.viewHeight, resolved.transform,
                            defaults.adaptedTo(dimensions.width, dimensions.height, before.viewWidth, before.viewHeight),
                            enabled = item.id !in preferences.getDisabledImages()))
                    }
                    currentCoroutineContext().ensureActive()
                    if (!active) { finish(current); onDone(null); return@launch }
                    if (result is LiveEditBridge.StepResult.Success) image = result.image
                    if (result == null) finish(current)
                    onDone(result)
                } catch (cancelled: CancellationException) {
                    onDone(null)
                    throw cancelled
                } catch (_: Exception) {
                    onDone(LiveEditBridge.StepResult.Failed("图片暂时无法读取，本次未切换"))
                } finally { current.busy = false }
            }
        }
    }

    private suspend fun writeTransform(current: Owner, imageId: String, transform: ImageTransform?): Boolean {
        currentCoroutineContext().ensureActive()
        if (!valid(current)) return false
        val expected = current.expected
        val task = currentCoroutineContext()[Job]
        current.writing = true
        return try {
            withContext(Dispatchers.IO) {
                val saved = store.commitImageTransformIfAllowed(imageId, transform?.sanitized(), publicationGuard) {
                    task?.isActive == true && valid(current, compareSnapshot = false) && store.load() == expected
                }
                if (saved) current.expected = expected.withoutTransformsFor(imageId)
                saved
            }
        } finally { current.writing = false }
    }
}
