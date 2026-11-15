package dev.abdus.apps.shimmer.ui.playback.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p4.*
import dev.abdus.apps.shimmer.p1.SourcePathFormatter
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.navigation.RefreshOnResume
import dev.abdus.apps.shimmer.ui.navigation.tabContentPadding
import dev.abdus.apps.shimmer.ui.navigation.wallpaperTargetViewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Product controls only. No diagnostics, legacy Activity or system wallpaper launch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleRulesPage(target: WallpaperTarget, onBack: () -> Unit,
    viewModel: ScheduleRulesViewModel = wallpaperTargetViewModel(target) { app, domain -> ScheduleRulesViewModel(app, domain) }) {
    val state by viewModel.uiState.collectAsState()
    var dailyEditor by rememberSaveable(target) { mutableStateOf<String?>(null) }
    var ruleEditor by rememberSaveable(target) { mutableStateOf<String?>(null) }
    var ruleExisting by rememberSaveable(target) { mutableStateOf(false) }
    var deleteId by rememberSaveable(target) { mutableStateOf<String?>(null) }
    var deleteRule by rememberSaveable(target) { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    RefreshOnResume(viewModel::refresh)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = tabContentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回播放设置") }
                Text("每日定时与日期规则", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.settings.pauseAll) item {
            Text("自动切换已暂停，以下配置仍会保留。返回播放设置恢复后生效。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        state.sourceError?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::retrySources) { Text("重新读取来源") }
        } }
        item {
            SectionHeading("每日定时", "添加定时") { dailyEditor = "" }
            Text("按系统时区每天触发一次；不可见时延后，已过去的时间不补播。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.settings.dailyTimes.isEmpty()) item { EmptyScheduleCard("尚未添加每日定时") }
        items(state.settings.dailyTimes.sortedBy { it.minuteOfDay }, key = { "daily:${it.id}" }) { daily ->
            BlurredCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ScheduleFormat.hhmm(daily.minuteOfDay), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        Switch(daily.enabled, { viewModel.setDailyEnabled(daily.id, it) },
                            modifier = Modifier.semanticsLabel("${ScheduleFormat.hhmm(daily.minuteOfDay)} 定时"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { dailyEditor = daily.id }) { Text("修改时间") }
                        TextButton(onClick = { deleteId = daily.id; deleteRule = false }) { Text("删除") }
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading("日期与时段规则", "添加规则") {
                ruleExisting = false
                ruleEditor = Json.encodeToString(RuleForm(id = UUID.randomUUID().toString()))
            }
            Text("按优先级使用第一个匹配且有可播图片的规则；未匹配时回到普通来源。可选择已停用来源，子目录停用仍有效。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.settings.rules.isEmpty()) item { EmptyScheduleCard("尚未添加日期规则") }
        items(state.settings.rules, key = { "rule:${it.id}" }) { rule ->
            val index = state.settings.rules.indexOfFirst { it.id == rule.id }
            BlurredCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("优先级 ${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(rule.displayName, style = MaterialTheme.typography.titleMedium)
                        }
                        Switch(rule.enabled, { viewModel.setRuleEnabled(rule.id, it) },
                            modifier = Modifier.semanticsLabel("${rule.displayName}规则"))
                    }
                    Text(ScheduleFormat.describe(rule, if (state.sourcesLoading || state.sourceError != null) rule.sourceIds.toSet()
                        else state.sources.map { it.id }.toSet()), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { ruleExisting = true; ruleEditor = Json.encodeToString(ScheduleFormat.form(rule)) }) { Text("修改") }
                        OutlinedButton(onClick = { viewModel.moveRule(rule.id, -1) }, enabled = index > 0) { Text("上移") }
                        OutlinedButton(onClick = { viewModel.moveRule(rule.id, 1) }, enabled = index < state.settings.rules.lastIndex) { Text("下移") }
                        TextButton(onClick = { deleteId = rule.id; deleteRule = true }) { Text("删除") }
                    }
                }
            }
        }
    }
    dailyEditor?.let { id ->
        val existing = state.settings.dailyTimes.find { it.id == id }
        DailyTimeDialog(id, existing?.minuteOfDay ?: 480, onDismiss = { dailyEditor = null }) { minute ->
            viewModel.saveDaily(id.takeIf { it.isNotEmpty() }, minute)
        }
    }
    ruleEditor?.let { encoded ->
        val initial = remember(encoded) { Json.decodeFromString<RuleForm>(encoded) }
        RuleEditorDialog(initial, ruleExisting, state, onDismiss = { ruleEditor = null },
            onSave = { viewModel.saveRule(it, ruleExisting) })
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(if (deleteRule) "删除规则？" else "删除定时？") },
        text = { Text("只删除此配置，保留来源、图片和播放历史。") },
        confirmButton = { TextButton(onClick = {
            if (deleteRule) viewModel.deleteRule(id) else viewModel.deleteDaily(id)
            deleteId = null
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } }) }
}

