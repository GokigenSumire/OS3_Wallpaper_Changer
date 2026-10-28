package dev.abdus.apps.shimmer.p2

import android.content.Context
import android.content.SharedPreferences
import dev.abdus.apps.shimmer.p10.WallpaperTarget

/**
 * P9-PREVIEW-EDIT-01：用户自选的来源预览图，旧 P1 页、旧 P2 页与新图库页、播放页共用。
 *
 * 版本 39 之前两套旧页各写各的键：P1 写 `source:<id>`，P2 写 `<kind>|<id>` 并按"先 P2 键、再 P1 键"读取，
 * 于是在 P2 页换过一次后，P1 页再换就不再生效。这里统一为：读取仍兼容两种旧键，写入只写
 * `<kind>|<id>` 并删除 `source:<id>`，任何入口最后一次选择就是唯一有效值。
 *
 * 只存 URI 字符串；持久读取授权由调用方在拿到系统选择器结果时申请。
 */
class SourcePreviewOverrides(private val preferences: SharedPreferences) {
    constructor(context: Context, target: WallpaperTarget = WallpaperTarget.HOME) : this(
        context.applicationContext.getSharedPreferences(nameFor(target), Context.MODE_PRIVATE),
    )

    fun get(kind: String, sourceId: String): String? =
        preferences.getString(key(kind, sourceId), null)
            ?: preferences.getString(legacyKey(sourceId), null)

    fun set(kind: String, sourceId: String, uri: String) {
        preferences.edit()
            .putString(key(kind, sourceId), uri)
            .remove(legacyKey(sourceId))
            .apply()
    }

    /** 恢复为自动选择（目录取最后一张、其他取第一张）。 */
    fun clear(kind: String, sourceId: String) {
        preferences.edit()
            .remove(key(kind, sourceId))
            .remove(legacyKey(sourceId))
            .apply()
    }

    /** 当前所有自选预览，按 [key] 取值；播放页一次读出，避免每个分组头部各查一次。 */
    fun all(): Map<String, String> =
        preferences.all.mapNotNull { (name, value) -> (value as? String)?.let { name to it } }.toMap()

    companion object {
        const val PREFERENCES_NAME = "p2_preview_preferences"
        const val LOCK_PREFERENCES_NAME = "p10_lock_preview_preferences"

        fun nameFor(target: WallpaperTarget): String = when (target) {
            WallpaperTarget.HOME -> PREFERENCES_NAME
            WallpaperTarget.LOCK -> LOCK_PREFERENCES_NAME
        }

        fun key(kind: String, sourceId: String): String = "$kind|$sourceId"

        fun legacyKey(sourceId: String): String = "source:$sourceId"

        /** 从 [all] 的结果中按与 [get] 相同的先后取值。 */
        fun lookup(all: Map<String, String>, kind: String, sourceId: String): String? =
            all[key(kind, sourceId)] ?: all[legacyKey(sourceId)]
    }
}
