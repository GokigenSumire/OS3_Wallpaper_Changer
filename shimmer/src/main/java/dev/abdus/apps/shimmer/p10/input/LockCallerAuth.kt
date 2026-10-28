package dev.abdus.apps.shimmer.p10.input

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import personal.wallpaper.protocol.lock.LockProtocol
import java.security.MessageDigest

/** 在真正Binder方法入口保存的内核UID/PID上做正式身份核定，不接受Intent声称的发送者。 */
internal object LockCallerAuth {
    fun module(context: Context, uid: Int, pid: Int): Boolean {
        if (pid <= 0) return false
        val identity = Binder.clearCallingIdentity()
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(LockProtocol.MODULE_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            val app = info.applicationInfo ?: return false
            val signing = info.signingInfo ?: return false
            val signers = signing.apkContentsSigners?.map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                    .joinToString("") { "%02X".format(it) }
            }?.toTypedArray()
            LockProtocol.exactIdentity(LockProtocol.MODULE_PACKAGE, LockProtocol.MODULE_CERT,
                uid, app.uid, pm.getPackagesForUid(uid), signers, app.enabled, signing.hasMultipleSigners())
        } catch (_: Exception) {
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
