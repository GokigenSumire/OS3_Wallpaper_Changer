package dev.abdus.apps.shimmer

import android.app.WallpaperManager
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.Settings
import android.os.Build
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p10.LockWallpaperService

/**
 * Utility for checking wallpaper status.
 */
object WallpaperUtil {

    /**
     * Identifies the intent accepted by startActivity. Acceptance alone does not
     * prove that the system displayed a picker or granted any access.
     */
    enum class PickerLaunchResult {
        DIRECT_PREVIEW,
        LIVE_WALLPAPER_CHOOSER,
        WALLPAPER_SETTINGS,
        FAILED,
    }

    /**
     * Checks if this app is currently set as the active live wallpaper.
     */
    fun isActiveWallpaper(context: Context, target: WallpaperTarget = WallpaperTarget.HOME): Boolean = try {
        val wallpaperManager = WallpaperManager.getInstance(context)
        val wallpaperInfo = if (target == WallpaperTarget.LOCK) {
            if (Build.VERSION.SDK_INT >= 34) wallpaperManager.getWallpaperInfo(WallpaperManager.FLAG_LOCK) else null
        } else wallpaperManager.wallpaperInfo

        wallpaperInfo?.component == ComponentName(
            context,
            componentOf(target)
        )
    } catch (_: RuntimeException) {
        // OEM package/wallpaper service denial is not proof that a component is active.
        false
    }

    /**
     * Opens the most specific system wallpaper entry point available.
     *
     * An unavailable or rejected direct preview falls back to the generic picker.
     * A silently returning vendor activity cannot be detected here; callers can
     * offer [openSystemWallpaperChooser] as a separate, user-triggered entry.
     */
    fun openWallpaperPicker(context: Context, target: WallpaperTarget = WallpaperTarget.HOME): PickerLaunchResult {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, componentOf(target))
            )
        }
        return if (tryStart(context, intent)) PickerLaunchResult.DIRECT_PREVIEW
        else openSystemWallpaperChooser(context)
    }

    fun componentOf(target: WallpaperTarget): Class<*> = when (target) {
        WallpaperTarget.HOME -> ShimmerWallpaperService::class.java
        WallpaperTarget.LOCK -> LockWallpaperService::class.java
    }

    /** Bypasses direct preview so the user can recover from a silent vendor return. */
    fun openSystemWallpaperChooser(context: Context): PickerLaunchResult {
        val attempts = listOf(
            PickerLaunchResult.LIVE_WALLPAPER_CHOOSER to
                Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
            PickerLaunchResult.WALLPAPER_SETTINGS to Intent(Intent.ACTION_SET_WALLPAPER),
        )
        attempts.forEach { (result, intent) ->
            if (tryStart(context, intent)) return result
        }
        return PickerLaunchResult.FAILED
    }

    /**
     * Opens this app's details as help for system-specific permissions. This does
     * not request or grant a universal live-wallpaper runtime permission.
     */
    fun openAppDetails(context: Context): Boolean = tryStart(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        },
    )

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.withTaskFlagWhenNeeded(context))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun Intent.withTaskFlagWhenNeeded(context: Context): Intent = apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
