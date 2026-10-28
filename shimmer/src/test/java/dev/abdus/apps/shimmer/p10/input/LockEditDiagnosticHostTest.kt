package dev.abdus.apps.shimmer.p10.input

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import dev.abdus.apps.shimmer.p10.editor.LockEditDiagnostics
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowSystemClock
import personal.wallpaper.protocol.lock.LockProtocol
import java.time.Duration
import java.util.Base64

/** 真 Service/Binder/PM 身份边界；公开证书取自已核正式模块77，不包含私钥。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class LockEditDiagnosticHostTest {
    @Test fun onlySuccessfulAuthenticatedDiagnosticReadRenewsBriefNavigationCapture() {
        val connection = LockCommandHostService::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(service)!!
        val lastReadField = connection.javaClass.getDeclaredField("diagnosticLastRead").apply { isAccessible = true }
        assertEquals(-1L, lastReadField.getLong(connection))
        rejected(read(owner = "wrong-session").second)
        assertEquals(-1L, lastReadField.getLong(connection))
        val (_, reply) = read()
        try { reply.readException() } finally { reply.recycle() }
        val readAt = lastReadField.getLong(connection)
        assertTrue(LockEditDiagnostics.navigationCaptureActive(android.os.SystemClock.elapsedRealtime(), readAt))
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000L))
        rejected(read(after = 9_999L).second)
        assertEquals(readAt, lastReadField.getLong(connection))
        ShadowSystemClock.advanceBy(Duration.ofMillis(500L))
        assertFalse(LockEditDiagnostics.navigationCaptureActive(android.os.SystemClock.elapsedRealtime(), readAt))
    }
    private lateinit var service: LockCommandHostService
    private lateinit var binder: IBinder
    private lateinit var session: String
    private val moduleUid = 31_001
    private val modulePid = 41_001

    @Before fun setup() {
        service = Robolectric.buildService(LockCommandHostService::class.java).create().get()
        binder = service.onBind(Intent()); installModule(CERTIFICATE)
        ShadowBinder.setCallingUid(moduleUid); ShadowBinder.setCallingPid(modulePid)
        val (_,reply) = transact(LockProtocol.HELLO) {
            it.writeInt(LockProtocol.VERSION); it.writeStrongBinder(Binder())
        }
        try { reply.readException(); session = reply.readString()!! }
        finally { reply.recycle() }
    }
    @After fun close() {
        service.onDestroy(); LockCommandHostService.endpoint = null
        ShadowBinder.setCallingUid(Process.myUid()); ShadowBinder.setCallingPid(Process.myPid())
    }
    private fun installModule(certificate: String) {
        val pkg = PackageInfo().apply {
            packageName = LockProtocol.MODULE_PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName=LockProtocol.MODULE_PACKAGE; uid=moduleUid; enabled=true }
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(Base64.getDecoder().decode(certificate)))) }
        }
        shadowOf(service.packageManager).installPackage(pkg)
        shadowOf(service.packageManager).setPackagesForUid(moduleUid,LockProtocol.MODULE_PACKAGE)
    }
    private fun transact(code: Int, flags: Int=0, fill: (Parcel)->Unit): Pair<Boolean,Parcel> {
        val data = Parcel.obtain(); val reply=Parcel.obtain()
        return try {
            data.writeInterfaceToken(LockProtocol.HOST); fill(data); data.setDataPosition(0)
            val result=binder.transact(code,data,reply,flags); reply.setDataPosition(0); result to reply
        } finally { data.recycle() }
    }
    private fun read(after: Long=0, owner: String=session) = transact(LockProtocol.EDIT_DIAGNOSTICS) {
        it.writeString(owner); it.writeLong(after)
    }
    private fun rejected(reply: Parcel) {
        try { reply.readException(); fail("诊断读取应被拒绝") }
        catch (_: SecurityException) { }
        finally { reply.recycle() }
    }

    @Test fun formalSignedCurrentOwnerCanReadOnlyFixedScalarSchema() {
        LockEditDiagnostics.ledger.begin(session,"private-request")!!.record(1)
        val (handled,reply)=read()
        try {
            assertTrue(handled); assertTrue(reply.dataSize()<=1024); reply.readException()
            assertEquals(1,reply.readInt()); assertEquals(1L,reply.readLong()); assertEquals(0,reply.readInt())
            assertEquals(1,reply.readInt()); assertEquals(1L,reply.readLong()); assertEquals(1,reply.readInt())
            assertEquals(0,reply.dataAvail())
        } finally { reply.recycle() }
    }
    @Test fun wrongUidCannotReadOwnerEvenWhenTokenIsKnown() {
        ShadowBinder.setCallingUid(moduleUid+1); rejected(read().second)
    }
    @Test fun wrongPidCannotUseSameSignedPackageOwnerSession() {
        ShadowBinder.setCallingPid(modulePid+1); rejected(read().second)
    }
    @Test fun changedCertificateCannotReadExistingOwner() {
        installModule(Base64.getEncoder().encodeToString(byteArrayOf(1,2,3))); rejected(read().second)
    }
    @Test fun staleOwnerTokenRejectedWithoutReturningRows() { rejected(read(owner="invented-session").second) }
    @Test fun unknownAndFutureCursorRejected() { rejected(read(-1).second); rejected(read(1).second) }
    @Test fun thirtyTwoReadsPerSecondMaximumAndNextWindowRestores() {
        repeat(32) { val (_,reply)=read(); try { reply.readException() } finally { reply.recycle() } }
        rejected(read().second)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_001))
        val (_,reply)=read(); try { reply.readException() } finally { reply.recycle() }
    }
    @Test fun fullBoundedPageFitsParcelBudgetAndMakesTruncationExplicit() {
        repeat(4) { index -> val request=LockEditDiagnostics.ledger.begin(session,"request-$index")!!; for(cause in 1..12) request.record(cause) }
        val (_,reply)=read()
        try {
            assertTrue(reply.dataSize()<=1024); reply.readException(); assertEquals(1,reply.readInt())
            assertEquals(48L,reply.readLong()); assertEquals(1,reply.readInt()); assertEquals(32,reply.readInt())
            repeat(32) { index -> assertEquals(17L+index,reply.readLong()); assertTrue(reply.readInt() in 1..12) }
            assertEquals(0,reply.dataAvail())
        } finally { reply.recycle() }
    }
    @Test fun unknownCauseCannotAppearOnWire() {
        val request=LockEditDiagnostics.ledger.begin(session,"request")!!
        assertFalse(request.record(0)); assertFalse(request.record(19))
        val (_,reply)=read(); try { reply.readException(); reply.readInt(); assertEquals(0L,reply.readLong()); reply.readInt(); assertEquals(0,reply.readInt()) }
        finally { reply.recycle() }
    }
    @Test fun asynchronousOrOversizedRequestCannotReadDiagnostics() {
        val (oneway,onewayReply)=transact(LockProtocol.EDIT_DIAGNOSTICS,IBinder.FLAG_ONEWAY) { it.writeString(session); it.writeLong(0) }
        onewayReply.recycle(); assertFalse(oneway)
        val (oversized,largeReply)=transact(LockProtocol.EDIT_DIAGNOSTICS) { it.writeString("x".repeat(2048)); it.writeLong(0) }
        largeReply.recycle(); assertFalse(oversized)
    }
    @Test fun trailingInputCannotBeInterpretedAsAuthorityOrCause() {
        val (_,reply)=transact(LockProtocol.EDIT_DIAGNOSTICS) { it.writeString(session); it.writeLong(0); it.writeInt(8) }
        try { reply.readException(); fail("多余输入应拒绝") } catch (_: IllegalArgumentException) { } finally { reply.recycle() }
    }
    @Test fun closedOwnerCannotReadOldResult() {
        val (_,reply)=transact(LockProtocol.CLOSE) { it.writeString(session) }
        try { reply.readException() } finally { reply.recycle() }
        rejected(read().second)
    }

    companion object {
        private val CERTIFICATE = """
            MIIEOzCCAqOgAwIBAgIIHtSHNFvjBQowDQYJKoZIhvcNAQELBQAwSzELMAkGA1UE
            BhMCQ04xETAPBgNVBAoTCFBlcnNvbmFsMSkwJwYDVQQDEyBPUzQgSG9tZSBDb21w
            YXQgUGVyc29uYWwgUmVsZWFzZTAgFw0yNjEwMTYwNjA3NDRaGA8yMDU0MDMwMzA2
            MDc0NFowSzELMAkGA1UEBhMCQ04xETAPBgNVBAoTCFBlcnNvbmFsMSkwJwYDVQQD
            EyBPUzQgSG9tZSBDb21wYXQgUGVyc29uYWwgUmVsZWFzZTCCAaIwDQYJKoZIhvcN
            AQEBBQADggGPADCCAYoCggGBAK5hmj95Cruek8ZmQ9caklzPxa728ChJ30r3qASw
            up6QXOG3CknaxhUwy4amqyvI2iM6eIhkptcRe3Kmcu9G6/55fHQInLYjlKBevjjE
            fZy7zns44h4YIe6D8R2rQKAEVtZGZoKUFdti4Er8EswjF60n8ibD5CQ+e6wlJiRe
            qEnpX4zFEhJLkdrMkAKdBU4b1VC/bzpuyVCL4zls672ytbn86oIwq8B8P9yLr3HR
            nfJAXOc2dBNE1imQNcyui/Em3s3Sf24lw2eR4K/dHdy57qRRjuUo8TrcGDxddznM
            MOqTyJaF+MpPEIbTOt26ptWAU6D5x1I8kWbf0hmw6ODfV6DttUlANCUddH2ExIu+
            4oUCt6MPJR2utt8GLxIi26EjV/1SKOZF6sjoRuGFtcWr9tCOVhZPW+IGoniWEL8i
            wWEvJnJDZIjFg9BW093kkQps9fLqof7573PzT3kYJi2yNSXnSqLvmz2stMAJ0IEP
            cJ4cAtI38sbuHjMKk0POc/0OiQIDAQABoyEwHzAdBgNVHQ4EFgQU3YBO/K0WqrNP
            98vyMyvOxrHpgkgwDQYJKoZIhvcNAQELBQADggGBACC61ppCjXFaqD1huBT2J7Hw
            fZ6Q1klGshx9RBA5Dln8jmkt1dJX5PjcYRtwywWwS5RuVwrIH8wLJ0YxJdO0rae3
            5t5f3NwDmwq3Rt8GX6JHw1FlwqH6IXoGQCmiKI4SuqKBHx5torXWkfbOrANiyAYx
            p5IeA1vO8nthFtsXaLIJ6GvOCa1YSIumqKVMRY2FJc/MMp20MqUzdejsFBU+sYqr
            ULMNdMSFf65BbItS1jdLGyS2cg/xBjQbOi8b9sreHTrwPI2Q4gzuKFW8OF4Yz3xO
            LA4NZX/EpZ6JsOJOBesQWGUr/81PnxMR7Wk2FcFGwXacOToutdw+pL5Y9/fBepaQ
            aD4vo2lIxQ8rAkJpOJFV6xA4wnaucAS9vsGfRkkBrT1vpPkTt8xrCrGhpOfuUVjA
            Sk/7Wb20i+Vcr8vlT9zUPFj4msqU7eTtUYkxH61RU0OeHF54fpheHIpiBDj/gLWp
            21dzh7YONFeplqAVebfD4uVcLFWFQVqBLt8+XA05JA==
        """.trimIndent().replace("\n","").replace("\r","")
    }
}
