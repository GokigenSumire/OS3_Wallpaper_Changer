package dev.abdus.apps.shimmer.p5

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.abdus.apps.shimmer.WallpaperPreferences

/**
 * P5 transition page: effect, duration and slide direction, plus the diagnostics of the frozen
 * thresholds. Every change is saved at once; the wallpaper reads the `p5_transition` preferences
 * at each switch, so a change applies from the next switch.
 */
class P5TransitionActivity : ComponentActivity() {
    private lateinit var store: TransitionSettingsStore
    private lateinit var preferences: WallpaperPreferences
    private lateinit var settingsBox: LinearLayout
    private lateinit var diagnostics: TextView
    private var settings = TransitionSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TransitionSettingsStore(this)
        preferences = WallpaperPreferences.create(this)
        settings = store.load()
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        settings = store.load()
        renderSettings()
        refreshDiagnostics()
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

        column.addView(label("P5 过渡效果", 24f))
        column.addView(label(
            "设置立即保存，从下一次切换开始生效；正在进行的过渡不受影响。同一张图片重新装载（如切换模糊）固定用淡入淡出。" +
                "特效设置里的“过渡时长”只影响模糊、暗化等特效，不再影响图片切换。",
        ))
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(settingsBox)

        column.addView(label("诊断", 18f))
        column.addView(label(
            "只记录桌面上的正式壁纸，不记录系统预览和应用内预览。阈值测量从“清空诊断记录”开始，测量时请关闭本页回到桌面切换。" +
                "诊断保存在内存中，应用进程被系统结束后会清零。回传时点击“复制诊断”，粘贴全部内容。",
        ))
        column.addView(button("刷新诊断") { refreshDiagnostics() })
        column.addView(button("复制诊断") { copyDiagnostics() })
        column.addView(button("清空诊断记录（开始新一轮测量）") { confirmClearDiagnostics() })
        diagnostics = label("")
        column.addView(diagnostics)
    }

    /** Rebuilds the settings rows from [settings]. */
    private fun renderSettings() {
        settingsBox.removeAllViews()
        if (preferences.isP2StaticTestMode()) {
            settingsBox.addView(label("注意：P2 静态验收模式已开启，切换一律无动画；关闭静态验收模式后这里的设置才生效。"))
        }
        settingsBox.addView(label("效果", 18f))
        settingsBox.addView(choiceGroup(TransitionReport.CHOICE_LABELS, settings.choice) {
            save(settings.copy(choice = it))
        })
        settingsBox.addView(label("随机效果每次在淡入淡出、淡出到背景再淡入、方向滑动、缩放中选一种，不与上一次相同。“背景”为纯黑。"))
        settingsBox.addView(label("过渡时长", 18f))
        val durations = TransitionSettings.DURATION_PRESETS.associateWith { "$it ms" }
        settingsBox.addView(choiceGroup(durations, settings.normalized().durationMillis) {
            save(settings.copy(durationMillis = it))
        })
        settingsBox.addView(label("滑动方向（方向滑动和随机效果抽到滑动时使用）", 18f))
        settingsBox.addView(choiceGroup(TransitionReport.DIRECTION_LABELS, settings.direction) {
            save(settings.copy(direction = it))
        })
        settingsBox.addView(label("跟随播放方向：下一张时新图从右侧进入，上一张时从左侧进入。"))
    }

    private fun save(next: TransitionSettings) {
        settings = next.normalized()
        store.save(settings)
        renderSettings()
        refreshDiagnostics()
    }

    /** One radio button per option; the tag holds the option's index, so no cast is unchecked. */
    private fun <T> choiceGroup(options: Map<T, String>, selected: T, onSelect: (T) -> Unit): RadioGroup {
        val values = options.keys.toList()
        val group = RadioGroup(this)
        values.forEachIndexed { index, value ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = options.getValue(value)
                tag = index
                isChecked = value == selected
            })
        }
        group.setOnCheckedChangeListener { radios, checkedId ->
            val index = radios.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            if (values[index] != selected) onSelect(values[index])
        }
        return group
    }

    private fun diagnosticsText(): String = TransitionReport.format(TransitionStats.shared.snapshot(), settings)

    private fun refreshDiagnostics() {
        diagnostics.text = diagnosticsText()
    }

    /** Copies a fresh reading, not whatever the page last showed. */
    private fun copyDiagnostics() {
        val text = diagnosticsText()
        diagnostics.text = text
        val report = "P5 过渡诊断\n$text"
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("P5 过渡诊断", report))
        Toast.makeText(this, "已复制诊断", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearDiagnostics() {
        AlertDialog.Builder(this)
            .setTitle("清空诊断记录？")
            .setMessage("将清空过渡统计和最近过渡，用于开始新一轮阈值测量；不改变过渡设置和当前图片。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                TransitionStats.shared.reset()
                refreshDiagnostics()
            }
            .show()
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
