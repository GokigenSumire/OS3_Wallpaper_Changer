package dev.abdus.apps.shimmer.ui.playback.schedule

import dev.abdus.apps.shimmer.p4.*
import java.util.UUID

/** All edits read the latest store. A stale dialog cannot recreate a deleted entry. */
class ScheduleEditor(private val store: ScheduleStore, private val now: () -> Long = System::currentTimeMillis) {
    fun saveDaily(id: String?, minute: Int): String? {
        if (minute !in 0..1439) return "请选择有效时间"
        var error: String? = null
        store.updateSettings { current ->
            val old = current.dailyTimes.find { it.id == id }
            when {
                id != null && old == null -> { error = "此定时已删除，请返回列表重新操作"; current }
                current.dailyTimes.any { it.id != id && it.minuteOfDay == minute } -> {
                    error = "已有 ${ScheduleFormat.hhmm(minute)} 的定时"; current
                }
                else -> {
                    val item = DailyTime(id ?: UUID.randomUUID().toString(), minute, old?.enabled ?: true, now())
                    current.copy(dailyTimes = current.dailyTimes.filterNot { it.id == id } + item)
                }
            }
        }
        return error
    }
    fun setDailyEnabled(id: String, enabled: Boolean) = store.updateSettings { current ->
        current.copy(dailyTimes = current.dailyTimes.map {
            if (it.id == id) it.copy(enabled = enabled, editedAtWall = now()) else it
        })
    }
    fun deleteDaily(id: String) = store.updateSettings { it.copy(dailyTimes = it.dailyTimes.filterNot { item -> item.id == id }) }
    fun saveRule(form: RuleForm, existing: Boolean, knownSourceIds: Set<String>): String? {
        if (form.sourceIds.any { it !in knownSourceIds }) return "所选来源已变化，请重新选择来源"
        val parsed = ScheduleFormat.toRule(form)
        val rule = parsed.rule ?: return parsed.error ?: "规则无效"
        var error: String? = null
        store.updateSettings { current ->
            val old = current.rules.find { it.id == rule.id }
            when {
                existing && old == null -> { error = "此规则已删除，请返回列表重新操作"; current }
                !existing && old != null -> { error = "规则标识重复，请重新添加"; current }
                else -> current.copy(rules = if (old == null) current.rules + rule else current.rules.map {
                    if (it.id == rule.id) rule.copy(enabled = it.enabled) else it
                })
            }
        }
        return error
    }
    fun setRuleEnabled(id: String, enabled: Boolean) = store.updateSettings { current ->
        current.copy(rules = current.rules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }
    fun deleteRule(id: String) = store.updateSettings { it.copy(rules = it.rules.filterNot { item -> item.id == id }) }
    fun moveRule(id: String, delta: Int) = store.updateSettings { current ->
        val index = current.rules.indexOfFirst { it.id == id }
        val destination = index + delta
        if (index < 0 || delta !in listOf(-1, 1) || destination !in current.rules.indices) current
        else current.copy(rules = current.rules.toMutableList().apply {
            val other = this[destination]; this[destination] = this[index]; this[index] = other
        })
    }
}
