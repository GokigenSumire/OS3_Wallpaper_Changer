package dev.abdus.apps.shimmer.p0

import android.app.WallpaperManager
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
import dev.abdus.apps.shimmer.BuildConfig
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

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val snapshot = P0Diagnostics.export()
            worker.execute {
            try {
                val stream = contentResolver.openOutputStream(uri, "w") ?: error("NO_OUTPUT_STREAM")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                main.post { if (!stopped) output.text = "诊断已导出；仅回传需要的测试摘要。" }
            } catch (failed: Exception) {
                P0Diagnostics.event("APP", "EXPORT_FAILED", "type=${failed.javaClass.simpleName}")
                main.post { if (!stopped) output.text = "导出失败，既有日志保留。" }
            }
            }
        }
    }
    private val selectTestImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val generation = ++importGeneration
            worker.execute {
                var valid = false
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val image = P0TestImage.decode(this, uri)
                    valid = image != null
                    image?.recycleAll()
                } catch (failed: Exception) {
                    P0Diagnostics.event("APP", "TEST_IMAGE_REJECTED", "type=${failed.javaClass.simpleName} configRetained=true")
                }
                main.post {
                    if (stopped || generation != importGeneration) return@post
                    if (valid) {
                        P0TestImage.commit(this, uri)
                        // Selecting a fixture is explicit; preview gesture recognition never performs it.
                        P0Controls.snapshot().forEach { it.reload() }
                        P0Diagnostics.event("APP", "TEST_IMAGE_COMMITTED", "testImage=USER_TEST")
                        output.text = "测试图已选定。只显示 USER_TEST 代号；P0 不提供图库、排序或编辑功能。"
                    } else output.text = "测试图暂不可读，原选择和已提交配置保留。"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = p0Column()
        column.label("OS3 Wallpaper Changer · P0", 23f)
        column.label("${BuildConfig.APPLICATION_ID}\n${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "仅用于三种手势、入口与生命周期验证。NEXT/PREV 只计数，不切图；编辑页不是完整编辑器。")
        column.button("开始诊断（保留已有记录）") { P0Diagnostics.setEnabled(true); refresh() }
        column.button("停止诊断") { P0Diagnostics.setEnabled(false); refresh() }
        column.button("选择无隐私测试图片（可选）") { selectTestImage.launch(arrayOf("image/*")) }
        column.button("打开系统壁纸预览 / 设置") {
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
            exportDocument.launch("WallpaperP0-${P0Diagnostics.session}.txt")
        }
        column.label("以下重建针对选定 Engine 的 EGLSurface/context，隐藏时等待下次可见执行；" +
            "它们不等于 Android SurfaceHolder 被系统重新创建。")
        engineActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(engineActions)
        summary = column.label("")
        output = column.label("默认显示内置 C 标定图。开始诊断后再回桌面做测试；每次操作间隔至少 1 秒。")
        refresh()
    }

    override fun onResume() { super.onResume(); if (::summary.isInitialized) refresh() }
    private fun refresh() {
        summary.text = P0Diagnostics.summary() + "\n最近事件（完整事件可导出）\n" + P0Diagnostics.tail()
        engineActions.removeAllViews()
        P0Controls.snapshot().forEach { control ->
            engineActions.label(control.scope)
            engineActions.button("重建该 Engine 的 EGLSurface") { control.rebuild(false); output.text = "已请求；返回桌面后检查实际重建事件。" }
            engineActions.button("重建该 Engine 的 EGL context") { control.rebuild(true); output.text = "已请求；返回桌面后检查 context 代数。" }
        }
    }
    override fun onDestroy() {
        stopped = true
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
