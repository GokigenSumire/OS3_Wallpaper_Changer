package dev.abdus.apps.shimmer.p10.input

import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import personal.wallpaper.protocol.lock.LockProtocol

/** 真Service/Binder入口的拒绝回归；宿主调用不是独立UID手机认证通过证据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33], manifest = Config.NONE)
class LockCommandHostServiceTest {
    private lateinit var service: LockCommandHostService
    private lateinit var binder: IBinder
    private var dispatched = 0
    private var revoked = 0

    @Before fun setup() {
        service = Robolectric.buildService(LockCommandHostService::class.java).create().get()
        binder = service.onBind(Intent())
        LockCommandHostService.endpoint = object : LockHostEndpoint {
            override fun snapshot() = LockHostState(true, "ticket", "READY")
            override fun dispatch(action: Int, ticket: String, requestId: String, complete: (LockHostResult) -> Unit) { dispatched++ }
            override fun revoke(requestId: String) { revoked++ }
        }
    }
    @After fun tearDown() {
        service.onDestroy()
        LockCommandHostService.endpoint = null
    }
    private fun request(code: Int, flags: Int = 0, write: (Parcel) -> Unit): Pair<Boolean, Parcel> {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            write(data); data.setDataPosition(0)
            val handled = binder.transact(code, data, reply, flags)
            reply.setDataPosition(0)
            return handled to reply
        } finally { data.recycle() }
    }
    @Test fun descriptorHasNoPrivateStateOrAuthority() {
        val (handled, reply) = request(IBinder.INTERFACE_TRANSACTION) { }
        try { assertTrue(handled); assertEquals(LockProtocol.HOST, reply.readString()); assertEquals(0, reply.dataAvail()) }
        finally { reply.recycle() }
        assertEquals(0, dispatched)
    }
    @Test fun unknownTransactionDoesNotReachEngine() {
        val (_, reply) = request(777) { }
        reply.recycle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, dispatched); assertEquals(0, revoked)
    }
    @Test fun oversizedParcelIsRejectedBeforeEngine() {
        val (handled, reply) = request(LockProtocol.COMMAND) { it.writeString("x".repeat(2048)) }
        reply.recycle(); assertFalse(handled); assertEquals(0, dispatched)
    }
    @Test fun ordinaryLocalUidCannotPretendFormalModule() {
        val (handled, reply) = request(LockProtocol.STATE) {
            it.writeInterfaceToken(LockProtocol.HOST); it.writeString("invented-session")
        }
        try {
            assertTrue(handled)
            try { reply.readException(); fail("普通宿主UID不应获得正式模块权限") }
            catch (expected: SecurityException) { assertEquals("UNAUTHORIZED_OR_STALE", expected.message) }
        } finally { reply.recycle() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, dispatched); assertEquals(0, revoked)
    }
    @Test fun wrongDescriptorCannotAcquireSession() {
        val (_, reply) = request(LockProtocol.HELLO) {
            it.writeInterfaceToken("other.descriptor"); it.writeInt(1); it.writeStrongBinder(android.os.Binder())
        }
        try {
            try { reply.readException(); fail("错误协议不应握手成功") }
            catch (_: SecurityException) { }
        } finally { reply.recycle() }
        assertEquals(0, dispatched)
    }
    @Test fun noModuleBindingIsSafeAndCloseIsIdempotent() {
        val hold = LockCommandHostService.keepRelayAlive(service)
        hold.close(); hold.close()
        assertEquals(0, dispatched); assertEquals(0, revoked)
    }
    @Test fun unknownRequestNeverGainsAuthorityFromHavingAnEndpoint() {
        assertFalse(LockCommandHostService.isRequestAuthorized(null))
        assertFalse(LockCommandHostService.isRequestAuthorized("invented-request"))
        assertEquals(0, dispatched)
    }
}
