package dev.abdus.apps.shimmer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Only discovers and opens the companion app; it never controls its system hooks. */
object CompatModuleEntry {
    enum class Availability { NOT_INSTALLED, INSTALLED, UNAVAILABLE }
    enum class OpenResult { OPENED, NOT_INSTALLED, NO_LAUNCHER, BLOCKED }

    data class Status(
        val packageName: String,
        val availability: Availability,
        val versionName: String? = null,
        val versionCode: Long? = null,
        val canOpen: Boolean = false,
    )

    @Suppress("DEPRECATION")
    fun read(context: Context, packageName: String): Status {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            Status(
                packageName = packageName,
                availability = Availability.INSTALLED,
                versionName = info.versionName?.takeIf { it.isNotBlank() },
                versionCode = info.longVersionCode,
                canOpen = info.applicationInfo?.enabled != false &&
                    explicitLaunchIntent(context.packageManager, packageName) != null,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            Status(packageName, Availability.NOT_INSTALLED)
        } catch (_: SecurityException) {
            Status(packageName, Availability.UNAVAILABLE)
        }
    }

    fun open(context: Context, packageName: String): OpenResult {
        // Re-read on click: uninstalling or disabling the module can race with the settings card.
        val status = read(context, packageName)
        if (status.availability == Availability.NOT_INSTALLED) return OpenResult.NOT_INSTALLED
        if (status.availability == Availability.UNAVAILABLE) return OpenResult.BLOCKED
        if (!status.canOpen) return OpenResult.NO_LAUNCHER
        return try {
            val intent = explicitLaunchIntent(context.packageManager, packageName)
                ?: return OpenResult.NO_LAUNCHER
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            OpenResult.OPENED
        } catch (_: ActivityNotFoundException) {
            OpenResult.NO_LAUNCHER
        } catch (_: SecurityException) {
            OpenResult.BLOCKED
        }
    }

    private fun explicitLaunchIntent(manager: PackageManager, packageName: String): Intent? {
        val component = manager.getLaunchIntentForPackage(packageName)?.component ?: return null
        if (component.packageName != packageName) return null
        // Rebuild the ordinary launcher intent without control actions, extras or implicit targets.
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
            .setComponent(component)
    }
}
