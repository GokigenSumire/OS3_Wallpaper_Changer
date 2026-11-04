package dev.abdus.apps.shimmer.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class MediaFrameState { PREPARING, VIDEO, STILL, ENDED, FALLBACK_ERROR, FALLBACK_HDR, FALLBACK_BUSY, FALLBACK_TIMEOUT }

/** One renderer's control plane. GL never waits for its main-looper player or vice versa. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlaybackOwner(
    context: Context,
    private val requestRender: () -> Unit,
    private val playerFactory: (Context, androidx.media3.datasource.DataSource.Factory) -> Player = { app, source ->
        ExoPlayer.Builder(app).setLooper(Looper.getMainLooper()).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
    },
) {
    private val app = context.applicationContext
    private val allowed = AtomicBoolean(true)
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private val restartPolicy = MediaRestartPolicy()
    companion object {
        const val START_TIMEOUT_MS = 8_000L
        private val main = Handler(Looper.getMainLooper())
        private val budget = MediaDecoderBudget(2)
        private val waiting = LinkedHashSet<Session>()
        private val owners = Collections.newSetFromMap(WeakHashMap<MediaPlaybackOwner, Boolean>())
        private val transientPermits = Semaphore(2)
        private var transientHolds = 0
        /** Poster/thumbnail decoding yields continuous decoders; all app video leases stay <= 2. */
        suspend fun <T> withTransientDecoder(block: suspend () -> T): T = transientPermits.withPermit {
            val lease = Any()
            var held = false
            var acquired = false
            try {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    transientHolds++
                    held = true
                    synchronized(owners) { owners.toList() }.forEach { owner ->
                        owner.sessions.forEach { it.visibilityChanged() }
                    }
                    check(budget.acquire(lease)) { "Transient video decoder budget exhausted" }
                    acquired = true
                }
                withContext(Dispatchers.IO) { block() }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (acquired) budget.release(lease)
                    if (held) transientHolds--
                    if (held && transientHolds == 0) synchronized(owners) { owners.toList() }.forEach { owner ->
                        owner.sessions.forEach { it.visibilityChanged() }
                    }
                }
            }
        }
        fun settingsChanged() { onMain { synchronized(owners) { owners.toList() }.forEach { owner ->
            owner.sessions.forEach { it.updateMode() }
        } } }
        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
        }
        private fun released(session: Session) {
            budget.release(session)
            waiting.remove(session)
            waiting.toList().forEach { it.reconcile() }
        }
    }
    init { synchronized(owners) { owners.add(this) } }
    fun setAllowed(value: Boolean) {
        allowed.set(value)
        onMain { sessions.forEach { it.visibilityChanged() } }
    }
    fun observeKeyguard(locked: Boolean) { onMain {
        if (restartPolicy.observe(locked, MediaPreferences.restartOnLock(app), MediaPreferences.restartOnUnlock(app))) {
            sessions.forEach { it.restartFromBeginning() }
        }
    } }
    fun open(clip: MediaClip, surface: Surface): Session = Session(clip, surface).also {
        sessions.add(it)
        onMain { it.updateMode() }
    }

    inner class Session internal constructor(val clip: MediaClip, private val surface: Surface) {
        @Volatile var state: MediaFrameState = MediaFrameState.PREPARING
            private set
        @Volatile var mode: MotionMode = MotionMode.LOOP
            private set
        @Volatile var videoAspect: Float = if (clip.videoHeight > 0) clip.videoWidth.toFloat() / clip.videoHeight else 0f
            private set
        @Volatile var outputGeneration: Long = 0
            private set
        @Volatile var producerReady: Boolean = false
            private set
        @Volatile var transientlyPaused: Boolean = false
            private set
        private val closed = AtomicBoolean(false)
        private var player: Player? = null
        private var position = 0L
        private var hasFrame = false
        private val timeout = Runnable {
            if (!closed.get() && allowed.get() && !hasFrame && player != null) {
                state = MediaFrameState.FALLBACK_TIMEOUT
                transientlyPaused = false
                releasePlayer()
                requestRender()
            }
        }
        internal fun updateMode() {
            if (closed.get()) return
            val next = if (clip.kind == MediaKind.MOTION_PHOTO) MediaPreferences.motionMode(app) else MotionMode.LOOP
            val changed = next != mode
            mode = next
            if (changed) {
                position = 0
                hasFrame = false
                releasePlayer(savePosition = false)
                state = MediaFrameState.PREPARING
            }
            if (next == MotionMode.STILL) {
                transientlyPaused = false
                state = MediaFrameState.STILL
                releasePlayer()
                requestRender()
            } else reconcile()
        }
        internal fun visibilityChanged() {
            if (!allowed.get() || transientHolds > 0) {
                transientlyPaused = allowed.get() && transientHolds > 0 && mode != MotionMode.STILL &&
                    (state == MediaFrameState.VIDEO || transientlyPaused)
                releasePlayer(); waiting.remove(this)
            }
            else {
                if (state.name.startsWith("FALLBACK_")) { state = MediaFrameState.PREPARING; hasFrame = false }
                updateMode()
            }
        }
        internal fun restartFromBeginning() {
            if (closed.get() || mode == MotionMode.STILL) return
            releasePlayer(savePosition = false)
            position = 0L
            hasFrame = false
            transientlyPaused = false
            state = MediaFrameState.PREPARING
            reconcile() // Retains zero position while hidden; never bypasses the decode budget.
            requestRender()
        }
        internal fun reconcile() {
            if (closed.get() || !allowed.get() || transientHolds > 0 || mode == MotionMode.STILL || state == MediaFrameState.ENDED) return
            if (clip.hdr) { state = MediaFrameState.FALLBACK_HDR; requestRender(); return }
            if (player != null || state in setOf(MediaFrameState.FALLBACK_ERROR, MediaFrameState.FALLBACK_TIMEOUT)) return
            if (!budget.acquire(this)) {
                state = MediaFrameState.FALLBACK_BUSY
                waiting.add(this)
                requestRender()
                return
            }
            waiting.remove(this)
            state = MediaFrameState.PREPARING
            hasFrame = false
            outputGeneration++
            producerReady = false
            try {
                val factory = DefaultDataSource.Factory(app)
                val dataSource = androidx.media3.datasource.DataSource.Factory {
                    val source = factory.createDataSource()
                    clip.range?.let { ClipDataSource(source, it) } ?: source
                }
                val instance = playerFactory(app, dataSource)
                player = instance
                instance.trackSelectionParameters = instance.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                instance.repeatMode = if (mode == MotionMode.LOOP) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                instance.volume = 0f
                instance.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (player !== instance || closed.get() || !allowed.get()) return
                        producerReady = true
                        transientlyPaused = false
                        requestRender()
                    }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        if (player !== instance || closed.get()) return
                        val hdr = tracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
                                group.isTrackSelected(index) && group.getTrackFormat(index).colorInfo?.colorTransfer in
                                    setOf(C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_ST2084)
                            }
                        }
                        if (hdr) {
                            transientlyPaused = false
                            state = MediaFrameState.FALLBACK_HDR
                            releasePlayer()
                            requestRender()
                        }
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (player === instance && videoSize.width > 0 && videoSize.height > 0) {
                            videoAspect = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (player !== instance || closed.get()) return
                        state = MediaFrameState.FALLBACK_ERROR
                        transientlyPaused = false
                        releasePlayer()
                        requestRender()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (player !== instance || closed.get()) return
                        if (playbackState == Player.STATE_ENDED && mode == MotionMode.ONCE) {
                            transientlyPaused = false
                            state = MediaFrameState.ENDED
                            releasePlayer(savePosition = false)
                            requestRender()
                        }
                    }
                })
                instance.setVideoSurface(surface)
                val item = MediaItem.Builder().setUri(clip.uri)
                if (clip.range != null) item.setMimeType(MimeTypes.VIDEO_MP4)
                instance.setMediaItem(item.build())
                instance.seekTo(position)
                instance.prepare()
                instance.playWhenReady = true
                main.postDelayed(timeout, START_TIMEOUT_MS)
            } catch (_: RuntimeException) {
                state = MediaFrameState.FALLBACK_ERROR
                transientlyPaused = false
                releasePlayer()
                requestRender()
            }
        }
        /** A decoder callback is insufficient. This is called only by the consumer GL draw. */
        fun frameConsumed(generation: Long = outputGeneration) { onMain {
            if (!closed.get() && allowed.get() && mode != MotionMode.STILL && player != null &&
                producerReady && generation == outputGeneration) {
                hasFrame = true; state = MediaFrameState.VIDEO; main.removeCallbacks(timeout)
            }
        } }
        fun close(afterDetach: () -> Unit = {}) {
            if (!closed.compareAndSet(false, true)) { onMain(afterDetach); return }
            sessions.remove(this)
            onMain {
                waiting.remove(this)
                try { releasePlayer() } finally { afterDetach() }
            }
        }
        private fun releasePlayer(savePosition: Boolean = true) {
            main.removeCallbacks(timeout)
            val old = player
            player = null
            producerReady = false
            if (old != null) outputGeneration++
            if (old != null) {
                try {
                    if (savePosition) position = old.currentPosition.coerceAtLeast(0)
                    old.clearVideoSurface(surface)
                } finally { try { old.release() } finally { released(this) } }
            } else released(this)
        }
    }
}
