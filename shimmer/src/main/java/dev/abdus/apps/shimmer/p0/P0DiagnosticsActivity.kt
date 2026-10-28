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
        column.label("壁纸输入与生命周期诊断 · P0", 23f)
        column.label("${BuildConfig.APPLICATION_ID}\n${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "此页可观察当前P2正式壁纸，无须切成P0实验壁纸。诊断不修复OS4手势；" +
            "P2保留原切图／编辑行为。下方P0实验入口仅作对照，实验NEXT/PREV只计数。")
        column.button("清空并开始新诊断") { confirmNewRun() }
        column.button("开始诊断（保留已有记录）") {
            page.invalidateDisplay(); P0Diagnostics.setEnabled(true); refresh()
        }
        column.button("停止诊断") {
            page.invalidateDisplay(); P0Diagnostics.setEnabled(false); refresh()
        }
        column.button("打开 P1 来源与数据") {
            startActivity(Intent(this, P1SourcesActivity::class.java))
        }
        column.button("打开 P2 播放控制") {
            startActivity(Intent(this, P2PlaybackActivity::class.java))
        }
        column.button("选择无隐私测试图片（可选）") { selectTestImage.launch(arrayOf("image/*")) }
        column.button("P0实验壁纸预览／设置（不是P2）") {
            try {
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, P0WallpaperService::class.java)))
            } catch (failed: Exception) {
                output.text = "系统未打开壁纸选择器，请从系统设置选择 OS3 Wallpaper P0。"
                P0Diagnostics.event("APP", "PICKER_FAILED", "type=${failed.javaClass.simpleName}")
            }
        }
        column.button("应用内编辑实验入口（对照）") { P0EditActivity.request(this, "APP_CONTROL", "C_OR_USER_TEST") }
        column.button("打开真实 Surface 重建实验室") { startActivity(Intent(this, P0SurfaceLabActivity::class.java)) }
        column.button("刷新摘要与 Engine 列表") { refresh() }
        column.button("导出当前诊断摘要与事件") {
            val captured = page.beginExport(P0Diagnostics.snapshot("EXPORT_METADATA"))
            if (captured == null) {
                output.text = "已有导出选择尚未结束；本轮诊断仍保留。"
            } else {
                try { exportDocument.launch(captured.fileName) }
                catch (failed: Exception) {
                    page.takeExport()
                    output.text = "无法打开文件选择器；诊断记录保留。"
                }
            }
        }
        column.label("以下重建针对选定 Engine 的 EGLSurface/context，隐藏时等待下次可见执行；" +
            "它们不等于 Android SurfaceHolder 被系统重新创建。")
        engineActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(engineActions)
        summary = column.label("")
        output = column.label("先清空并开始一次新诊断，再返回当前正式壁纸的解锁桌面操作；" +
            "开始时应用遮挡桌面、visible=false是正常的。请求接收触摸不代表系统保证送达。")
        refresh()
    }

    override fun onResume() { super.onResume(); resumed = true; if (::summary.isInitialized) refresh() }
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
        summary.text = snapshot.summary + "\n最近事件（完整事件可导出）\n" + snapshot.tail
        main.removeCallbacks(statusRefresh)
        if (resumed) main.postDelayed(statusRefresh, 1000)
        engineActions.removeAllViews()
        P0Controls.snapshot().forEach { control ->
            engineActions.label(control.scope)
            engineActions.button("重建该 Engine 的 EGLSurface") { control.rebuild(false); output.text = "已请求；返回桌面后检查实际重建事件。" }
            engineActions.button("重建该 Engine 的 EGL context") { control.rebuild(true); output.text = "已请求；返回桌面后检查 context 代数。" }
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
