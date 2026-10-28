package dev.abdus.apps.shimmer.p3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.p2.PlaybackSnapshot
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P3TransformStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val saved = ImageTransform(fitMode = FitMode.CONTAIN, userScale = 3f, mirrorHorizontal = true)
    private val inherited = ImageTransform(offsetX = 0.2f)

    @Before
    fun clearState() {
        context.getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(WALLPAPER_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val WALLPAPER_PREFS = "shimmer_wallpaper_prefs"
    }

    @Test
    fun savedPlacementRoundTripsAndResetRemovesIt() {
        val store = PlaybackSnapshotStore(context)
        assertNull(store.loadImageTransform("a"))

        store.commitImageTransform("a", saved)
        assertEquals(saved, store.loadImageTransform("a"))
        assertNull(store.loadImageTransform("b"))

        store.commitImageTransform("a", null)
        assertNull(store.loadImageTransform("a"))
    }

    @Test
    fun saveClearsInheritedCopiesOfThatImageInTheSameCommit() {
        val store = PlaybackSnapshotStore(context)
        store.save(
            PlaybackSnapshot(
                historyIds = listOf("a", "b", "a"),
                historyCursor = 2,
                currentId = "a",
                historyTransforms = listOf(inherited, inherited, inherited),
            ),
        )
        val changedKeys = mutableListOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changedKeys += key }
        store.registerListener(listener)

        val updated = store.commitImageTransform("a", saved)
        store.unregisterListener(listener)

        assertEquals(listOf(null, inherited), updated?.historyTransforms)
        assertEquals(updated, store.load())
        assertEquals(saved, store.loadImageTransform("a"))
        assertTrue(changedKeys.contains(PlaybackSnapshotStore.transformKey("a")))
        assertTrue(changedKeys.contains(PlaybackSnapshotStore.SNAPSHOT_KEY))
    }

    @Test
    fun saveWithoutInheritedCopiesDoesNotRewriteTheSnapshot() {
        val store = PlaybackSnapshotStore(context)
        store.save(PlaybackSnapshot(historyIds = listOf("b"), historyCursor = 0, currentId = "b"))
        val changedKeys = mutableListOf<String?>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changedKeys += key }
        store.registerListener(listener)

        store.commitImageTransform("a", saved)
        store.unregisterListener(listener)

        assertFalse(changedKeys.contains(PlaybackSnapshotStore.SNAPSHOT_KEY))
        assertTrue(changedKeys.contains(PlaybackSnapshotStore.transformKey("a")))
    }

    @Test
    fun unreadablePlacementIsIgnoredInsteadOfBreakingPlayback() {
        context.getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE).edit()
            .putString(PlaybackSnapshotStore.transformKey("a"), "{\"f\":\"SPIRAL\"}")
            .commit()
        assertNull(PlaybackSnapshotStore(context).loadImageTransform("a"))
    }

    @Test
    fun snapshotWithPlacementsRoundTripsThroughTheStore() {
        val store = PlaybackSnapshotStore(context)
        val snapshot = PlaybackSnapshot(
            historyIds = listOf("a", "b"),
            historyCursor = 1,
            currentId = "b",
            historyTransforms = listOf(null, inherited),
        )
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test
    fun snapshotWrittenBeforeP3StillLoads() {
        context.getSharedPreferences(PlaybackSnapshotStore.NAME, Context.MODE_PRIVATE).edit()
            .putString(
                PlaybackSnapshotStore.SNAPSHOT_KEY,
                "{\"historyIds\":[\"a\"],\"historyCursor\":0,\"currentId\":\"a\"}",
            )
            .commit()
        val loaded = PlaybackSnapshotStore(context).load()
        assertEquals(listOf("a"), loaded?.historyIds)
        assertEquals(emptyList<ImageTransform?>(), loaded?.historyTransforms)
    }

    @Test
    fun globalDefaultsPersistAndUnknownFitModeFallsBack() {
        val preferences = WallpaperPreferences.create(context)
        // Default is smart fit (null stored value)
        assertEquals(P3Defaults(FitMode.COVER, blurFill = true, inheritPrevious = false, smartFit = true), preferences.getP3Defaults())

        preferences.setP3DefaultFitMode(FitMode.FIT_HEIGHT)
        preferences.setP3DefaultBlurFill(false)
        preferences.setP3InheritPrevious(true)
        assertEquals(P3Defaults(FitMode.FIT_HEIGHT, blurFill = false, inheritPrevious = true, smartFit = false), preferences.getP3Defaults())

        // Unknown fit mode "SPIRAL" falls back to COVER with smartFit = true
        context.getSharedPreferences(WALLPAPER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(WallpaperPreferences.KEY_P3_DEFAULT_FIT_MODE, "SPIRAL")
            .commit()
        val defaults = WallpaperPreferences.create(context).getP3Defaults()
        assertEquals(FitMode.COVER, defaults.fitMode)
        assertTrue(defaults.smartFit)
    }
}
