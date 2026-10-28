package dev.abdus.apps.shimmer.p9

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.p2.SourcePreviewOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P9-PREVIEW-EDIT-01：自选预览图在新图库页、旧 P1 页、旧 P2 页与播放页读写同一份存储。
 *
 * 版本 39 之前 P1 写 `source:<id>`，P2 写 `<kind>|<id>` 且优先读后者，于是在 P2 页换过一次后，
 * P1 页再换就不生效。这些测试固定"最后一次选择就是唯一有效值"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P9PreviewOverridesTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val preferences = application.getSharedPreferences(
        SourcePreviewOverrides.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val overrides = SourcePreviewOverrides(application)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun unsetSourceHasNoOverride() {
        assertNull(overrides.get(DIRECTORY, SOURCE))
    }

    @Test
    fun legacyP1KeyIsStillReadWhenNoKindKeyExists() {
        preferences.edit().putString(SourcePreviewOverrides.legacyKey(SOURCE), "content://legacy").commit()

        assertEquals("content://legacy", overrides.get(DIRECTORY, SOURCE))
    }

    @Test
    fun kindKeyWinsOverTheLegacyKey() {
        preferences.edit()
            .putString(SourcePreviewOverrides.legacyKey(SOURCE), "content://legacy")
            .putString(SourcePreviewOverrides.key(DIRECTORY, SOURCE), "content://p2")
            .commit()

        assertEquals("content://p2", overrides.get(DIRECTORY, SOURCE))
    }

    @Test
    fun setRemovesTheLegacyKeySoTheLatestChoiceIsTheOnlyValue() {
        preferences.edit().putString(SourcePreviewOverrides.legacyKey(SOURCE), "content://legacy").commit()

        overrides.set(DIRECTORY, SOURCE, "content://chosen")

        assertEquals("content://chosen", overrides.get(DIRECTORY, SOURCE))
        assertFalse(preferences.contains(SourcePreviewOverrides.legacyKey(SOURCE)))
    }

    @Test
    fun aLaterChoiceReplacesAnEarlierOne() {
        overrides.set(DIRECTORY, SOURCE, "content://first")
        overrides.set(DIRECTORY, SOURCE, "content://second")

        assertEquals("content://second", overrides.get(DIRECTORY, SOURCE))
    }

    @Test
    fun clearRemovesBothKeys() {
        preferences.edit().putString(SourcePreviewOverrides.legacyKey(SOURCE), "content://legacy").commit()
        overrides.set(DIRECTORY, SOURCE, "content://chosen")

        overrides.clear(DIRECTORY, SOURCE)

        assertNull(overrides.get(DIRECTORY, SOURCE))
        assertFalse(preferences.contains(SourcePreviewOverrides.legacyKey(SOURCE)))
    }

    @Test
    fun overridesAreKeptPerSource() {
        overrides.set(DIRECTORY, SOURCE, "content://a")
        overrides.set(DIRECTORY, OTHER_SOURCE, "content://b")

        assertEquals("content://a", overrides.get(DIRECTORY, SOURCE))
        assertEquals("content://b", overrides.get(DIRECTORY, OTHER_SOURCE))
    }

    @Test
    fun lookupOnAllGivesTheSameAnswerAsGet() {
        preferences.edit()
            .putString(SourcePreviewOverrides.legacyKey(OTHER_SOURCE), "content://legacy-only")
            .commit()
        overrides.set(DIRECTORY, SOURCE, "content://chosen")

        val all = overrides.all()

        assertEquals(overrides.get(DIRECTORY, SOURCE), SourcePreviewOverrides.lookup(all, DIRECTORY, SOURCE))
        assertEquals(
            overrides.get(SINGLE, OTHER_SOURCE),
            SourcePreviewOverrides.lookup(all, SINGLE, OTHER_SOURCE),
        )
        assertNull(SourcePreviewOverrides.lookup(all, DIRECTORY, "missing"))
    }

    private companion object {
        const val DIRECTORY = "DIRECTORY_TREE"
        const val SINGLE = "SINGLE_DOCUMENT"
        const val SOURCE = "source-a"
        const val OTHER_SOURCE = "source-b"
    }
}
