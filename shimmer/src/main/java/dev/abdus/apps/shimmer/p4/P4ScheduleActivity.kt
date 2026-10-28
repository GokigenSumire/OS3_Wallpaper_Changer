package dev.abdus.apps.shimmer.p4

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p1.P1SourceKind
import dev.abdus.apps.shimmer.p1.SourcePathFormatter
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * P4 scheduling page: triggers, daily times, date rules and the diagnostics used for the frozen
 * thresholds. Every change is saved at once; the scheduler in the wallpaper process picks it up
 * through the `p4_schedule` preferences.
 */
class P4ScheduleActivity : ComponentActivity() {
    private var wallpaperTarget = WallpaperTarget.HOME
    private lateinit var store: ScheduleStore
    private lateinit var schedule: ScheduleCoordinator
    private lateinit var repository: ImageFolderRepository
    private lateinit var settingsBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var catalogLine: TextView
    private lateinit var diagnostics: TextView
    private var settings = ScheduleSettings()
    /** P1 sources for the rule editor, loaded off the main thread. */
    private var sources: List<P1SourceEntity> = emptyList()
    private var sourcesLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = SchedulePageTarget.read(intent)
        if (target == null) {
            Toast.makeText(this, "调度目标无效，未读取或修改桌面、锁屏设置。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        wallpaperTarget = target
        store = ScheduleStore(this, wallpaperTarget)
        schedule = ScheduleCoordinator.get(this, wallpaperTarget)
        repository = ImageFolderRepository(this, lifecycleScope, wallpaperTarget)
        settings = store.loadSettings()
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (!::store.isInitialized) return
        settings = store.loadSettings()
        renderSettings()
        loadSources()
        refreshStatus()
    }

    private fun buildScreen() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)

        column.addView(label("${if (wallpaperTarget == WallpaperTarget.HOME) "桌面 HOME" else "锁屏 LOCK"} · 调度与日期", 24f))
        column.addView(label(
            "只在壁纸可见时计时和检查；壁纸不可见期间到期的切换会合并，在下一次可见时只切一次，不补播。" +
                "本应用不使用闹钟、唤醒锁或后台服务，不申请新权限。双击、三击等手动切换不受这里的开关限制。",
        ))
        status = label("正在读取调度状态…")
        column.addView(status)
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(settingsBox)

