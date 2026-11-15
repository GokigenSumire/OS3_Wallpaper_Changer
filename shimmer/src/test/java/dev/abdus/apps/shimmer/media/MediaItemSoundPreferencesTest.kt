package dev.abdus.apps.shimmer.media

import android.content.Context
import android.os.Looper
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaItemSoundPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun defaultsPersistAndRemainIndependentOfTargetAndExactUri() {
        val home = WallpaperTarget.HOME; val lock = WallpaperTarget.LOCK
        assertTrue(MediaPreferences.itemSoundEnabled(context, "content://test/a", home))
        MediaPreferences.setItemSoundEnabled(context, "content://test/a", home, false)
        assertFalse(MediaPreferences.itemSoundEnabled(context.applicationContext, "content://test/a", home))
        assertTrue(MediaPreferences.itemSoundEnabled(context, "content://test/a", lock))
        assertTrue(MediaPreferences.itemSoundEnabled(context, "content://test/b", home))
        assertTrue(MediaPreferences.itemSoundEnabled(context, "content://test/A", home))
        MediaPreferences.setItemSoundEnabled(context, "content://test/a", home, true)
        assertTrue(MediaPreferences.itemSoundEnabled(context, "content://test/a", home))
    }
    @Test fun overrideDoesNotEnableGlobalSoundAndCorruptFlagStaysMuted() {
        val uri = "content://test/a"
        MediaPreferences.setItemSoundEnabled(context, uri, WallpaperTarget.HOME, true)
        assertFalse(MediaPreferences.anySoundEnabled(context))
        val prefs = context.getSharedPreferences("p11_item_sound", Context.MODE_PRIVATE)
        val key = prefs.all.keys.single()
        assertFalse(key.contains(uri))
        prefs.edit().putString(key, "bad").commit()
        assertFalse(MediaPreferences.itemSoundEnabled(context, uri, WallpaperTarget.HOME))
        assertThrows(IllegalArgumentException::class.java) {
            MediaPreferences.setItemSoundEnabled(context, "", WallpaperTarget.HOME, true)
        }
    }
    @Test fun observerSeesGlobalAndItemEditsAndCanBeDisposed() {
        var changes = 0
        val stop = MediaPreferences.observeSound(context) { changes++ }
        MediaPreferences.setSoundEnabled(context, MediaKind.VIDEO, true)
        MediaPreferences.setItemSoundEnabled(context, "content://test/a", WallpaperTarget.LOCK, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(changes >= 2)
        stop(); val before = changes
        MediaPreferences.setSoundEnabled(context, MediaKind.VIDEO, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, changes)
    }
}
