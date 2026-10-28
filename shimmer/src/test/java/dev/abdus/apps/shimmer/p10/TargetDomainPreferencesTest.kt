package dev.abdus.apps.shimmer.p10

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p2.SourcePreviewOverrides
import dev.abdus.apps.shimmer.p3.FitMode
import dev.abdus.apps.shimmer.p3.P3Defaults
import dev.abdus.apps.shimmer.p5.TransitionChoice
import dev.abdus.apps.shimmer.p5.TransitionSettings
import dev.abdus.apps.shimmer.p5.TransitionSettingsStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real preference files: only explicitly target-owned settings split, never the whole app file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TargetDomainPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val home get() = WallpaperPreferences.create(context)
    private val lock get() = WallpaperPreferences.create(context, WallpaperTarget.LOCK)

    @Before fun clearOnlyTestPreferenceFiles() {
        listOf("shimmer_wallpaper_prefs", WallpaperPreferences.LOCK_DOMAIN_NAME,
            SourcePreviewOverrides.PREFERENCES_NAME, SourcePreviewOverrides.LOCK_PREFERENCES_NAME,
            TransitionSettingsStore.NAME, TransitionSettingsStore.LOCK_NAME).forEach {
            prefs(it).edit().clear().commit()
        }
    }

    @Test fun defaultHomeUsesOriginalFileAndLockStartsWithDefaultsWithoutCopying() {
        val original = prefs("shimmer_wallpaper_prefs")
        original.edit().putStringSet(WallpaperPreferences.KEY_DISABLED_IMAGES, setOf("same-uri"))
            .putString(WallpaperPreferences.KEY_P3_DEFAULT_FIT_MODE, FitMode.FIT_HEIGHT.name)
            .putBoolean(WallpaperPreferences.KEY_P3_INHERIT_PREVIOUS, true).commit()
        val before = original.all.toMap()
        assertEquals(WallpaperTarget.HOME, home.target)
        assertEquals(WallpaperTarget.LOCK, lock.target)
        assertEquals(setOf("same-uri"), home.getDisabledImages())
        assertTrue(lock.getDisabledImages().isEmpty())
        assertEquals(P3Defaults(smartFit = true), lock.getP3Defaults())
        assertEquals(before, original.all)
        assertTrue(prefs(WallpaperPreferences.LOCK_DOMAIN_NAME).all.isEmpty())
    }

    @Test fun sameImageCanBeDisabledOnlyInOneDomain() {
        home.setImageEnabled("same-uri", false)
        lock.setImageEnabled("lock-only", false)
        assertEquals(setOf("same-uri"), home.getDisabledImages())
        assertEquals(setOf("lock-only"), lock.getDisabledImages())
        lock.setImageEnabled("same-uri", true)
        assertEquals(setOf("same-uri"), home.getDisabledImages())
        lock.clearDisabledImages()
        assertEquals(setOf("same-uri"), home.getDisabledImages())
        assertTrue(lock.getDisabledImages().isEmpty())
    }

    @Test fun disabledReadReturnsDetachedSetAndDoesNotAllowCallerMutation() {
        lock.setImageEnabled("one", false)
        val read = lock.getDisabledImages().toMutableSet()
        read.add("uncommitted")
        assertEquals(setOf("one"), lock.getDisabledImages())
        assertTrue(home.getDisabledImages().isEmpty())
    }

    @Test fun placementDefaultsAndInheritanceAreIndependentAndSurviveRecreation() {
        home.setP3DefaultFitMode(FitMode.FIT_WIDTH)
        home.setP3DefaultBlurFill(false)
        home.setP3InheritPrevious(true)
        lock.setP3DefaultFitMode(FitMode.CONTAIN)
        lock.setP3DefaultBlurFill(true)
        lock.setP3InheritPrevious(false)
        assertEquals(FitMode.FIT_WIDTH, home.getP3Defaults().fitMode)
        assertFalse(home.getP3Defaults().blurFill)
        assertTrue(home.getP3Defaults().inheritPrevious)
        assertEquals(FitMode.CONTAIN, lock.getP3Defaults().fitMode)
        assertTrue(lock.getP3Defaults().blurFill)
        assertFalse(lock.getP3Defaults().inheritPrevious)
        lock.setP3SmartFit()
        assertTrue(lock.getP3Defaults().smartFit)
        assertFalse(home.getP3Defaults().smartFit)
    }

    @Test fun corruptHomeDomainValuesAreNotReadOrRewrittenByLockDomain() {
        val original = prefs("shimmer_wallpaper_prefs")
        original.edit().putInt(WallpaperPreferences.KEY_P3_DEFAULT_FIT_MODE, 7)
            .putString(WallpaperPreferences.KEY_DISABLED_IMAGES, "invalid-set").commit()
        val before = original.all.toMap()
        lock.setP3DefaultFitMode(FitMode.COVER)
        lock.setImageEnabled("lock", false)
        assertEquals(FitMode.COVER, lock.getP3Defaults().fitMode)
        assertEquals(setOf("lock"), lock.getDisabledImages())
        assertEquals(before, original.all)
    }

    @Test fun staticAcceptanceRemainsApplicationGlobalRatherThanDuplicatedInLock() {
        home.setP2StaticTestMode(true)
        assertTrue(lock.isP2StaticTestMode())
        lock.setP2StaticTestMode(false)
        assertFalse(home.isP2StaticTestMode())
        assertFalse(prefs(WallpaperPreferences.LOCK_DOMAIN_NAME).contains(WallpaperPreferences.KEY_P2_STATIC_TEST_MODE))
    }

    @Test fun nonDomainApplicationEffectsKeepOriginalGlobalStorage() {
        home.setBlurAmount(0.25f)
        assertEquals(0.25f, lock.getBlurAmount(), 0f)
        lock.setDimAmount(0.75f)
        assertEquals(0.75f, home.getDimAmount(), 0f)
        assertTrue(prefs(WallpaperPreferences.LOCK_DOMAIN_NAME).all.isEmpty())
    }

    @Test fun lockListenerDoesNotReceiveHomePlacementOrDisabledChanges() {
        val events = mutableListOf<String?>()
        val instance = lock
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> events += key }
        instance.registerListener(listener)
        home.setP3DefaultFitMode(FitMode.FIT_WIDTH)
        home.setImageEnabled("home", false)
        idle()
        assertTrue(events.isEmpty())
        instance.setP3DefaultFitMode(FitMode.CONTAIN)
        instance.setImageEnabled("lock", false)
        idle()
        assertEquals(setOf(WallpaperPreferences.KEY_P3_DEFAULT_FIT_MODE, WallpaperPreferences.KEY_DISABLED_IMAGES), events.toSet())
        instance.unregisterListener(listener)
    }

    @Test fun domainListenersStillObserveGlobalStaticAcceptanceAndCanUnregisterBothSources() {
        val events = mutableListOf<String?>()
        val instance = lock
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> events += key }
        instance.registerListener(listener)
        instance.registerListener(listener)
        home.setP2StaticTestMode(true)
        idle()
        assertEquals(listOf(WallpaperPreferences.KEY_P2_STATIC_TEST_MODE), events)
        instance.unregisterListener(listener)
        instance.unregisterListener(listener)
        home.setP2StaticTestMode(false)
        instance.setImageEnabled("lock", false)
        idle()
        assertEquals(listOf(WallpaperPreferences.KEY_P2_STATIC_TEST_MODE), events)
    }

    @Test fun homeListenerNeverReceivesLockDomainChanges() {
        val events = mutableListOf<String?>()
        val instance = home
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> events += key }
        instance.registerListener(listener)
        lock.setImageEnabled("lock", false)
        lock.setP3InheritPrevious(true)
        idle()
        assertTrue(events.isEmpty())
        instance.setImageEnabled("home", false)
        idle()
        assertEquals(listOf(WallpaperPreferences.KEY_DISABLED_IMAGES), events)
        instance.unregisterListener(listener)
    }

    @Test fun originalSinglePreferencesConstructorKeepsHomeSemantics() {
        val supplied = prefs("original-constructor-fixture")
        supplied.edit().clear().commit()
        val original = WallpaperPreferences(supplied)
        original.setImageEnabled("image", false)
        original.setP3DefaultFitMode(FitMode.FIT_HEIGHT)
        assertEquals(WallpaperTarget.HOME, original.target)
        assertEquals(setOf("image"), supplied.getStringSet(WallpaperPreferences.KEY_DISABLED_IMAGES, emptySet()))
        assertEquals(FitMode.FIT_HEIGHT.name, supplied.getString(WallpaperPreferences.KEY_P3_DEFAULT_FIT_MODE, null))
    }

    @Test fun sameSourceCanHaveDifferentCustomPreviewsAndClearIsLocal() {
        val homePreview = SourcePreviewOverrides(context)
        val lockPreview = SourcePreviewOverrides(context, WallpaperTarget.LOCK)
        homePreview.set("DIRECTORY_TREE", "same", "content://home-preview")
        assertNull(lockPreview.get("DIRECTORY_TREE", "same"))
        lockPreview.set("DIRECTORY_TREE", "same", "content://lock-preview")
        assertEquals("content://home-preview", homePreview.get("DIRECTORY_TREE", "same"))
        assertEquals("content://lock-preview", lockPreview.get("DIRECTORY_TREE", "same"))
        lockPreview.clear("DIRECTORY_TREE", "same")
        assertEquals("content://home-preview", homePreview.get("DIRECTORY_TREE", "same"))
        assertNull(lockPreview.get("DIRECTORY_TREE", "same"))
    }

    @Test fun legacyHomePreviewFallbackIsNotInheritedByNewLockDomain() {
        prefs(SourcePreviewOverrides.PREFERENCES_NAME).edit().putString("source:same", "content://legacy-home").commit()
        assertEquals("content://legacy-home", SourcePreviewOverrides(context).get("DIRECTORY_TREE", "same"))
        assertNull(SourcePreviewOverrides(context, WallpaperTarget.LOCK).get("DIRECTORY_TREE", "same"))
        assertEquals("p2_preview_preferences", SourcePreviewOverrides.nameFor(WallpaperTarget.HOME))
        assertNotEquals(SourcePreviewOverrides.nameFor(WallpaperTarget.HOME), SourcePreviewOverrides.nameFor(WallpaperTarget.LOCK))
    }

    @Test fun transitionsStartEmptyInLockAndWriteOnlyTheirOwnDomain() {
        val homeTransition = TransitionSettingsStore(context)
        val lockTransition = TransitionSettingsStore(context, WallpaperTarget.LOCK)
        val homeSettings = TransitionSettings(choice = TransitionChoice.NONE, durationMillis = 321)
        homeTransition.save(homeSettings)
        val before = prefs(TransitionSettingsStore.NAME).all.toMap()
        assertEquals(TransitionSettings(), lockTransition.load())
        lockTransition.save(TransitionSettings(choice = TransitionChoice.SLIDE, durationMillis = 876))
        assertEquals(homeSettings, homeTransition.load())
        assertEquals(before, prefs(TransitionSettingsStore.NAME).all)
        assertEquals(TransitionChoice.SLIDE, lockTransition.load().choice)
        assertEquals("p5_transition", TransitionSettingsStore.nameFor(WallpaperTarget.HOME))
        assertNotEquals(TransitionSettingsStore.NAME, TransitionSettingsStore.nameFor(WallpaperTarget.LOCK))
    }

    @Test fun transitionListenersDoNotCrossDomains() {
        val events = mutableListOf<String?>()
        val instance = TransitionSettingsStore(context, WallpaperTarget.LOCK)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> events += key }
        instance.registerListener(listener)
        TransitionSettingsStore(context).save(TransitionSettings(choice = TransitionChoice.NONE))
        idle()
        assertTrue(events.isEmpty())
        instance.save(TransitionSettings(choice = TransitionChoice.ZOOM))
        idle()
        assertEquals(listOf(TransitionSettingsStore.KEY_SETTINGS), events)
        instance.unregisterListener(listener)
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
