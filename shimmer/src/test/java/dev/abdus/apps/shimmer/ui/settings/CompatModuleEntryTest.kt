package dev.abdus.apps.shimmer.ui.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompatModuleEntryTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val modulePackage = "personal.wallpaper.os4.e1"
    private val moduleActivity = ComponentName(modulePackage, "$modulePackage.MainActivity")

    @Test
    fun absentModuleIsReportedWithoutLaunchingAnything() {
        val context = RecordingContext(base)
        val status = CompatModuleEntry.read(context, modulePackage)

        assertEquals(CompatModuleEntry.Availability.NOT_INSTALLED, status.availability)
        assertFalse(status.canOpen)
        assertNull(status.versionName)
        assertEquals(CompatModuleEntry.OpenResult.NOT_INSTALLED, CompatModuleEntry.open(context, modulePackage))
        assertTrue(context.started.isEmpty())
    }

    @Test
    fun installedModuleReportsItsActualVersionAndLauncher() {
        installModule()

        val status = CompatModuleEntry.read(base, modulePackage)

        assertEquals(CompatModuleEntry.Availability.INSTALLED, status.availability)
        assertEquals("1.0.1", status.versionName)
        assertEquals(62L, status.versionCode)
        assertTrue(status.canOpen)
    }

    @Test
    fun packageWithoutLauncherStaysInstalledButCannotOpen() {
        installModule(withLauncher = false)
        val context = RecordingContext(base)

        val status = CompatModuleEntry.read(context, modulePackage)

        assertEquals(CompatModuleEntry.Availability.INSTALLED, status.availability)
        assertFalse(status.canOpen)
        assertEquals(CompatModuleEntry.OpenResult.NO_LAUNCHER, CompatModuleEntry.open(context, modulePackage))
        assertTrue(context.started.isEmpty())
    }

    @Test
    fun debugCompanionDoesNotSatisfyTheFormalPackageRequirement() {
        installModule(packageName = "$modulePackage.debug")

        assertEquals(CompatModuleEntry.Availability.NOT_INSTALLED, CompatModuleEntry.read(base, modulePackage).availability)
        assertTrue(CompatModuleEntry.read(base, "$modulePackage.debug").canOpen)
    }

    @Test
    fun openingUsesOnlyAnExplicitOrdinaryLauncherIntent() {
        installModule()
        val context = RecordingContext(base)

        assertEquals(CompatModuleEntry.OpenResult.OPENED, CompatModuleEntry.open(context, modulePackage))

        val intent = context.started.single()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(moduleActivity, intent.component)
        assertEquals(modulePackage, intent.`package`)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.categories)
        assertNull(intent.extras)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun uninstallBetweenRefreshAndClickCannotLaunchTheOldEntry() {
        installModule()
        assertTrue(CompatModuleEntry.read(base, modulePackage).canOpen)
        shadowOf(base.packageManager).removePackage(modulePackage)
        val context = RecordingContext(base)

        assertEquals(CompatModuleEntry.OpenResult.NOT_INSTALLED, CompatModuleEntry.open(context, modulePackage))
        assertTrue(context.started.isEmpty())
    }

    @Test
    fun disappearingActivityDuringLaunchReturnsAnUnavailableEntry() {
        installModule()
        val context = RecordingContext(base) { throw ActivityNotFoundException() }

        assertEquals(CompatModuleEntry.OpenResult.NO_LAUNCHER, CompatModuleEntry.open(context, modulePackage))
    }

    @Test
    fun deniedActivityLaunchIsReportedWithoutCrashing() {
        installModule()
        val context = RecordingContext(base) { throw SecurityException("denied") }

        assertEquals(CompatModuleEntry.OpenResult.BLOCKED, CompatModuleEntry.open(context, modulePackage))
    }

    @Test
    fun disabledModuleIsNotOfferedAsLaunchable() {
        installModule(enabled = false)

        assertFalse(CompatModuleEntry.read(base, modulePackage).canOpen)
        assertEquals(CompatModuleEntry.OpenResult.NO_LAUNCHER, CompatModuleEntry.open(base, modulePackage))
    }

    @Test
    fun blankVersionNameDoesNotMasqueradeAsAKnownVersion() {
        installModule(versionName = "  ")

        assertNull(CompatModuleEntry.read(base, modulePackage).versionName)
    }

    @Test(expected = IllegalStateException::class)
    fun unexpectedProgrammingFailuresAreNotHiddenAsAnInstallationProblem() {
        installModule()
        CompatModuleEntry.open(RecordingContext(base) { throw IllegalStateException("unexpected") }, modulePackage)
    }

    private fun installModule(
        packageName: String = modulePackage,
        withLauncher: Boolean = true,
        enabled: Boolean = true,
        versionName: String = "1.0.1",
    ) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            this.versionName = versionName
            longVersionCode = 62L
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.enabled = enabled
            }
        }
        val manager = shadowOf(base.packageManager)
        manager.installPackage(info)
        if (withLauncher) {
            val component = ComponentName(packageName, "$modulePackage.MainActivity")
            manager.addActivityIfNotPresent(component).apply {
                this.enabled = enabled
                exported = true
            }
            manager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        }
    }

    private class RecordingContext(
        base: Context,
        private val onStart: () -> Unit = {},
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            started += intent
            onStart()
        }
    }
}