        column.addView(label("诊断", 18f))
        column.addView(label("阈值测量从“清空诊断记录”开始。回传时点击“复制诊断”，粘贴全部内容。"))
        catalogLine = label("")
        column.addView(catalogLine)
        column.addView(button("刷新状态") { refreshStatus() })
        column.addView(button("复制诊断") { copyDiagnostics() })
        column.addView(button("清空诊断记录（开始新一轮测量）") { confirmClearDiagnostics() })
        diagnostics = label("")
        column.addView(diagnostics)
    }

    /** Rebuilds every settings row from [settings]; cheap, the lists are short. */
    private fun renderSettings() {
        settingsBox.removeAllViews()
        settingsBox.addView(button(
            if (settings.pauseAll) "暂停全部调度：已暂停（点击恢复）" else "暂停全部调度：未暂停（点击暂停）",
        ) { save(settings.copy(pauseAll = !settings.pauseAll)) })
        settingsBox.addView(label("暂停后不派发任何调度切换，日期规则仍决定有效目录；手动切换不受影响。"))
        addIntervalSection()
        addLockSection()
        addDailySection()
        addRuleSection()
    }

    private fun save(next: ScheduleSettings, message: String? = null) {
        settings = next
        store.saveSettings(next)
        renderSettings()
        refreshStatus()
        message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun addIntervalSection() {
        val interval = settings.interval
        settingsBox.addView(label("间隔切换", 18f))
        settingsBox.addView(button(
            "间隔切换：${if (interval.enabled) "开" else "关"}（每 ${interval.value} ${interval.unit.label}）",
        ) { save(settings.copy(interval = interval.copy(enabled = !interval.enabled))) })
        val value = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(interval.value.toString())
            contentDescription = "间隔数值"
            hint = "间隔数值"
        }
        settingsBox.addView(value)
        var unit = interval.unit
        lateinit var unitButton: Button
        unitButton = button("单位：${unit.label}（点击切换）") {
            unit = IntervalUnit.entries[(unit.ordinal + 1) % IntervalUnit.entries.size]
            unitButton.text = "单位：${unit.label}（点击切换）"
        }
        settingsBox.addView(unitButton)
        settingsBox.addView(button("保存间隔") {
            val number = value.text.toString().trim().toIntOrNull()
            val next = interval.copy(value = number ?: 0, unit = unit)
            if (number == null || number <= 0 || !next.valid) {
                status.text = "间隔无效：范围为 10 秒～24 小时。"
                return@button
            }
            save(settings.copy(interval = next), "已保存间隔：${next.value} ${next.unit.label}，重新计时")
        })
        settingsBox.addView(button(
            "计时方式：" + if (interval.clock == IntervalClock.VISIBLE) "可见累计（点击改为自然时间）" else "自然时间（点击改为可见累计）",
        ) {
            val clock = if (interval.clock == IntervalClock.VISIBLE) IntervalClock.WALL else IntervalClock.VISIBLE
            save(settings.copy(interval = interval.copy(clock = clock)))
        })
        settingsBox.addView(label(
            "可见累计：只累计壁纸显示在屏幕上的时间。自然时间：按实际经过的时间，到期时若壁纸不可见，下一次可见时切换。" +
                "任何切换（含手动）、修改间隔和编辑结束都会重新计时；编辑中到期的间隔会被丢弃。",
        ))
    }

    private fun addLockSection() {
        settingsBox.addView(label("锁屏、熄屏与解锁", 18f))
        settingsBox.addView(toggle("锁屏后切换（下一次可见时）", settings.onLock) { save(settings.copy(onLock = it)) })
        settingsBox.addView(toggle("熄屏后切换（下一次可见时）", settings.onScreenOff) { save(settings.copy(onScreenOff = it)) })
        settingsBox.addView(toggle("解锁时切换", settings.onUnlock) { save(settings.copy(onUnlock = it)) })
        settingsBox.addView(label(
            "从熄屏到解锁为一个锁屏周期，三项合计每个周期至多切换一次；周期内已发生任何切换（包括间隔、每日定时和手动），之后的锁屏、熄屏、解锁不再切换。",
        ))
    }

    private fun addDailySection() {
        settingsBox.addView(label("每日定时", 18f))
        if (settings.dailyTimes.isEmpty()) settingsBox.addView(label("尚未添加每日定时。"))
        settings.dailyTimes.sortedBy { it.minuteOfDay }.forEach { daily ->
            settingsBox.addView(label("${ScheduleFormat.hhmm(daily.minuteOfDay)}（${if (daily.enabled) "启用" else "停用"}）"))
            settingsBox.addView(buttonRow(
                button(if (daily.enabled) "停用" else "启用") {
                    // Enabling counts as a change: an occurrence already past today does not fire.
                    replaceDaily(daily.copy(enabled = !daily.enabled, editedAtWall = System.currentTimeMillis()))
                },
                button("修改") {
                    askTime("修改每日定时", ScheduleFormat.hhmm(daily.minuteOfDay)) { minute ->
                        replaceDaily(daily.copy(minuteOfDay = minute, editedAtWall = System.currentTimeMillis()))
                    }
                },
                button("删除") {
                    save(settings.copy(dailyTimes = settings.dailyTimes.filterNot { it.id == daily.id }), "已删除定时")
                },
            ))
        }
        settingsBox.addView(button("添加每日定时") {
            askTime("添加每日定时", "08:00") { minute ->
                val added = DailyTime(UUID.randomUUID().toString(), minute, editedAtWall = System.currentTimeMillis())
                replaceDaily(added)
            }
        })
        settingsBox.addView(label("按系统当前时区的本地时间，每个定时每天至多切换一次；保存时今天已经过去的时刻，今天不再触发。到点时壁纸不可见，就在下一次可见时切换。"))
    }

    /** Adds or replaces [daily]; two entries at the same minute would only ever merge. */
    private fun replaceDaily(daily: DailyTime): Boolean {
        val others = settings.dailyTimes.filterNot { it.id == daily.id }
        if (others.any { it.minuteOfDay == daily.minuteOfDay }) {
            status.text = "已有 ${ScheduleFormat.hhmm(daily.minuteOfDay)} 的定时，未重复添加。"
            return false
        }
        save(settings.copy(dailyTimes = others + daily), "已保存定时 ${ScheduleFormat.hhmm(daily.minuteOfDay)}")
        return true
    }

    /** Asks for "HH:mm"; the dialog stays open on invalid input. */
    private fun askTime(title: String, initial: String, done: (Int) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            hint = "例如 08:30"
            contentDescription = "时间"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("输入 24 小时制时间，格式 HH:mm")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val minute = ScheduleFormat.parseTime(input.text.toString())
            if (minute == null) {
                input.error = "格式应为 HH:mm，例如 08:30"
                return@setOnClickListener
            }
            dialog.dismiss()
            done(minute)
        }
    }

    private fun addRuleSection() {
        settingsBox.addView(label("日期与时段规则", 18f))
        settingsBox.addView(label(
            "按列表顺序，第一个匹配当前日期/时段、且来源中有可播图片的规则生效，其来源替代普通目录；没有匹配时回到 P1 已启用的来源。" +
                "规则可选用在 P1 中已停用的来源，子目录停用仍然有效。规则开始或结束时，当前图片仍在新目录中就不切换。",
        ))
        // Until the sources are read, nothing is reported as deleted.
        val known = if (sourcesLoaded) sources.map { it.id }.toSet() else settings.rules.flatMap { it.sourceIds }.toSet()
        if (settings.rules.isEmpty()) settingsBox.addView(label("尚未添加规则。"))
        settings.rules.forEachIndexed { index, rule ->
            settingsBox.addView(label("${index + 1}. ${rule.displayName}（${if (rule.enabled) "启用" else "停用"}）"))
            settingsBox.addView(label(ScheduleFormat.describe(rule, known)))
            settingsBox.addView(buttonRow(
                button(if (rule.enabled) "停用" else "启用") { replaceRule(rule.copy(enabled = !rule.enabled)) },
                button("上移") { moveRule(index, -1) },
                button("下移") { moveRule(index, 1) },
                button("修改") { editRule(rule) },
                button("删除") { confirmDeleteRule(rule) },
            ))
        }
        settingsBox.addView(button("添加规则") { editRule(null) })
    }

    private fun replaceRule(rule: PlaybackRule) {
        val index = settings.rules.indexOfFirst { it.id == rule.id }
        val rules = if (index < 0) settings.rules + rule else settings.rules.toMutableList().also { it[index] = rule }
        save(settings.copy(rules = rules), "已保存规则：${rule.displayName}")
    }

    private fun moveRule(index: Int, delta: Int) {
        val target = index + delta
        if (target !in settings.rules.indices) return
        val rules = settings.rules.toMutableList()
        rules[index] = rules[target].also { rules[target] = rules[index] }
        save(settings.copy(rules = rules), "已调整优先级")
    }

    private fun confirmDeleteRule(rule: PlaybackRule) {
        AlertDialog.Builder(this)
            .setTitle("删除规则？")
            .setMessage("将删除“${rule.displayName}”，不影响 P1 来源和图片。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                save(settings.copy(rules = settings.rules.filterNot { it.id == rule.id }), "已删除规则")
            }
            .show()
    }

    /** Editor for a new ([existing] null) or existing rule; stays open until the rule is valid. */
    private fun editRule(existing: PlaybackRule?) {
        if (sources.isEmpty()) {
            status.text = if (sourcesLoaded) "还没有 P1 来源；先在 P1 中添加来源，再创建规则。" else "正在读取 P1 来源，请稍后再试。"
            return
        }
        val initial = existing?.let(ScheduleFormat::form) ?: RuleForm(id = UUID.randomUUID().toString())
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val name = field("规则名称（可留空）", initial.name)
        form.addView(name)
        form.addView(label("重复方式"))
        val repeats = RuleRepeat.entries
        val repeatLabels = mapOf(
            RuleRepeat.DAILY to "每天",
            RuleRepeat.DATE_RANGE to "指定日期或日期范围",
            RuleRepeat.YEARLY to "每年（月日范围，可跨年）",
            RuleRepeat.WEEKLY to "每周（星期几）",
        )
        val repeatGroup = RadioGroup(this)
        repeats.forEach { repeat ->
            repeatGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = repeatLabels.getValue(repeat)
                tag = repeat
                isChecked = repeat == initial.repeat
            })
        }
        form.addView(repeatGroup)

        val dateBox = group()
        val startDate = field("开始日期，例如 2026-10-01", initial.startDate)
        val endDate = field("结束日期，例如 2026-10-07（留空为单日）", initial.endDate)
        dateBox.addView(startDate)
        dateBox.addView(endDate)
        form.addView(dateBox)

        val yearBox = group()
        val startMonthDay = field("每年开始月日，例如 12-20", initial.startMonthDay)
        val endMonthDay = field("每年结束月日，例如 01-05", initial.endMonthDay)
        yearBox.addView(startMonthDay)
        yearBox.addView(endMonthDay)
        yearBox.addView(label("开始晚于结束表示跨年；2 月 29 日在平年按 2 月 28 日处理。"))
        form.addView(yearBox)

        val weekBox = group()
        val weekChecks = (1..7).map { day ->
            CheckBox(this).apply {
                text = "星期" + ScheduleFormat.weekdayText(day)
                isChecked = day in initial.weekdays
            }.also(weekBox::addView)
        }
        form.addView(weekBox)

        val windowCheck = CheckBox(this).apply {
            text = "只在每天某个时段生效（不勾选为全天）"
            isChecked = initial.windowOn
        }
        form.addView(windowCheck)
        val windowBox = group()
        val windowStart = field("时段开始，例如 22:00", initial.windowStart)
        val windowEnd = field("时段结束，例如 02:00", initial.windowEnd)
        windowBox.addView(windowStart)
        windowBox.addView(windowEnd)
        windowBox.addView(label("开始晚于结束表示跨过午夜，结束时间属于次日。"))
        form.addView(windowBox)

        form.addView(label("来源（可多选）"))
        val sourceChecks = sources.map { source ->
            CheckBox(this).apply {
                text = sourceLabel(source)
                tag = source.id
                isChecked = source.id in initial.sourceIds
            }.also(form::addView)
        }
        // P8 纠正：深色模式下 #B00020 在深色对话框背景上对比度不足（2.25:1）；改用条件色。
        val error = label("").apply {
            setTextColor(if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xFFFFB4AB.toInt() else 0xFFB00020.toInt())
        }
        form.addView(error)

        fun showFor(repeat: RuleRepeat) {
            dateBox.visibility = if (repeat == RuleRepeat.DATE_RANGE) View.VISIBLE else View.GONE
            yearBox.visibility = if (repeat == RuleRepeat.YEARLY) View.VISIBLE else View.GONE
            weekBox.visibility = if (repeat == RuleRepeat.WEEKLY) View.VISIBLE else View.GONE
        }
        fun checkedRepeat(): RuleRepeat =
            repeatGroup.findViewById<RadioButton>(repeatGroup.checkedRadioButtonId)?.tag as? RuleRepeat ?: RuleRepeat.DAILY
        showFor(initial.repeat)
        repeatGroup.setOnCheckedChangeListener { _, _ -> showFor(checkedRepeat()) }
        windowBox.visibility = if (initial.windowOn) View.VISIBLE else View.GONE
        windowCheck.setOnCheckedChangeListener { _, on -> windowBox.visibility = if (on) View.VISIBLE else View.GONE }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加规则" else "修改规则")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // Ids of sources deleted from P1 are dropped on save; they could never match again.
            val parsed = ScheduleFormat.toRule(
                initial.copy(
                    name = name.text.toString(),
                    repeat = checkedRepeat(),
                    startDate = startDate.text.toString(),
                    endDate = endDate.text.toString(),
                    startMonthDay = startMonthDay.text.toString(),
                    endMonthDay = endMonthDay.text.toString(),
                    weekdays = (1..7).filter { weekChecks[it - 1].isChecked }.toSet(),
                    windowOn = windowCheck.isChecked,
                    windowStart = windowStart.text.toString(),
                    windowEnd = windowEnd.text.toString(),
                    sourceIds = sourceChecks.filter { it.isChecked }.map { it.tag as String },
                ),
            )
            val rule = parsed.rule
            if (rule == null) {
                error.text = parsed.error
                return@setOnClickListener
            }
            dialog.dismiss()
            replaceRule(rule)
        }
    }

    private fun sourceLabel(source: P1SourceEntity): String {
        val kind = when (source.kind) {
            P1SourceKind.SINGLE_DOCUMENT.name -> "单张图片"
            P1SourceKind.MULTIPLE_DOCUMENTS.name -> "多张图片"
            P1SourceKind.DIRECTORY_TREE.name -> "文件夹目录"
            else -> source.kind
        }
        val disabled = if (source.enabled) "" else "（P1 中已停用）"
        return "$kind：${SourcePathFormatter.shorten(source.rootUri, 48)}$disabled"
    }

    private fun loadSources() {
        lifecycleScope.launch {
            sources = try {
                ShimmerDatabase.getInstance(this@P4ScheduleActivity, wallpaperTarget).p1SourceDao().getSources()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "读取 P1 来源失败：${error.javaClass.simpleName}"
                return@launch
            }
            sourcesLoaded = true
            renderSettings()
        }
    }

    /** Status and diagnostics now, then the effective catalog, which needs the database. */
    private fun refreshStatus() {
        val text = schedule.diagnosticsText()
        status.text = text.substringBefore("\n\n")
        diagnostics.text = text
        lifecycleScope.launch { catalogLine.text = catalogText() }
    }

    private suspend fun catalogText(): String = try {
        val catalog = repository.getPlaybackCatalog()
        "当前有效目录：" + (catalog.activeRule?.let { "规则“${it.displayName}”" } ?: "普通目录（P1 已启用来源）") +
            "，${catalog.items.size} 张" + if (settings.pauseAll) "（调度已暂停，规则仍决定目录）" else ""
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        "当前有效目录读取失败：${error.javaClass.simpleName}"
    }

    /** Copies a fresh reading, not whatever the page last showed. */
    private fun copyDiagnostics() {
        lifecycleScope.launch {
            val catalog = catalogText()
            val text = schedule.diagnosticsText()
            catalogLine.text = catalog
            status.text = text.substringBefore("\n\n")
            diagnostics.text = text
            val report = listOf("P4 调度诊断", catalog, text).joinToString("\n")
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("P4 调度诊断", report))
            Toast.makeText(this@P4ScheduleActivity, "已复制诊断", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearDiagnostics() {
        AlertDialog.Builder(this)
            .setTitle("清空诊断记录？")
            .setMessage("将清空最近事件和统计，用于开始新一轮阈值测量；不改变调度设置、当前图片和计时。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                schedule.clearDiagnostics()
                refreshStatus()
            }
            .show()
    }

    private fun toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit): Button =
        button("$title：${if (value) "开" else "关"}") { onChange(!value) }

    private fun field(hintText: String, value: String): EditText = EditText(this).apply {
        setText(value)
        hint = hintText
        contentDescription = hintText
        isSingleLine = true
    }

    private fun group(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** Equal-width buttons that shrink with the screen instead of running off its edge. */
    private fun buttonRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { item ->
            item.minWidth = 0
            item.minimumWidth = 0
            item.setPadding(dp(4), item.paddingTop, dp(4), item.paddingBottom)
            addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun label(value: String, size: Float = 14f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextIsSelectable(true)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
