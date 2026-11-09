package dev.abdus.apps.shimmer.p3

import android.content.Context
import androidx.core.content.edit

/** 只决定主动切换图片前如何处理草稿；不改变返回、锁屏或进程中断的处理。 */
internal enum class EditorSwitchSavePolicy(val storedValue: String, val label: String) {
    SAVE("save", "默认保存"), DISCARD("discard", "默认不保存"), ASK("ask", "每次询问");

    val compactLabel: String get() = when (this) {
        SAVE -> "保存"
        DISCARD -> "不存"
        ASK -> "询问"
    }

    fun decide(hasChanges: Boolean): EditorSwitchDecision = when {
        !hasChanges -> EditorSwitchDecision.SWITCH_WITHOUT_SAVE
        this == SAVE -> EditorSwitchDecision.SAVE_AND_SWITCH
        this == DISCARD -> EditorSwitchDecision.SWITCH_WITHOUT_SAVE
        else -> EditorSwitchDecision.ASK
    }

    companion object {
        fun fromStored(value: String?): EditorSwitchSavePolicy =
            entries.firstOrNull { it.storedValue == value } ?: ASK
    }
}

internal enum class EditorSwitchDecision { SAVE_AND_SWITCH, SWITCH_WITHOUT_SAVE, ASK }

internal enum class EditorDockPosition(val storedValue: String) {
    TOP("top"), BOTTOM("bottom");

    fun opposite() = if (this == TOP) BOTTOM else TOP

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: BOTTOM
    }
}

/** 编辑器交互偏好由HOME／LOCK共用，与各域图片变换、播放历史及数据库分开。 */
internal class EditorPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun switchSavePolicy(): EditorSwitchSavePolicy = EditorSwitchSavePolicy.fromStored(
        try { prefs.getString(KEY_SWITCH_SAVE_POLICY, null) } catch (_: ClassCastException) { null },
    )

    fun setSwitchSavePolicy(policy: EditorSwitchSavePolicy) {
        prefs.edit { putString(KEY_SWITCH_SAVE_POLICY, policy.storedValue) }
    }

    fun dockPosition(): EditorDockPosition = EditorDockPosition.fromStored(
        try { prefs.getString(KEY_DOCK_POSITION, null) } catch (_: ClassCastException) { null },
    )

    fun setDockPosition(position: EditorDockPosition) {
        prefs.edit { putString(KEY_DOCK_POSITION, position.storedValue) }
    }

    fun dockVisible(): Boolean = booleanPreference(KEY_DOCK_VISIBLE)
    fun toolsExpanded(): Boolean = booleanPreference(KEY_TOOLS_EXPANDED)

    private fun booleanPreference(key: String): Boolean =
        try { prefs.getBoolean(key, false) } catch (_: ClassCastException) { false }

    fun setDockVisible(visible: Boolean) {
        prefs.edit { putBoolean(KEY_DOCK_VISIBLE, visible) }
    }

    fun setToolsExpanded(expanded: Boolean) {
        prefs.edit { putBoolean(KEY_TOOLS_EXPANDED, expanded) }
    }

    companion object {
        const val PREFS_NAME = "p3_editor_ui"
        const val KEY_SWITCH_SAVE_POLICY = "switch_save_policy"
        const val KEY_DOCK_POSITION = "dock_position"
        const val KEY_DOCK_VISIBLE = "dock_visible"
        const val KEY_TOOLS_EXPANDED = "tools_expanded"
    }
}