@Composable
private fun SectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClick) { Icon(Icons.Default.Add, null); Text(action) }
    }
}
@Composable
private fun EmptyScheduleCard(text: String) {
    BlurredCard(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable
private fun DailyTimeDialog(id: String, initialMinute: Int, onDismiss: () -> Unit, onSave: (Int) -> String?) {
    var time by rememberSaveable(id) { mutableStateOf(ScheduleFormat.hhmm(initialMinute)) }
    var error by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val minute = ScheduleFormat.parseTime(time)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (id.isEmpty()) "添加每日定时" else "修改每日定时") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(time, { time = it; error = null }, label = { Text("时间 · 24小时制") },
                placeholder = { Text("08:30") }, singleLine = true, isError = minute == null || error != null)
            Text(error ?: "例如 08:30，每天按此时间触发。", color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        } }, confirmButton = { TextButton(enabled = minute != null, onClick = {
            minute?.let { error = onSave(it); if (error == null) onDismiss() }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private val formSaver = Saver<RuleForm, String>(save = { Json.encodeToString(it) }, restore = {
    runCatching { Json.decodeFromString<RuleForm>(it) }.getOrNull()
})

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorDialog(initial: RuleForm, existing: Boolean, state: ScheduleRulesState,
    onDismiss: () -> Unit, onSave: (RuleForm) -> String?) {
    var form by rememberSaveable(initial.id, stateSaver = formSaver) { mutableStateOf(initial) }
    var error by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing) "修改日期规则" else "添加日期规则") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(form.name, { form = form.copy(name = it) }, label = { Text("规则名称（可留空）") }, singleLine = true)
            Text("重复方式", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RuleRepeat.entries.forEach { repeat ->
                    FilterChip(selected = form.repeat == repeat, onClick = { form = form.copy(repeat = repeat) }, label = { Text(when (repeat) {
                        RuleRepeat.DAILY -> "每天"; RuleRepeat.DATE_RANGE -> "指定日期"; RuleRepeat.YEARLY -> "每年"; RuleRepeat.WEEKLY -> "每周"
                    }) })
                }
            }
            if (form.repeat == RuleRepeat.DATE_RANGE) {
                RuleTextField("开始日期 · 年-月-日", form.startDate) { form = form.copy(startDate = it) }
                RuleTextField("结束日期 · 留空为单日", form.endDate) { form = form.copy(endDate = it) }
            }
            if (form.repeat == RuleRepeat.YEARLY) {
                RuleTextField("开始月日 · 例如12-20", form.startMonthDay) { form = form.copy(startMonthDay = it) }
                RuleTextField("结束月日 · 例如01-05", form.endMonthDay) { form = form.copy(endMonthDay = it) }
                Text("开始晚于结束表示跨年；平年2月29日按2月28日处理。", style = MaterialTheme.typography.bodySmall)
            }
            if (form.repeat == RuleRepeat.WEEKLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { day -> FilterChip(selected = day in form.weekdays, onClick = {
                    form = form.copy(weekdays = if (day in form.weekdays) form.weekdays - day else form.weekdays + day)
                }, label = { Text("周${ScheduleFormat.weekdayText(day)}") }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("限定每日时段", Modifier.weight(1f))
                Switch(form.windowOn, { form = form.copy(windowOn = it) })
            }
            if (form.windowOn) {
                RuleTextField("开始时间 · 例如22:00", form.windowStart) { form = form.copy(windowStart = it) }
                RuleTextField("结束时间 · 例如02:00", form.windowEnd) { form = form.copy(windowEnd = it) }
                Text("开始晚于结束表示跨过午夜，未限定时按全天生效。", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("来源 · 可多选", style = MaterialTheme.typography.titleSmall)
            when {
                state.sourcesLoading -> Text("正在读取来源…")
                state.sourceError != null -> Text(state.sourceError, color = MaterialTheme.colorScheme.error)
                state.sources.isEmpty() -> Text("请先返回图库添加来源，再设置规则。")
            }
            state.sources.forEach { source ->
                val selected = source.id in form.sourceIds
                val choose = { form = form.copy(sourceIds = if (selected) form.sourceIds - source.id else form.sourceIds + source.id) }
                Row(Modifier.fillMaxWidth().clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected, onCheckedChange = null)
                    val label = SourcePathFormatter.shorten(source.rootUri, 48)
                    Text((if (label.startsWith("content://")) "来源 ${source.sourceOrder + 1}" else label) + if (!source.enabled) "（图库已停用）" else "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!state.sourcesLoading && state.sourceError == null && form.sourceIds.any { id -> state.sources.none { it.id == id } }) {
                Text("部分已选来源已被删除，请移除后保存。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { form = form.copy(sourceIds = form.sourceIds.filter { id -> state.sources.any { it.id == id } }) }) { Text("移除失效来源") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !state.sourcesLoading && state.sourceError == null, onClick = {
            error = onSave(form); if (error == null) onDismiss()
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun RuleTextField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

private fun Modifier.semanticsLabel(label: String): Modifier = this.then(Modifier.semantics { contentDescription = label })
