package dev.abdus.apps.shimmer.p10.editor

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LockEditEntryActivityTest {
    @Test fun duplicateEntryCannotCancelTheActivityThatOwnsAuthentication() {
        val pending = open()
        val first = controller!!.get()
        val duplicate = Robolectric.buildActivity(LockEditEntryActivity::class.java, first.intent)
            .create().start().resume()
        assertTrue(duplicate.get().isFinishing)
        duplicate.pause().stop().destroy()
        assertEquals(0, pending.cancels)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        frameworkSuccess()
        assertEquals(1, pending.authenticated)
        assertEquals(0, pending.cancels)
    }
    @Test fun frameworkSuccessMayPrecedeUnlockedAttributeWithoutLosingOriginalTicket() {
        val pending = open()
        val callbackField = org.robolectric.shadows.ShadowKeyguardManager::class.java.getDeclaredField("callback").apply { isAccessible = true }
        (callbackField.get(null) as KeyguardManager.KeyguardDismissCallback).onDismissSucceeded()
        assertFalse(controller!!.get().isFinishing)
        assertEquals(0, pending.authenticated)
        assertEquals(0, pending.cancels)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100L))
        assertEquals(1, pending.authenticated)
        assertEquals(0, pending.cancels)
        assertEquals(LockEditActivity::class.java.name, shadowOf(controller!!.get()).nextStartedActivity.component!!.className)
    }

    @Test fun unlockedAttributeWithoutFrameworkSuccessIsStillNotAuthentication() {
        val pending = open()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        controller!!.pause().resume()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100L))
        assertEquals(0, pending.authenticated)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }

    @Test fun successThenTicketRevocationDuringUnlockSyncCannotOpenEditor() {
        val pending = open()
        frameworkSuccess()
        pending.valid = false
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100L))
        assertEquals(0, pending.authenticated)
        assertEquals(1, pending.cancels)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }

    @Test fun successWaitCannotExtendOriginalAuthenticationDeadline() {
        val pending = open(100L)
        frameworkSuccess()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101L))
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100L))
        assertEquals(0, pending.authenticated)
        assertEquals(1, pending.cancels)
        assertTrue(controller!!.get().isFinishing)
    }

    @Test fun backDuringSuccessfulCallbackUnlockSyncStillCancelsAndNeverReplays() {
        val pending = open()
        frameworkSuccess()
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100L))
        assertEquals(0, pending.authenticated)
        assertEquals(1, pending.cancels)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }

    private fun frameworkSuccess() {
        val callbackField = org.robolectric.shadows.ShadowKeyguardManager::class.java.getDeclaredField("callback").apply { isAccessible = true }
        (callbackField.get(null) as KeyguardManager.KeyguardDismissCallback).onDismissSucceeded()
    }

    @Test @Config(shadows = [CredentialKeyguardShadow::class])
    fun dismissErrorOffersOneSystemCredentialConfirmationWithoutAuthenticating() {
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceSecure(true)
        val pending = open()
        val callbackField = org.robolectric.shadows.ShadowKeyguardManager::class.java.getDeclaredField("callback").apply { isAccessible = true }
        (callbackField.get(null) as KeyguardManager.KeyguardDismissCallback).onDismissError()
        val activity = controller!!.get()
        assertFalse(activity.isFinishing)
        assertEquals(0, pending.authenticated)
        assertEquals(0, pending.cancels)
        val launched = shadowOf(activity).nextStartedActivityForResult
        assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", launched.intent.action)
        assertEquals("com.android.settings", launched.intent.`package`)
        (callbackField.get(null) as KeyguardManager.KeyguardDismissCallback).onDismissError()
        val rest = generateSequence { shadowOf(activity).nextStartedActivityForResult }.take(8).toList()
        assertFalse(rest.any { it.intent.action == "android.app.action.CONFIRM_DEVICE_CREDENTIAL" })
    }

    @Test @Config(shadows = [CredentialKeyguardShadow::class])
    fun systemCredentialSuccessTransfersTheSamePendingOnlyWhenDeviceUnlocked() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        val pending = open()
        val activity = controller!!.get()
        credentialError()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", request.intent.action)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(activity).receiveResult(request.intent, android.app.Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pending.authenticated)
        assertEquals(0, pending.cancels)
        assertEquals(LockEditActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(activity.isFinishing)
    }

    @Test @Config(shadows = [CredentialKeyguardShadow::class])
    fun systemCredentialCancelOrStillLockedNeverAuthenticates() {
        for (result in listOf(android.app.Activity.RESULT_CANCELED, android.app.Activity.RESULT_OK)) {
            shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
            val pending = open()
            val activity = controller!!.get()
            credentialError()
            val request = shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).receiveResult(request.intent, result, null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100L))
            assertEquals(0, pending.authenticated)
            assertEquals(1, pending.cancels)
            assertTrue(activity.isFinishing)
            controller!!.pause().stop().destroy(); controller = null
        }
    }

    @Test @Config(shadows = [CredentialKeyguardShadow::class])
    fun credentialResultCannotReviveRevokedImageTicket() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        val pending = open()
        val activity = controller!!.get()
        credentialError()
        val request = shadowOf(activity).nextStartedActivityForResult
        pending.valid = false
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(activity).receiveResult(request.intent, android.app.Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, pending.authenticated)
        assertEquals(1, pending.cancels)
        assertTrue(activity.isFinishing)
    }

    private fun credentialError() {
        val field = org.robolectric.shadows.ShadowKeyguardManager::class.java.getDeclaredField("callback").apply { isAccessible = true }
        (field.get(null) as KeyguardManager.KeyguardDismissCallback).onDismissError()
    }

    @org.robolectric.annotation.Implements(KeyguardManager::class)
    class CredentialKeyguardShadow : org.robolectric.shadows.ShadowKeyguardManager() {
        @org.robolectric.annotation.Implementation
        protected fun createConfirmDeviceCredentialIntent(title: CharSequence?, description: CharSequence?): Intent =
            Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL").setPackage("com.android.settings")
    }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = "lock-auth-entry-engine"
    private var controller: ActivityController<LockEditEntryActivity>? = null

    private class Pending(duration: Long) : LockEditorBridge.Pending {
        var valid = true
        var cancels = 0
        var authenticated = 0
        val session = LockEditorActivityTest.Session()
        override val active get() = valid
        override val deadlineElapsedRealtimeMillis = SystemClock.elapsedRealtime() + duration
        override fun authenticate(): LockEditorBridge.Session? {
            authenticated++
            if (!valid) return null
            valid = false
            return session
        }
        override fun cancel() { valid = false; cancels++ }
    }

    private fun open(duration: Long = 60_000L): Pending {
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(true)
        keyguard.setKeyguardLocked(true)
        val pending = Pending(duration)
        assertTrue(LockEditorBridge.launchPrepared(context, pending, engine))
        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        controller = Robolectric.buildActivity(LockEditEntryActivity::class.java, intent).create().start().resume().visible()
        controller!!.get().onWindowFocusChanged(true)
        return pending
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        LockEditorBridge.unregister(engine)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun waitsForActualFrameworkSuccessWhileShowingNoPrivateImage() {
        val pending = open()
        val activity = controller!!.get()
        assertEquals(0, pending.authenticated)
        assertFalse(activity.isFinishing)
        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
            .none { it.text.contains("LOCK-original") || it.text.contains("content://") })
        activity.onWindowFocusChanged(false) // Expected bouncer transition is not ticket death.
        assertEquals(0, pending.cancels)
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(false)
        keyguard.setKeyguardLocked(false) // Robolectric dispatches the real captured callback.
        assertEquals(1, pending.authenticated)
        assertTrue(activity.isFinishing)
        val destination = shadowOf(activity).nextStartedActivity
        assertEquals(LockEditActivity::class.java.name, destination.component!!.className)
        assertFalse(destination.toUri(0).contains("content://"))
        assertEquals(0, pending.cancels)
    }

    @Test fun cancelThroughSystemCallbackReleasesHoldWithoutAuthentication() {
        val pending = open()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertTrue(controller!!.get().isFinishing)
        assertEquals(1, pending.cancels)
        assertEquals(0, pending.authenticated)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }

    @Test fun diagnosticUsesActualFrameworkCancelNotGenericActivityDestruction() {
        val ledger = LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val request = ledger.begin("owner","request")!!
        LockEditDiagnostics.withRequest(request) { open() }
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertEquals(listOf(LockEditDiagnosticCause.AUTH_WAITING,LockEditDiagnosticCause.AUTH_CANCELLED),
            ledger.read("owner",0)!!.rows.map { it.cause })
    }

    @Test fun diagnosticLifecycleDestructionDoesNotClaimUserCancelledAuthentication() {
        val ledger = LockEditDiagnosticLedger().also { it.openOwner("owner") }
        val request = ledger.begin("owner","request")!!
        LockEditDiagnostics.withRequest(request) { open() }
        controller!!.pause().stop().destroy(); controller = null
        val causes=ledger.read("owner",0)!!.rows.map { it.cause }
        assertTrue(causes.contains(LockEditDiagnosticCause.AUTH_ENTRY_DESTROYED))
        assertFalse(causes.contains(LockEditDiagnosticCause.AUTH_CANCELLED))
        assertFalse(causes.contains(LockEditDiagnosticCause.AUTH_SUCCEEDED))
    }

    @Test fun backCancelsAndLateSystemSuccessCannotOpenEditor() {
        val pending = open()
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(false)
        keyguard.setKeyguardLocked(false)
        assertEquals(1, pending.cancels)
        assertEquals(0, pending.authenticated)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }

    @Test fun timeoutCancelsAndReleasesHoldEvenWithoutAnyCallback() {
        val pending = open(1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001L))
        assertTrue(controller!!.get().isFinishing)
        assertEquals(1, pending.cancels)
        assertEquals(0, pending.authenticated)
    }

    @Test fun flagsImageOrEpochRevocationBeforeCallbackCannotEditReplacement() {
        val pending = open()
        pending.valid = false
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(false)
        keyguard.setKeyguardLocked(false)
        assertTrue(controller!!.get().isFinishing)
        assertEquals(0, pending.authenticated)
        assertEquals(1, pending.cancels)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }

    @Test fun callbackWhileDeviceRemainsLockedFailsClosed() {
        val pending = open()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        assertFalse(controller!!.get().isFinishing)
        assertEquals(0, pending.authenticated)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100L))
        assertTrue(controller!!.get().isFinishing)
        assertEquals(0, pending.authenticated)
        assertEquals(1, pending.cancels)
    }

    @Test fun returningFromDeepSleepRechecksElapsedDeadlineWithoutWaitingAnotherUptimeWindow() {
        val pending = open()
        controller!!.get().onWindowFocusChanged(false)
        org.robolectric.shadows.ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(60_001L))
        controller!!.get().onWindowFocusChanged(true)
        assertTrue(controller!!.get().isFinishing)
        assertEquals(1, pending.cancels)
        assertEquals(0, pending.authenticated)
    }

    @Test fun missingTokenCannotFallbackToAnyCurrentEngine() {
        controller = Robolectric.buildActivity(LockEditEntryActivity::class.java,
            Intent(context, LockEditEntryActivity::class.java)).create().start().resume()
        assertTrue(controller!!.get().isFinishing)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
