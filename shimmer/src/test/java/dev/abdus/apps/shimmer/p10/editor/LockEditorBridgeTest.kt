package dev.abdus.apps.shimmer.p10.editor

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.LiveEditBridge
import dev.abdus.apps.shimmer.p3.P3Defaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LockEditorBridgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = "lock-test-exact-engine"

    private class Session : LockEditorBridge.Session, LockEditSaveResultSource {
        private var resultObserver: ((Boolean) -> Unit)? = null
        override fun observeSaveResult(observer: (Boolean) -> Unit) { resultObserver = observer }
        fun committedForTest(saved: Boolean) { resultObserver?.invoke(saved) }
        override var image = LiveEditBridge.EditImage("content://private/LOCK-image", 400, 600, 1200, 2400,
            ImageTransform(), P3Defaults())
        override val previewUri: Uri = Uri.parse(image.imageId)
        var open = true
        var cancels = 0
        override val active get() = open
        override fun preview(transform: ImageTransform, inputUptimeMillis: Long) = Unit
        override fun apply(transform: ImageTransform?) = Unit
        override fun save(transform: ImageTransform?, saveUptimeMillis: Long) { open = false }
        override fun cancel() { cancels++; open = false }
        override fun statusLine() = "LOCK"
        override fun step(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?, onDone: (LiveEditBridge.StepResult?) -> Unit) {
            onDone(LiveEditBridge.StepResult.NoCandidate)
        }
    }

    private class Pending(duration: Long = 60_000L) : LockEditorBridge.Pending {
        var valid = true
        var cancels = 0
        var authentications = 0
        val session = Session()
        var rejectAuthentication = false
        override val active get() = valid
        override val deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + duration
        override fun authenticate(): LockEditorBridge.Session? {
            authentications++
            if (!valid || rejectAuthentication) return null
            valid = false // Hold transferred: the pending ticket is now intentionally consumed.
            return session
        }
        override fun cancel() { valid = false; cancels++ }
    }

    @After fun clear() {
        LockEditorBridge.unregister(engine)
        LockEditorBridge.unregister("other-engine")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun publish(pending: Pending): String {
        assertTrue(LockEditorBridge.launchPrepared(context, pending, engine))
        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(LockEditEntryActivity::class.java.name, intent.component!!.className)
        assertFalse(intent.hasExtra("uri"))
        assertFalse(intent.toUri(0).contains("private/LOCK-image"))
        return intent.getStringExtra(LockEditorBridge.EXTRA_TOKEN)!!
    }

    @Test fun unclaimedSystemUiCapabilityReleasesHoldAtFiveSeconds() {
        val pending = Pending()
        val capability = LockEditorBridge.createPreparedPendingIntent(context, pending, engine)!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_999))
        assertEquals(0, pending.cancels)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, pending.cancels)
        assertTrue(shadowOf(capability).isCanceled)
    }

    @Test fun claimedAuthenticationKeepsOriginalDeadlineBeyondLaunchWindow() {
        val pending = Pending()
        val capability = LockEditorBridge.createPreparedPendingIntent(context, pending, engine)!!
        val token = shadowOf(capability).savedIntent.getStringExtra(LockEditorBridge.EXTRA_TOKEN)!!
        assertTrue(LockEditorBridge.beginAuthentication(token))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_001))
        assertEquals(0, pending.cancels)
        assertTrue(LockEditorBridge.pendingValid(token))
    }

    @Test fun previousLaunchTimeoutCannotCancelNewOwner() {
        val old = Pending()
        LockEditorBridge.createPreparedPendingIntent(context, old, engine)!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        LockEditorBridge.unregister(engine)
        val next = Pending()
        LockEditorBridge.createPreparedPendingIntent(context, next, engine)!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_001))
        assertEquals(1, old.cancels)
        assertEquals(0, next.cancels)
    }

    @Test fun imageIsUnavailableBeforeRealSuccessAndReadySessionConsumesOnce() {
        val pending = Pending()
        val token = publish(pending)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        // Do not call consume prematurely here: that deliberately revokes the invalid path.
        assertEquals(0, pending.authentications)
        assertTrue(LockEditorBridge.systemSucceeded(token, deviceStillLocked = false))
        assertEquals(1, pending.authentications)
        assertTrue(LockEditorBridge.pendingValid(token))
        assertNotNull(LockEditorBridge.consumeAuthenticated(token))
        assertNull(LockEditorBridge.consumeAuthenticated(token))
        assertEquals(0, pending.session.cancels)
        assertEquals(0, pending.cancels)
    }

    @Test fun prematurePrivatePreviewConsumptionCancelsWithoutAuthenticating() {
        val pending = Pending()
        val token = publish(pending)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertNull(LockEditorBridge.consumeAuthenticated(token))
        assertEquals(0, pending.authentications)
        assertEquals(1, pending.cancels)
    }

    @Test fun guessedOrStaleTokenCannotConsumeOrCancelLegitimateTicket() {
        val pending = Pending()
        val token = publish(pending)
        assertFalse(LockEditorBridge.beginAuthentication("wrong"))
        assertFalse(LockEditorBridge.systemSucceeded("wrong", false))
        assertNull(LockEditorBridge.consumeAuthenticated("wrong"))
        LockEditorBridge.cancel("wrong")
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertEquals(0, pending.cancels)
    }

    @Test fun duplicateAuthenticationCallbackCannotCreateSecondSession() {
        val pending = Pending()
        val token = publish(pending)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertFalse(LockEditorBridge.beginAuthentication(token))
        assertTrue(LockEditorBridge.systemSucceeded(token, false))
        assertFalse(LockEditorBridge.systemSucceeded(token, false))
        assertEquals(1, pending.authentications)
        assertNotNull(LockEditorBridge.consumeAuthenticated(token))
    }

    @Test fun stillLockedOrRejectedEngineAuthenticationNeverExposesSession() {
        val pending = Pending()
        val token = publish(pending)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertFalse(LockEditorBridge.systemSucceeded(token, true))
        assertEquals(0, pending.authentications)
        assertEquals(1, pending.cancels)
        assertNull(LockEditorBridge.consumeAuthenticated(token))

        val rejected = Pending().apply { rejectAuthentication = true }
        val second = publish(rejected)
        assertTrue(LockEditorBridge.beginAuthentication(second))
        assertFalse(LockEditorBridge.systemSucceeded(second, false))
        assertNull(LockEditorBridge.consumeAuthenticated(second))
    }

    @Test fun exactEngineUnregisterRevokesButOtherEngineCannotRevoke() {
        val pending = Pending()
        val token = publish(pending)
        LockEditorBridge.unregister("other-engine")
        assertTrue(LockEditorBridge.beginAuthentication(token))
        LockEditorBridge.unregister(engine)
        assertFalse(LockEditorBridge.systemSucceeded(token, false))
        assertEquals(1, pending.cancels)
    }

    @Test fun engineCannotSilentlyReplaceImageWhileAuthenticating() {
        val pending = Pending()
        val token = publish(pending)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        pending.valid = false
        assertFalse(LockEditorBridge.systemSucceeded(token, false))
        assertNull(LockEditorBridge.consumeAuthenticated(token))
        assertEquals(0, pending.authentications)
    }

    @Test fun exactEngineRequiredAndNoGlobalHomeFallback() {
        var prepared = 0
        LockEditorBridge.register(engine, object : LockEditorBridge.Target {
            override fun prepareLockEdit(): LockEditorBridge.Pending { prepared++; return Pending() }
        })
        assertFalse(LockEditorBridge.launch(context, "stale-engine"))
        assertEquals(0, prepared)
        assertTrue(LockEditorBridge.launch(context, engine))
        assertEquals(1, prepared)
    }

    @Test fun newPreparedTicketCannotDisplaceAuthenticationInProgress() {
        val original = Pending()
        val token = publish(original)
        val other = Pending()
        assertFalse(LockEditorBridge.launchPrepared(context, other, "other-engine"))
        assertEquals(1, other.cancels)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertEquals(0, original.cancels)
    }

    @Test fun invalidDeadlineDoesNotLaunchAuthentication() {
        for (duration in listOf(0L, -1L, 60_001L)) {
            val pending = Pending(duration)
            assertFalse(LockEditorBridge.launchPrepared(context, pending, engine))
            assertEquals(1, pending.cancels)
        }
    }

    @Test fun timeoutReleasesPendingHoldAndLateCallbackCannotReopen() {
        val pending = Pending(1_000L)
        val token = publish(pending)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001L))
        assertEquals(1, pending.cancels)
        assertFalse(LockEditorBridge.systemSucceeded(token, false))
        assertNull(LockEditorBridge.consumeAuthenticated(token))
    }

    @Test fun readySessionDeathIsRecheckedAtFinalHandoff() {
        val pending = Pending()
        val token = publish(pending)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertTrue(LockEditorBridge.systemSucceeded(token, false))
        pending.session.open = false
        assertNull(LockEditorBridge.consumeAuthenticated(token))
        assertEquals(1, pending.session.cancels)
    }

    @Test fun cancelAfterSuccessReleasesTransferredSessionRatherThanOldPendingHold() {
        val pending = Pending()
        val token = publish(pending)
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertTrue(LockEditorBridge.systemSucceeded(token, false))
        LockEditorBridge.cancel(token)
        assertEquals(1, pending.session.cancels)
        assertEquals(0, pending.cancels)
        assertNull(LockEditorBridge.consumeAuthenticated(token))
    }

    @Test fun systemUiCapabilityIsImmutableOneShotAndContainsOnlyEntryToken() {
        val pending = Pending()
        val capability = LockEditorBridge.createPreparedPendingIntent(context, pending, engine)!!
        val info = shadowOf(capability)
        assertTrue(info.isActivity)
        assertTrue(info.isImmutable)
        assertTrue(info.flags and android.app.PendingIntent.FLAG_ONE_SHOT != 0)
        val sealed = info.savedIntent
        assertEquals(LockEditEntryActivity::class.java.name, sealed.component!!.className)
        assertTrue(sealed.hasExtra(LockEditorBridge.EXTRA_TOKEN))
        assertFalse(sealed.toUri(0).contains("private/LOCK-image"))
        capability.send(context, 0, android.content.Intent().putExtra(LockEditorBridge.EXTRA_TOKEN, "attacker"))
        val sent = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(sealed.getStringExtra(LockEditorBridge.EXTRA_TOKEN), sent.getStringExtra(LockEditorBridge.EXTRA_TOKEN))
        assertTrue(info.isCanceled)
    }

    @Test fun cancelledEngineRevokesUnsentCapabilityAndReleasesHold() {
        val pending = Pending()
        val capability = LockEditorBridge.createPreparedPendingIntent(context, pending, engine)!!
        LockEditorBridge.unregister(engine)
        assertTrue(shadowOf(capability).isCanceled)
        assertEquals(1, pending.cancels)
    }

    @Test fun diagnosticCapturesExactDispatchAndRecordsOnlyActualSystemSuccess() {
        val ledger = LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val request = ledger.begin("owner","request")!!; val pending = Pending()
        val token = LockEditDiagnostics.withRequest(request) { publish(pending) }
        assertTrue(LockEditorBridge.beginAuthentication(token))
        assertEquals(listOf(1),ledger.read("owner",0)!!.rows.map { it.cause })
        assertTrue(LockEditorBridge.systemSucceeded(token,false))
        assertEquals(listOf(1,2),ledger.read("owner",0)!!.rows.map { it.cause })
        val session = LockEditorBridge.consumeAuthenticated(token)!!
        session.recordDiagnostic(LockEditDiagnosticCause.EDITOR_OPENED)
        session.save(ImageTransform(),1)
        assertEquals(listOf(1,2,6,7),ledger.read("owner",0)!!.rows.map { it.cause })
        pending.session.committedForTest(true)
        assertEquals(listOf(1,2,6,7,8),ledger.read("owner",0)!!.rows.map { it.cause })
    }
    @Test fun diagnosticSystemCancelAndLifecycleAbortRemainDifferent() {
        val ledger = LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val first = ledger.begin("owner","first")!!
        val token = LockEditDiagnostics.withRequest(first) { publish(Pending()) }
        LockEditorBridge.beginAuthentication(token)
        LockEditorBridge.cancel(token,LockEditDiagnosticCause.AUTH_CANCELLED)
        val second = ledger.begin("owner","second")!!
        val next = LockEditDiagnostics.withRequest(second) { publish(Pending()) }
        LockEditorBridge.beginAuthentication(next); LockEditorBridge.cancel(next)
        assertEquals(listOf(1,3,1,5),ledger.read("owner",0)!!.rows.map { it.cause })
    }
    @Test fun revokedDiagnosticOwnerDoesNotGrantOrAffectEditorAuthority() {
        val ledger = LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val request = ledger.begin("owner","request")!!; val pending = Pending()
        val token = LockEditDiagnostics.withRequest(request) { publish(pending) }
        ledger.revokeOwner("owner")
        assertTrue(LockEditorBridge.beginAuthentication(token)); assertTrue(LockEditorBridge.systemSucceeded(token,false))
        assertNotNull(LockEditorBridge.consumeAuthenticated(token)); assertNull(ledger.read("owner",0))
        assertEquals(1,pending.authentications)
    }
    @Test fun failedStorageResultNeverBecomesSavedDiagnostic() {
        val ledger = LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val request = ledger.begin("owner","request")!!; val pending = Pending()
        val token = LockEditDiagnostics.withRequest(request) { publish(pending) }
        LockEditorBridge.beginAuthentication(token); LockEditorBridge.systemSucceeded(token,false)
        val session = LockEditorBridge.consumeAuthenticated(token)!!; session.save(ImageTransform(),1)
        pending.session.committedForTest(false)
        assertTrue(ledger.read("owner",0)!!.rows.any { it.cause == LockEditDiagnosticCause.EDITOR_SAVE_REJECTED })
        assertFalse(ledger.read("owner",0)!!.rows.any { it.cause == LockEditDiagnosticCause.EDITOR_SAVED })
    }
}
