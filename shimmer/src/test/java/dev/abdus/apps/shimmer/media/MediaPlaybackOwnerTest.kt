package dev.abdus.apps.shimmer.media

import android.content.Context
import android.os.Looper
import android.view.Surface
import android.graphics.SurfaceTexture
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlaybackOwnerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val sessions = mutableListOf<MediaPlaybackOwner.Session>()
    private val surfaces = mutableListOf<Surface>()
    private val textures = mutableListOf<SurfaceTexture>()
    private val players = mutableListOf<Fake>()
    private var renders = 0
    private fun owner() = MediaPlaybackOwner(context, { renders++ }) { _, _ -> Fake().also(players::add).proxy }
    private fun open(owner: MediaPlaybackOwner, motion: Boolean = false, hdr: Boolean = false): MediaPlaybackOwner.Session {
        val surface = Surface(SurfaceTexture(0).also(textures::add)).also(surfaces::add)
        return owner.open(MediaClip("content://tests/original", if (motion) MediaKind.MOTION_PHOTO else MediaKind.VIDEO, hdr = hdr), surface).also(sessions::add)
    }
    @Before fun resetMode() {
        MediaPreferences.setMotionMode(context, MotionMode.LOOP)
        MediaPreferences.setRestartOnLock(context, false)
        MediaPreferences.setRestartOnUnlock(context, false)
    }
    @After fun close() {
        sessions.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
        surfaces.forEach { it.release() }
        textures.forEach { it.release() }
    }
    @Test fun audioDisabledAndOnlyConsumedFrameMarksVideo() {
        val session = open(owner())
        assertEquals(MediaFrameState.PREPARING, session.state)
        val p = players.single()
        assertTrue(p.parameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_AUDIO))
        assertEquals(Player.REPEAT_MODE_ONE, p.repeat)
        p.firstFrame()
        assertEquals(MediaFrameState.PREPARING, session.state)
        session.frameConsumed()
        assertEquals(MediaFrameState.VIDEO, session.state)
    }
    @Test fun noFrameTimesOutReleasesCodecAndRequestsFallbackDraw() {
        val session = open(owner())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MediaPlaybackOwner.START_TIMEOUT_MS))
        assertEquals(MediaFrameState.FALLBACK_TIMEOUT, session.state)
        assertTrue(players.single().released)
        assertTrue(renders > 0)
        session.frameConsumed()
        assertEquals(MediaFrameState.FALLBACK_TIMEOUT, session.state)
    }
    @Test fun consumedFrameCancelsStartupTimeout() {
        val session = open(owner())
        players.single().firstFrame()
        session.frameConsumed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MediaPlaybackOwner.START_TIMEOUT_MS + 1))
        assertEquals(MediaFrameState.VIDEO, session.state)
        assertFalse(players.single().released)
    }
    @Test fun invisibleReleasesAndResumeGetsNewPlayerAtSavedPosition() {
        val owner = owner(); val session = open(owner)
        val old = players.single(); old.position = 1234
        owner.setAllowed(false)
        assertTrue(old.released)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9000))
        owner.setAllowed(true)
        assertEquals(2, players.size)
        assertEquals(1234L, players.last().seek)
        old.fail()
        assertEquals(MediaFrameState.PREPARING, session.state)
    }
    @Test fun lockingResetsHiddenPositionOnceWithoutCreatingAHiddenPlayer() {
        MediaPreferences.setRestartOnLock(context, true)
        val owner = owner(); owner.observeKeyguard(false)
        val session = open(owner); val first = players.single()
        first.position = 4200
        owner.setAllowed(false)
        owner.observeKeyguard(true); owner.observeKeyguard(true)
        assertEquals(1, players.size)
        owner.setAllowed(true)
        assertEquals(0L, players.last().seek)
        assertEquals(2, players.size)
        owner.observeKeyguard(true)
        assertEquals(2, players.size)
        assertEquals("content://tests/original", session.clip.uri)
    }
    @Test fun unlockSettingIsIndependentAndRejectsOldOutputAfterRestart() {
        MediaPreferences.setRestartOnUnlock(context, true)
        val owner = owner(); owner.observeKeyguard(true)
        val session = open(owner); val old = players.single(); val generation = session.outputGeneration
        old.firstFrame(); session.frameConsumed(); old.position = 900
        owner.observeKeyguard(false)
        assertTrue(old.released)
        assertEquals(0L, players.last().seek)
        assertEquals(MediaFrameState.PREPARING, session.state)
        old.firstFrame(); session.frameConsumed(generation)
        assertEquals(MediaFrameState.PREPARING, session.state)
        owner.observeKeyguard(false)
        assertEquals(2, players.size)
    }
    @Test fun eventsWithBothOptionsOffRetainNormalSavedPosition() {
        val owner = owner(); owner.observeKeyguard(false); open(owner)
        players.single().position = 650
        owner.setAllowed(false); owner.observeKeyguard(true); owner.observeKeyguard(false)
        owner.setAllowed(true)
        assertEquals(650L, players.last().seek)
    }
    @Test fun eventCanReplayAnEndedOncePhotoButNeverCreatesAStillDecoder() {
        MediaPreferences.setRestartOnUnlock(context, true)
        MediaPreferences.setMotionMode(context, MotionMode.ONCE)
        val owner = owner(); owner.observeKeyguard(true)
        val session = open(owner, motion = true)
        players.single().ended()
        assertEquals(MediaFrameState.ENDED, session.state)
        owner.observeKeyguard(false)
        assertEquals(0L, players.last().seek)
        assertEquals(MediaFrameState.PREPARING, session.state)
        MediaPreferences.setMotionMode(context, MotionMode.STILL)
        val count = players.size
        owner.observeKeyguard(true); owner.observeKeyguard(false)
        assertEquals(count, players.size)
        assertEquals(MediaFrameState.STILL, session.state)
    }
    @Test fun stillAndOnceModesPreserveOneAssetAndReleaseOnEnd() {
        val owner = owner(); val session = open(owner, motion = true)
        MediaPreferences.setMotionMode(context, MotionMode.STILL)
        assertEquals(MediaFrameState.STILL, session.state)
        assertTrue(players.first().released)
        session.frameConsumed()
        assertEquals(MediaFrameState.STILL, session.state)
        MediaPreferences.setMotionMode(context, MotionMode.ONCE)
        assertEquals(Player.REPEAT_MODE_OFF, players.last().repeat)
        players.last().ended()
        assertEquals(MediaFrameState.ENDED, session.state)
        assertTrue(players.last().released)
    }
    @Test fun thirdDecoderWaitsAndIsWokenWhenSlotReleased() {
        val a = open(owner()); open(owner()); val c = open(owner())
        assertEquals(2, players.size)
        assertEquals(MediaFrameState.FALLBACK_BUSY, c.state)
        a.close()
        assertEquals(3, players.size)
        assertEquals(MediaFrameState.PREPARING, c.state)
    }
    @Test fun closedOrHdrSessionCannotCreateAPlayer() {
        val owner = owner(); val session = open(owner, hdr = true)
        assertEquals(MediaFrameState.FALLBACK_HDR, session.state)
        assertTrue(players.isEmpty())
        session.close()
        owner.setAllowed(false); owner.setAllowed(true)
        assertTrue(players.isEmpty())
    }
    @Test fun decoderFailureAndLateCallbackDoNotReviveReleasedSession() {
        val session = open(owner())
        val old = players.single()
        old.fail()
        assertEquals(MediaFrameState.FALLBACK_ERROR, session.state)
        assertTrue(old.released)
        session.frameConsumed()
        assertEquals(MediaFrameState.FALLBACK_ERROR, session.state)
        session.close()
        old.ended()
        assertEquals(MediaFrameState.FALLBACK_ERROR, session.state)
    }
    @Test fun resumeRequiresFreshFramesForItsOwnStartupDeadline() {
        val owner = owner(); val session = open(owner)
        players.single().firstFrame()
        session.frameConsumed()
        owner.setAllowed(false); owner.setAllowed(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MediaPlaybackOwner.START_TIMEOUT_MS))
        assertEquals(MediaFrameState.FALLBACK_TIMEOUT, session.state)
    }
    @Test fun failedPlayerConstructionDoesNotLeakDecoderLease() {
        val owner = MediaPlaybackOwner(context, {}) { _, _ -> throw IllegalStateException("allocation") }
        assertEquals(MediaFrameState.FALLBACK_ERROR, open(owner).state)
        open(owner()); open(owner())
        assertEquals(2, players.size)
    }
    @Test fun previousOutputBuffersCannotAdmitNewPlayerOrCancelItsDeadline() {
        val owner = owner(); val session = open(owner)
        val old = players.single(); val generation = session.outputGeneration
        old.firstFrame(); session.frameConsumed(generation)
        assertEquals(MediaFrameState.VIDEO, session.state)
        owner.setAllowed(false); owner.setAllowed(true)
        assertTrue(session.outputGeneration > generation)
        old.firstFrame(); session.frameConsumed(generation)
        assertEquals(MediaFrameState.PREPARING, session.state)
        players.last().firstFrame()
        session.frameConsumed(generation)
        assertEquals(MediaFrameState.PREPARING, session.state)
        session.frameConsumed(session.outputGeneration)
        assertEquals(MediaFrameState.VIDEO, session.state)
    }
    @Test fun actualHdrTrackOverridesMissingRetrieverHdrMetadata() {
        val session = open(owner())
        val fake = players.single()
        val format = androidx.media3.common.Format.Builder().setSampleMimeType("video/hevc")
            .setColorInfo(androidx.media3.common.ColorInfo.Builder()
                .setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_HLG).build()).build()
        val group = androidx.media3.common.Tracks.Group(androidx.media3.common.TrackGroup(format), false,
            intArrayOf(androidx.media3.common.C.FORMAT_HANDLED), booleanArrayOf(true))
        val tracks = androidx.media3.common.Tracks(listOf(group))
        fake.listeners.forEach { it.onTracksChanged(tracks) }
        assertEquals(MediaFrameState.FALLBACK_HDR, session.state)
        assertTrue(fake.released)
        fake.firstFrame(); session.frameConsumed()
        assertEquals(MediaFrameState.FALLBACK_HDR, session.state)
    }
    private fun <T> background(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val task = scope.async { block() }
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!task.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
            assertTrue("Background operation did not complete", task.isCompleted)
            return runBlocking { task.await() }
        } finally { scope.cancel() }
    }
    @Test fun posterExtractionYieldsBothContinuousDecodersAndRestoresThem() {
        open(owner()); open(owner())
        assertEquals(2, players.size)
        background {
            MediaPlaybackOwner.withTransientDecoder {
                assertTrue(players.take(2).all { it.released })
                assertEquals(2, players.size)
            }
        }
        assertEquals(4, players.size)
        assertTrue(players.take(2).all { it.released })
        assertTrue(players.takeLast(2).none { it.released })
    }
    @Test fun failedTransientDecodeDoesNotLeaveContinuousPlaybackSuspended() {
        open(owner())
        assertThrows(java.io.IOException::class.java) {
            background { MediaPlaybackOwner.withTransientDecoder<Unit> { throw java.io.IOException("bad frame") } }
        }
        assertEquals(2, players.size)
        assertTrue(players.first().released)
        assertFalse(players.last().released)
    }
    @Test fun nestedTransientLeasesDoNotResumeUntilTheLastOneEnds() {
        open(owner())
        background {
            MediaPlaybackOwner.withTransientDecoder {
                MediaPlaybackOwner.withTransientDecoder { assertEquals(1, players.size) }
                assertEquals(1, players.size)
            }
        }
        assertEquals(2, players.size)
    }

    private class Fake {
        val listeners = mutableListOf<Player.Listener>()
        var parameters = TrackSelectionParameters.Builder().build()
        var repeat = Player.REPEAT_MODE_OFF
        var released = false
        var position = 0L
        var seek = -1L
        val proxy = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> { parameters = args!![0] as TrackSelectionParameters; null }
                "setRepeatMode" -> { repeat = args!![0] as Int; null }
                "getCurrentPosition" -> position
                "seekTo" -> { seek = args!!.last() as Long; null }
                "release" -> { released = true; null }
                else -> null
            }
        } as Player
        fun ended() { listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) } }
        fun firstFrame() { listeners.toList().forEach { it.onRenderedFirstFrame() } }
        fun fail() { listeners.toList().forEach { it.onPlayerError(PlaybackException("test", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)) } }
    }
}
