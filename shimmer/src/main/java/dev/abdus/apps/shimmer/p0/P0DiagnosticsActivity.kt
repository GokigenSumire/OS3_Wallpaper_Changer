package dev.abdus.apps.shimmer.p0

import android.app.WallpaperManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import dev.abdus.apps.shimmer.BuildConfig
import dev.abdus.apps.shimmer.p1.P1SourcesActivity
import dev.abdus.apps.shimmer.p2.P2PlaybackActivity
import dev.abdus.apps.shimmer.recycleAll
import java.util.concurrent.Executors

class P0DiagnosticsActivity : ComponentActivity() {
    private lateinit var summary: TextView
    private lateinit var engineActions: LinearLayout
    private lateinit var output: TextView
    private lateinit var recentEvents: TextView
    private var displayedControls: List<P0Controls.EngineControl>? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var importGeneration = 0
    private var stopped = false
    private var resumed = false
    private val page by lazy { ViewModelProvider(this)[DiagnosticPageSession::class.java] }
    private val statusRefresh = Runnable { if (!stopped && resumed) refresh() }

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val captured = page.takeExport()
        val displayVersion = page.displayVersion()
        if (uri == null) {
            if (!stopped) output.text = "已取消导出，诊断记录保留。"
        } else if (captured == null) {
            if (!stopped) output.text = "原导出快照已不可用；没有写入文件，请重新导出。"
        } else {
            worker.execute {
            try {
                DiagnosticExportWriter.write(contentResolver, uri, captured.content)
                main.post {
                    if (!stopped && page.acceptsDisplay(displayVersion, captured.token)) {
                        output.text = "诊断已导出：${captured.fileName}；仅回传需要的测试摘要。"
                    }
                }
            } catch (failed: Exception) {
                main.post {
                    if (!stopped && page.acceptsDisplay(displayVersion, captured.token)) {
                        output.text = "导出失败（${failed.javaClass.simpleName}），既有日志保留。"
                    }
                }
            }
            }
        }
    }
    private val selectTestImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val generation = ++importGeneration
            val diagnosticToken = P0Diagnostics.captureToken()
            val displayVersion = page.displayVersion()
            worker.execute {
                var valid = false
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val image = P0TestImage.decode(this, uri)
                    valid = image != null
                    image?.recycleAll()
                } catch (failed: Exception) {
                    P0Diagnostics.event("APP", "TEST_IMAGE_REJECTED", "type=${failed.javaClass.simpleName} configRetained=true", diagnosticToken)
                }
                main.post {
                    if (stopped || generation != importGeneration) return@post
                    if (valid) {
                        P0TestImage.commit(this, uri)
                        // Selecting a fixture is explicit; preview gesture recognition never performs it.
                        P0Controls.snapshot().forEach { it.reload() }
                        P0Diagnostics.event("APP", "TEST_IMAGE_COMMITTED", "testImage=USER_TEST", diagnosticToken)
                        if (page.acceptsDisplay(displayVersion, diagnosticToken)) {
                            output.text = "测试图已选定。只显示 USER_TEST 代号；P0 不提供图库、排序或编辑功能。"
                        }
                    } else if (page.acceptsDisplay(displayVersion, diagnosticToken)) {
                        output.text = "测试图暂不可读，原选择和已提交配置保留。"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = p0Column()
        column.button("返回设置") { finish() }
        column.label("诊断与排查", 23f)
        column.label("${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}） · ${BuildConfig.APPLICATION_ID}")
        output = column.label("诊断不会自动启用或清空。开始记录后返回桌面复现问题，再导出结果。")

        val stages = column.diagnosticSection("阶段入口 · P0～P11", tag = "diagnostic-stages")
        DiagnosticStageEntries.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { entry ->
                row.addView(Button(this).apply {
                    text = "P${entry.stage} ${entry.title}" + if (!entry.available) "\n暂未开放" else ""
                    isAllCaps = false
                    isEnabled = entry.available
                    minHeight = (56 * resources.displayMetrics.density).toInt()
                    setOnClickListener {
                        if (entry.stage == 0) window.decorView.findViewWithTag<LinearLayout>("diagnostic-records")
                            ?.requestRectangleOnScreen(android.graphics.Rect(0, 0, 1, 1), true)
                        else DiagnosticStageEntries.intent(this@P0DiagnosticsActivity, entry.stage)?.let(::startActivity)
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            stages.addView(row)
        }
        stages.label("P6／P7按原计划延期。P2首次使用且未选择验收模式时会默认开启静态验收；P3为桌面编辑，锁屏请走安全编辑入口。")

        val record = column.diagnosticSection("记录与导出", tag = "diagnostic-records")
        record.diagnosticButtonRow("开始诊断（保留已有记录）" to {
            page.invalidateDisplay(); P0Diagnostics.setEnabled(true); refresh()
        }, "停止诊断" to {
            page.invalidateDisplay(); P0Diagnostics.setEnabled(false); refresh()
        })
        record.button("导出当前诊断摘要与事件") {
            val captured = page.beginExport(P0Diagnostics.snapshot("EXPORT_METADATA"))
            if (captured == null) output.text = "已有导出选择尚未结束；本轮诊断仍保留。"
            else try { exportDocument.launch(captured.fileName) }
            catch (_: Exception) { page.takeExport(); output.text = "无法打开文件选择器；诊断记录保留。" }
        }
        record.button("清空并开始新诊断") { confirmNewRun() }
        record.label("清空需要确认，只影响诊断事件，不删除图片、来源、历史或编辑。")

        val status = column.diagnosticSection("运行摘要", tag = "diagnostic-summary")
        status.button("刷新摘要与 Engine 列表") { refresh() }
        summary = status.label("")
        val events = column.diagnosticSection("最近事件", collapsed = true, tag = "diagnostic-events")
        recentEvents = events.label("")

        engineActions = column.diagnosticSection("壁纸画面重建工具", collapsed = true, tag = "diagnostic-engines")
        val lab = column.diagnosticSection("旧版工具与实验入口", collapsed = true, tag = "diagnostic-labs")
        lab.label("仅在排查时使用。旧播放控制首次可能默认开启静态验收；实验壁纸不会替代正式播放功能。")
        lab.diagnosticButtonRow("打开 P1 来源与数据" to {
            startActivity(Intent(this, P1SourcesActivity::class.java))
        }, "打开 P2 播放控制" to {
            startActivity(Intent(this, P2PlaybackActivity::class.java))
        })
        lab.button("选择无隐私测试图片（可选）") { selectTestImage.launch(arrayOf("image/*")) }
        lab.button("P0实验壁纸预览／设置（不是P2）") {
            try {
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, P0WallpaperService::class.java)))
            } catch (failed: Exception) {
                output.text = "系统未打开壁纸选择器，请从系统设置选择 OS3 Wallpaper P0。"
                P0Diagnostics.event("APP", "PICKER_FAILED", "type=${failed.javaClass.simpleName}")
            }
        }
        lab.button("应用内编辑实验入口（对照）") { P0EditActivity.request(this, "APP_CONTROL", "C_OR_USER_TEST") }
        lab.button("打开真实 Surface 重建实验室") { startActivity(Intent(this, P0SurfaceLabActivity::class.java)) }

        listOf("diagnostic-events", "diagnostic-engines", "diagnostic-labs").forEach { tag ->
            if (savedInstanceState?.getBoolean(tag) == true) {
                val body = window.decorView.findViewWithTag<LinearLayout>(tag)
                body.visibility = android.view.View.VISIBLE
                val header = (body.parent as LinearLayout).getChildAt(0) as Button
                header.text = "${header.contentDescription} · 收起"
                header.stateDescription = "已展开"
            }
        }

        refresh()
    }

    override fun onResume() { super.onResume(); resumed = true; if (::summary.isInitialized) refresh() }
    override fun onSaveInstanceState(outState: Bundle) {
        listOf("diagnostic-events", "diagnostic-engines", "diagnostic-labs").forEach { tag ->
            outState.putBoolean(tag, window.decorView.findViewWithTag<LinearLayout>(tag)?.visibility == android.view.View.VISIBLE)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { resumed = false; main.removeCallbacks(statusRefresh); super.onPause() }
    private fun confirmNewRun() {
        AlertDialog.Builder(this)
            .setTitle("清空并开始新诊断")
            .setMessage("将清空本应用保存的诊断事件和诊断计数，并开始新的诊断会话。\n" +
                "不会删除图片、来源、播放历史、手动排序、编辑参数或已导出的日志。\n" +
                "如需保留当前诊断，请先导出。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空并开始") { _, _ ->
                page.invalidateDisplay()
                val token = P0Diagnostics.resetAndStart()
                output.text = "已开始新运行 ${token.runId}；旧诊断清除与新记录正在后台保存，请查看摘要持久化状态。"
                refresh()
            }.show()
    }
    private fun refresh() {
        val snapshot = P0Diagnostics.snapshot("UI_REFRESH")
        summary.text = snapshot.summary
        recentEvents.text = snapshot.tail.ifBlank { "暂无事件，完整记录可从上方导出。" }
        main.removeCallbacks(statusRefresh)
        if (resumed) main.postDelayed(statusRefresh, 1000)
        val controls = P0Controls.snapshot()
        if (controls != displayedControls) {
            displayedControls = controls
            engineActions.removeAllViews()
            engineActions.label("隐藏时等待下次可见执行；以下是EGL重建，不等同系统重新创建SurfaceHolder。")
            if (controls.isEmpty()) engineActions.label("暂无活动壁纸实例，请先应用正式壁纸后刷新。")
            controls.forEach { control ->
                engineActions.label(control.scope)
                engineActions.button("重建该 Engine 的 EGLSurface") {
                    control.rebuild(false); output.text = "已请求；返回桌面后检查实际重建事件。"
                }
                engineActions.button("重建该 Engine 的 EGL context") {
                    control.rebuild(true); output.text = "已请求；返回桌面后检查 context 代数。"
                }
            }
        }
    }
    override fun onDestroy() {
        stopped = true
        main.removeCallbacks(statusRefresh)
        importGeneration++
        worker.shutdownNow()
        super.onDestroy()
    }
}

internal fun ComponentActivity.p0Column(): LinearLayout {
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { addView(column) }
    ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val margin = (16 * resources.displayMetrics.density).toInt()
        view.setPadding(bars.left + margin, bars.top + margin, bars.right + margin, bars.bottom + margin)
        insets
    }
    setContentView(scroll)
    ViewCompat.requestApplyInsets(scroll)
    return column
}
internal fun LinearLayout.label(value: String, size: Float = 15f): TextView = TextView(context).apply {
    text = value; textSize = size; setTextIsSelectable(true); setPadding(0, 12, 0, 12)
    this@label.addView(this)
}
internal fun LinearLayout.button(value: String, action: () -> Unit): Button = Button(context).apply {
    text = value; setOnClickListener { action() }; this@button.addView(this)
}


private fun LinearLayout.diagnosticSection(title: String, collapsed: Boolean = false, tag: String): LinearLayout {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val section = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (night) 0x188AB4F8 else 0x10174EA6)
        }
    }
    addView(section, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
    val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        this.tag = tag
        visibility = if (collapsed) android.view.View.GONE else android.view.View.VISIBLE
    }
    if (collapsed) section.button("$title · 展开") {
        body.visibility = if (body.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        (section.getChildAt(0) as Button).text = "$title · " + if (body.visibility == android.view.View.VISIBLE) "收起" else "展开"
        (section.getChildAt(0) as Button).stateDescription = if (body.visibility == android.view.View.VISIBLE) "已展开" else "已收起"
    }.apply { isAllCaps = false; contentDescription = title; stateDescription = "已收起" }
    else section.label(title, 18f)
    section.addView(body)
    return body
}

private fun LinearLayout.diagnosticButtonRow(vararg actions: Pair<String, () -> Unit>) {
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    actions.forEach { (title, action) ->
        row.addView(Button(context).apply {
            text = title; isAllCaps = false
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    addView(row)
}
