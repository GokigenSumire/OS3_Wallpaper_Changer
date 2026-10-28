package dev.abdus.apps.shimmer.p3

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.abdus.apps.shimmer.p0.P0Diagnostics
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Short Chinese names used by the editor; the global default goes through [DefaultFitChoice]. */
internal fun FitMode.chineseLabel(): String = when (this) {
    FitMode.FIT_WIDTH -> "适宽"
    FitMode.FIT_HEIGHT -> "适高"
    FitMode.CONTAIN -> "完整"
    FitMode.COVER -> "铺满"
}

/**
 * P3 live editor. The window shows the real wallpaper behind a transparent gesture layer, and
 * every adjustment is applied by the formal wallpaper's own renderer. The draft lives only in
 * the Engine until Save; leaving the page any other way (back, Home, lock) is a cancel.
 */
open class P3EditActivity : ComponentActivity() {
    private var session: LiveEditBridge.EditSession? = null
    private lateinit var image: LiveEditBridge.EditImage
    private var draft = ImageTransform()
    /** Reset shows the defaults; saving then drops the image's own config instead of storing them. */
    private var resetPending = false
    /** Set once Save or Cancel ran, so onStop does not cancel a second time. */
    private var closing = false
    /** Set while a step operation is in progress to prevent double-triggering. */
    private var stepping = false
    private var confirmationShowing = false
    private var lastInputUptime = 0L
    private var shownDraft: ImageTransform? = null
    private var statsJob: Job? = null
    private lateinit var surface: GestureSurface
    private lateinit var stats: TextView
    private lateinit var statsHeader: LinearLayout
    private lateinit var statsToggle: Button
    private lateinit var state: TextView
    private lateinit var panel: EditorToolScroll
    private lateinit var showPanel: Button
    private var panelExpanded = EditorPanelPolicy.INITIAL_EXPANDED
    private var selectedTool = EditorToolGroup.ROTATE_MIRROR
    private val toolGroups = linkedMapOf<EditorToolGroup, LinearLayout>()
    private val toolTabs = linkedMapOf<EditorToolGroup, Button>()
    private lateinit var fitButton: Button
    private lateinit var blurButton: Button
    private lateinit var enableButton: Button
    private lateinit var dock: LinearLayout
    private val draftButtons = mutableListOf<Button>()

    /** HOME keeps its original transparent, formal-Engine preview path by default. */
    protected open val editorTarget: WallpaperTarget get() = WallpaperTarget.HOME
    protected open val editorFrameStats: FrameStats get() = FrameStats.shared
    protected open val noEditorMessage: String get() =
        "没有可编辑的壁纸：请先把 P2 设为动态壁纸，并等桌面显示出图片后再试。"
    protected open fun acquireEditorSession(): LiveEditBridge.EditSession? =
        LiveEditBridge.find(intent.getStringExtra(EXTRA_TARGET), editorTarget)?.beginEdit()

    /** LOCK overrides this to put a privately owned GL surface below the shared gesture UI. */
    protected open fun attachEditorPreview(root: FrameLayout) = Unit
    protected open fun editorDiagnosticImageLabel(imageId: String): String = imageId.takeLast(24)

    /** Preview failures close the exact session instead of falling back to a HOME editor. */
    protected fun interruptEditor() {
        session?.cancel()
        interrupted()
    }

    companion object {
        private const val EXTRA_TARGET = "dev.abdus.apps.shimmer.p3.TARGET"
        private const val EXTRA_GESTURE_UPTIME = "dev.abdus.apps.shimmer.p3.GESTURE_UPTIME"
        private const val STATS_INTERVAL_MILLIS = 500L
        private const val LOG_SOURCE = "P3_EDIT"

        /** [gestureUptime] is set only by the wallpaper gesture; it starts the entry-latency clock. */
        fun launch(context: Context, targetId: String?, gestureUptime: Long?) {
            val intent = Intent(context, P3EditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            targetId?.let { intent.putExtra(EXTRA_TARGET, it) }
            gestureUptime?.let { intent.putExtra(EXTRA_GESTURE_UPTIME, it) }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val opened = acquireEditorSession()
        if (opened == null) {
            P0Diagnostics.event(LOG_SOURCE, "P3_EDIT_NO_TARGET", "target=${intent.getStringExtra(EXTRA_TARGET)}")
            Toast.makeText(this, noEditorMessage, Toast.LENGTH_LONG).show()
            closing = true
            finish()
            return
        }
        session = opened
        image = opened.image
        draft = image.original
        buildScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave(offerSave = true)
        })
        P0Diagnostics.event(LOG_SOURCE, "P3_EDIT_ARRIVE", "image=${editorDiagnosticImageLabel(image.imageId)} gesture=${intent.hasExtra(EXTRA_GESTURE_UPTIME)}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resetNavigationGesture()
        // A second request while open keeps the current draft; only the entry clock is new.
        setIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) resetNavigationGesture()
        if (!hasFocus || session == null || !intent.hasExtra(EXTRA_GESTURE_UPTIME)) return
        // Frozen method: two-finger decision in the Engine to this window gaining focus.
        val entry = SystemClock.uptimeMillis() - intent.getLongExtra(EXTRA_GESTURE_UPTIME, 0L)
        intent.removeExtra(EXTRA_GESTURE_UPTIME)
        editorFrameStats.recordEntry(entry)
        P0Diagnostics.event(LOG_SOURCE, "P3_EDIT_ENTRY", "ms=$entry")
    }

    override fun onStart() {
        super.onStart()
        if (session == null) return
        statsJob = lifecycleScope.launch {
            while (true) {
                refreshStats()
                delay(STATS_INTERVAL_MILLIS)
            }
        }
    }

    override fun onStop() {
        resetNavigationGesture()
        statsJob?.cancel()
        statsJob = null
        // Home, lock or another app on top: an unsaved draft must not stay on the wallpaper.
        if (!closing && session != null) {
            cancelEdit()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::surface.isInitialized) surface.closeNavigation()
        if (!closing) session?.cancel()
        super.onDestroy()
    }

    override fun onPause() {
        resetNavigationGesture()
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Touches on overlay padding also interrupt a pending double on the exposed wallpaper.
        if (::surface.isInitialized && (confirmationShowing || touchesOverlay(event))) resetNavigationGesture()
        return super.dispatchTouchEvent(event)
    }

    private fun touchesOverlay(event: MotionEvent): Boolean {
        val bounds = Rect()
        val rootScreen = IntArray(2)
        fun inside(view: View): Boolean {
            if (!view.isShown || !view.getGlobalVisibleRect(bounds)) return false
            // getGlobalVisibleRect is in root coordinates; MotionEvent.rawX/Y are screen pixels.
            view.rootView.getLocationOnScreen(rootScreen)
            return EditorOverlayBounds.contains(bounds.left, bounds.top, bounds.right, bounds.bottom,
                rootScreen[0], rootScreen[1], event.rawX, event.rawY)
        }
        return (::dock.isInitialized && inside(dock)) || (::statsHeader.isInitialized && inside(statsHeader))
    }

    private fun resetNavigationGesture() {
        if (::surface.isInitialized) surface.resetNavigation()
    }

    private fun save() {
        if (closing || stepping) return
        resetNavigationGesture()
        val current = session
        if (current == null || !current.active) {
            interrupted()
            return
        }
        closing = true
        current.save(if (resetPending) null else draft, SystemClock.uptimeMillis())
        finish()
    }

    private fun cancelEdit() {
        if (closing) return
        resetNavigationGesture()
        closing = true
        session?.cancel()
        finish()
    }

    /** The Engine ended the session (surface lost, wallpaper replaced, another editor opened). */
    private fun interrupted() {
        if (closing) return
        resetNavigationGesture()
        closing = true
        Toast.makeText(this, "编辑已中断，本次修改未保存。", Toast.LENGTH_LONG).show()
        finish()
    }

    /** Saving and discarding are reached only through Back; an unchanged draft exits directly. */
    private fun confirmLeave(offerSave: Boolean) {
        if (closing || confirmationShowing) return
        resetNavigationGesture()
        if (stepping) {
            cancelEdit()
            return
        }
        if (!resetPending && draft == image.original) {
            cancelEdit()
            return
        }
        val dialog = AlertDialog.Builder(this)
        if (offerSave) {
            dialog.setTitle("保存修改？")
                .setMessage("本次调整尚未保存。保存后壁纸保留当前效果；放弃则恢复为编辑前的样子。")
                .setNeutralButton("继续编辑", null)
                .setNegativeButton("放弃修改") { _, _ -> cancelEdit() }
                .setPositiveButton("保存修改") { _, _ -> save() }
        } else {
            dialog.setTitle("放弃修改？")
                .setMessage("本次调整尚未保存，离开后壁纸恢复为编辑前的样子。")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃修改") { _, _ -> cancelEdit() }
        }
        showConfirmation(dialog)
    }

    private fun showConfirmation(builder: AlertDialog.Builder) {
        resetNavigationGesture()
        confirmationShowing = true
        builder.setOnDismissListener {
            confirmationShowing = false
            resetNavigationGesture()
        }.show()
    }
    private fun buildScreen() {
        val root = FrameLayout(this)
        attachEditorPreview(root)
        surface = GestureSurface(this).apply {
            contentDescription = "壁纸编辑区：单指拖动，两指缩放和旋转；单指双击下一张、三击上一张；返回可保存或放弃；读屏时可用自定义操作"
        }
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        stats = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            tag = "p3-editor-stats"
            isClickable = true
        }
        state = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), dp(4))
            visibility = View.GONE
            tag = "p3-editor-transform-state"
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
        }
        statsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(EditorPanelPolicy.OVERLAY_COLOR)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
            tag = "p3-editor-stats-header"
            isClickable = true
            addView(state)
            addView(stats)
        }
        root.addView(statsHeader, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        fitButton = draftAction("") { change(cycleFit(draft)) }
        blurButton = draftAction("") { change(draft.copy(blurFill = !draft.blurFill)) }
        enableButton = action("") { toggleImageEnabled() }
        statsToggle = action("显示统计") { toggleStats() }

        // This dock is an overlay. Never shrink or reposition the full-screen gesture surface.
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "p3-editor-dock"
            isClickable = true
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(EditorPanelPolicy.OVERLAY_COLOR)
            setPadding(dp(8), dp(6), dp(8), dp(8))
            isClickable = true
            tag = "p3-editor-tool-content"
        }
        val tabs = EditorToolGroup.entries.map { group ->
            action(group.label) { selectToolGroup(group) }.also {
                toolTabs[group] = it
                it.tag = "p3-editor-tab-${group.name}"
            }
        }
        tools.apply {
            addView(row(*tabs.toTypedArray()))
        }
        fun group(which: EditorToolGroup, vararg rows: LinearLayout) {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "p3-editor-group-${which.name}"
                isClickable = true
                rows.forEach { addView(it) }
            }
            toolGroups[which] = content
            tools.addView(content)
        }
        group(EditorToolGroup.ROTATE_MIRROR,
            row(draftAction("左右镜像") { change(draft.copy(mirrorHorizontal = !draft.mirrorHorizontal)) },
                draftAction("上下镜像") { change(draft.copy(mirrorVertical = !draft.mirrorVertical)) },
                draftAction("左转 90°") { change(TransformMath.stepQuarterTurn(draft, clockwise = false)) },
                draftAction("右转 90°") { change(TransformMath.stepQuarterTurn(draft, clockwise = true)) }),
        )
        group(EditorToolGroup.DEBUG, row(blurButton, statsToggle))
        panel = EditorToolScroll(this).apply {
            tag = "p3-editor-tools"
            addView(tools, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        dock.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        showPanel = action("展开工具") { setPanelVisible(!panelExpanded) }.apply { tag = "p3-editor-tool-toggle" }
        val footer = row(fitButton, draftAction("重置") { reset() }, enableButton, showPanel).apply {
            tag = "p3-editor-footer"
            setBackgroundColor(EditorPanelPolicy.OVERLAY_COLOR)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
        }
        dock.addView(footer)
        root.addView(dock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        var insetTop = 0
        var insetBottom = 0
        fun updateToolLimit() {
            panel.maximumHeightPixels = EditorPanelPolicy.maxToolHeight(root.height, insetTop, insetBottom)
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateToolLimit() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetTop = bars.top
            insetBottom = bars.bottom
            statsHeader.setPadding(dp(8) + bars.left, dp(6) + bars.top, dp(8) + bars.right, dp(6))
            dock.setPadding(bars.left, 0, bars.right, bars.bottom)
            updateToolLimit()
            insets
        }
        selectToolGroup(selectedTool)
        setPanelVisible(EditorPanelPolicy.INITIAL_EXPANDED)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        refreshState()
    }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isClickable = true
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        minimumHeight = dp(EditorPanelPolicy.MIN_TOUCH_DP)
        minHeight = dp(EditorPanelPolicy.MIN_TOUCH_DP)
        setOnClickListener {
            resetNavigationGesture()
            lastInputUptime = SystemClock.uptimeMillis()
            onClick()
        }
    }

    private fun draftAction(label: String, onClick: () -> Unit): Button = action(label) {
        if (!stepping && !closing) onClick()
    }.also { draftButtons += it }

    private fun setPanelVisible(visible: Boolean) {
        resetNavigationGesture()
        panelExpanded = visible
        val mode = if (visible) View.VISIBLE else View.GONE
        panel.visibility = mode
        showPanel.text = if (visible) "收起工具" else "展开工具"
        showPanel.contentDescription = if (visible) "收起编辑工具，保留本次调整" else "展开编辑工具，保留本次调整"
        showPanel.visibility = View.VISIBLE
    }

    private fun selectToolGroup(group: EditorToolGroup) {
        resetNavigationGesture()
        selectedTool = group
        toolGroups.forEach { (which, content) -> content.visibility = if (which == group) View.VISIBLE else View.GONE }
        toolTabs.forEach { (which, button) ->
            button.isSelected = which == group
            button.contentDescription = "${which.label}工具，${if (which == group) "已选中" else "未选中"}"
            button.setTextColor(Color.WHITE)
            button.backgroundTintList = null
            button.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (which == group) 0xB0000000.toInt() else 0x30000000)
                setStroke(dp(if (which == group) 2 else 1), if (which == group) Color.WHITE else 0x80FFFFFF.toInt())
            }
        }
        if (::panel.isInitialized) panel.scrollTo(0, 0)
    }

    private fun toggleStats() {
        val nowVisible = stats.visibility == View.VISIBLE
        stats.visibility = if (nowVisible) View.GONE else View.VISIBLE
        state.visibility = stats.visibility
        statsHeader.visibility = stats.visibility
        statsToggle.text = if (nowVisible) "显示统计" else "隐藏统计"
    }

    private fun toggleImageEnabled() {
        if (stepping || closing) return
        val current = session ?: return
        if (!current.active) {
            interrupted()
            return
        }
        val enabled = image.enabled ?: return
        val newEnabled = !enabled

        // Use WallpaperPreferences to toggle
        val prefs = dev.abdus.apps.shimmer.WallpaperPreferences.create(this, editorTarget)
        prefs.setImageEnabled(image.imageId, newEnabled)

        image = image.copy(enabled = newEnabled)
        refreshState()

        if (newEnabled) {
            Toast.makeText(this, "本张图片已启用", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, DisabledImageText.DISABLED_TOAST, Toast.LENGTH_LONG).show()
        }
    }

    private fun navigateImage(forward: Boolean) {
        if (stepping || closing || confirmationShowing) return
        resetNavigationGesture()
        val current = session ?: return
        if (!current.active) {
            interrupted()
            return
        }

        // Reset is a pending removal of the image config, even if defaults equal the old draft.
        val hasChanges = resetPending || draft != image.original
        if (!hasChanges) {
            // No unsaved changes, step directly
            runStep(forward, saveFirst = false, transform = null)
        } else {
            // Ask what to do with unsaved changes
            showConfirmation(AlertDialog.Builder(this)
                .setTitle("切换前保存修改？")
                .setMessage("本次调整尚未保存。")
                .setNeutralButton("继续编辑", null)
                .setNegativeButton("放弃并切换") { _, _ -> runStep(forward, saveFirst = false, transform = null) }
                .setPositiveButton("保存并切换") { _, _ -> runStep(forward, saveFirst = true, transform = if (resetPending) null else draft) }
            )
        }
    }

    private fun runStep(forward: Boolean, saveFirst: Boolean, transform: ImageTransform?) {
        val current = session ?: return
        if (!current.active || stepping) return

        stepping = true
        setStepControlsEnabled(false)

        current.step(forward, saveFirst, transform) { result ->
            // Cancel/Home/Back or a replaced session owns the newer state, never a late result.
            if (closing || session !== current || !current.active) return@step
            stepping = false
            setStepControlsEnabled(true)

            when (result) {
                is LiveEditBridge.StepResult.Success -> {
                    image = result.image
                    draft = image.original
                    resetPending = false
                    refreshState()
                }
                is LiveEditBridge.StepResult.Failed -> {
                    Toast.makeText(this, "切换失败：${result.reason}", Toast.LENGTH_SHORT).show()
                }
                LiveEditBridge.StepResult.NoCandidate -> {
                    Toast.makeText(this, "没有可切换的图片", Toast.LENGTH_SHORT).show()
                }
                null -> {
                    interrupted()
                }
            }
        }
    }

    private fun setStepControlsEnabled(enabled: Boolean) {
        resetNavigationGesture()
        enableButton.isEnabled = enabled && image.enabled != null
        draftButtons.forEach { it.isEnabled = enabled }
    }

    /** A fit button starts its own framing: zoom and pan reset, rotation and mirrors stay. */
    private fun cycleFit(transform: ImageTransform): ImageTransform {
        val modes = FitMode.entries
        val next = modes[(modes.indexOf(transform.fitMode) + 1) % modes.size]
        return transform.copy(fitMode = next, userScale = 1f, offsetX = 0f, offsetY = 0f)
    }

    private fun reset() {
        if (stepping || closing) return
        change(image.defaults.toTransform())
        resetPending = true
        refreshState()
    }

    /** Every adjustment goes through here: bounded, kept on screen, then drawn by the Engine. */
    private fun change(next: ImageTransform, inputUptimeMillis: Long = SystemClock.uptimeMillis(), fromGesture: Boolean = false) {
        if (stepping || closing) return
        val bounded = TransformMath.keepVisible(next, image.sourceWidth, image.sourceHeight, image.viewWidth, image.viewHeight)
        if (bounded == draft) return
        draft = bounded
        resetPending = false
        session?.preview(bounded, inputUptimeMillis)
        // A drag updates the text from the poll instead of relaying out on every move event.
        if (!fromGesture) refreshState()
    }

    private fun refreshState() {
        shownDraft = draft
        fitButton.text = "适配：${draft.fitMode.chineseLabel()}"
        blurButton.text = if (draft.blurFill) "模糊填充：开" else "模糊填充：关"
        when (image.enabled) {
            true -> {
                enableButton.text = "启用：开"
                enableButton.isEnabled = !stepping
            }
            false -> {
                enableButton.text = "启用：关"
                enableButton.isEnabled = !stepping
            }
            null -> {
                enableButton.text = "默认图片"
                enableButton.isEnabled = false
            }
        }
        state.text = String.format(
            Locale.ROOT,
            "缩放 %.2f×  旋转 %.1f°  平移 %.3f, %.3f  镜像 %s%s%s",
            draft.userScale, draft.rotationDegrees, draft.offsetX, draft.offsetY,
            when {
                draft.mirrorHorizontal && draft.mirrorVertical -> "左右+上下"
                draft.mirrorHorizontal -> "左右"
                draft.mirrorVertical -> "上下"
                else -> "无"
            },
            if (resetPending) "  （已重置，保存后恢复全局默认）" else "",
            if (!resetPending && draft == image.original) "" else "  · 未保存",
        )
    }
    /** F08 measurement line, polled while the page is started. Thresholds are the frozen ones. */
    private fun refreshStats() {
        val current = session ?: return
        if (!current.active) {
            interrupted()
            return
        }
        if (shownDraft != draft) refreshState()
        val snap = editorFrameStats.snapshot()
        val now = SystemClock.uptimeMillis()
        val location = IntArray(2).also { surface.getLocationOnScreen(it) }
        stats.text = buildString {
            appendLine(
                "跟手（触摸→含该变换的帧提交，不含系统合成/显示）：p95 ${snap.inputP95Millis ?: "-"} ms，" +
                    "p99 ${snap.inputP99Millis ?: "-"} ms，样本 ${snap.inputSamples}/${FrameStats.MIN_FRAME_SAMPLES}，${verdict(snap.latencyPass)}",
            )
            appendLine(
                "单帧绘制：p95 ${millis(snap.drawP95Millis)} ms，p99 ${millis(snap.drawP99Millis)} ms，" +
                    "超 16.7 ms ${percent(snap.drawOverBudgetFraction)}，样本 ${snap.drawSamples}，${verdict(snap.drawPass)}",
            )
            appendLine("进入（近 5 次，每次 ≤ ${FrameStats.ENTRY_MAX_MS} ms）：${events(snap.recentEntryMillis, FrameStats.ENTRY_MAX_MS)}")
            appendLine("保存生效（近 5 次，每次 ≤ ${FrameStats.SAVE_MAX_MS} ms，关闭后记录，下次打开可见）：${events(snap.recentSaveMillis, FrameStats.SAVE_MAX_MS)}")
            appendLine("壁纸绘制 ${snap.totalDraws} 次；距上次绘制 ${since(now, snap.lastDrawUptimeMillis)}；距上次操作 ${since(now, lastInputUptime)}")
            appendLine("Native heap ${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB")
            appendLine("视口 ${image.viewWidth}×${image.viewHeight}；编辑区 ${surface.width}×${surface.height} 位于 ${location[0]},${location[1]}")
            append(current.statusLine())
        }
    }

    private fun verdict(pass: Boolean?): String = when (pass) {
        null -> "样本不足"
        true -> "达标"
        false -> "未达标"
    }

    private fun millis(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

    private fun percent(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "-"

    private fun events(values: List<Long>, max: Long): String =
        if (values.isEmpty()) "无"
        else values.joinToString(" / ", postfix = " ms，") + (if (values.all { it <= max }) "均达标" else "有超限")

    private fun since(now: Long, then: Long): String =
        if (then <= 0L) "无" else String.format(Locale.ROOT, "%.1f s", (now - then) / 1000.0)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Full-screen transparent touch layer. One finger pans; two fingers pan, pinch and twist in
     * the same move, all around the point between the fingers. Coordinates are mapped to the
     * wallpaper surface through this view's position on screen.
     */
    private inner class GestureSurface(context: Context) : View(context) {
        private val navigation = EditorNavigationGesture(context,
            canNavigate = { !stepping && !closing && !confirmationShowing && session?.active == true },
            onNavigate = { navigateImage(forward = it) },
        )
        fun resetNavigation() = navigation.reset()
        fun closeNavigation() = navigation.close()
        private val origin = IntArray(2)
        private var last = PointerFrame(0f, 0f, 0f, null)
        /** Unsnapped accumulated angle, so the quarter-turn snap does not swallow a slow twist. */
        private var rawRotation = 0f
        /** Below this finger distance, span and angle are too noisy to zoom or rotate from. */
        private val minSpan = 24f * resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var down = last
        /** A single finger that has not moved: the only touch reported as a click. */
        private var tap = false
        private var dragging = false
        /** A touch started during navigation cannot resume half-way onto a newly loaded image. */
        private var suppressedUntilDown = false

        init {
            // TalkBack cannot pinch or drag; these reach the same zoom and pan in small steps.
            accessibilityStep("放大") { EditorPositionControls.adjust(draft, EditorPositionAction.ZOOM_IN, image.viewWidth, image.viewHeight) }
            accessibilityStep("缩小") { EditorPositionControls.adjust(draft, EditorPositionAction.ZOOM_OUT, image.viewWidth, image.viewHeight) }
            accessibilityStep("图片上移") { EditorPositionControls.adjust(draft, EditorPositionAction.UP, image.viewWidth, image.viewHeight) }
            accessibilityStep("图片下移") { EditorPositionControls.adjust(draft, EditorPositionAction.DOWN, image.viewWidth, image.viewHeight) }
            accessibilityStep("图片左移") { EditorPositionControls.adjust(draft, EditorPositionAction.LEFT, image.viewWidth, image.viewHeight) }
            accessibilityStep("图片右移") { EditorPositionControls.adjust(draft, EditorPositionAction.RIGHT, image.viewWidth, image.viewHeight) }
            ViewCompat.addAccessibilityAction(this, "下一张图片") { _, _ ->
                resetNavigationGesture()
                navigateImage(forward = true)
                true
            }
            ViewCompat.addAccessibilityAction(this, "上一张图片") { _, _ ->
                resetNavigationGesture()
                navigateImage(forward = false)
                true
            }
        }

        private fun accessibilityStep(label: String, next: () -> ImageTransform) {
            ViewCompat.addAccessibilityAction(this, label) { _, _ ->
                resetNavigationGesture()
                lastInputUptime = SystemClock.uptimeMillis()
                change(next())
                true
            }
        }

        // A tap does nothing here; it is still reported so accessibility sees the click.
        override fun performClick(): Boolean = super.performClick()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            lastInputUptime = SystemClock.uptimeMillis()
            if (stepping || closing || confirmationShowing) {
                resetNavigation()
                suppressedUntilDown = true
                tap = false
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) suppressedUntilDown = false
            if (suppressedUntilDown) return true
            navigation.onTouchEvent(event)
            // The recognizer may synchronously begin navigation or show its save dialog on UP.
            if (stepping || closing || confirmationShowing) {
                tap = false
                suppressedUntilDown = true
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    getLocationOnScreen(origin)
                    rawRotation = draft.rotationDegrees
                    last = frame(event, excluded = -1)
                    down = last
                    tap = true
                    dragging = false
                }
                // A finger joining or leaving moves the centroid: restart from here, no jump.
                MotionEvent.ACTION_POINTER_DOWN -> {
                    last = frame(event, excluded = -1)
                    tap = false
                    dragging = true
                }
                MotionEvent.ACTION_POINTER_UP -> last = frame(event, excluded = event.actionIndex)
                MotionEvent.ACTION_MOVE -> {
                    val now = frame(event, excluded = -1)
                    if (!dragging && (event.pointerCount > 1 ||
                        hypot(now.x - down.x, now.y - down.y) > touchSlop || historicalDrag(event))) dragging = true
                    if (dragging) {
                        move(event)
                        tap = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    refreshState()
                    if (tap) performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    tap = false
                    refreshState()
                }
            }
            return true
        }

        /** A batched excursion cannot become a tap merely because its final sample returned. */
        private fun historicalDrag(event: MotionEvent): Boolean = (0 until event.historySize).any { sample ->
            hypot(event.getHistoricalX(0, sample) + origin[0] - down.x,
                event.getHistoricalY(0, sample) + origin[1] - down.y) > touchSlop
        }

        private fun move(event: MotionEvent) {
            val now = frame(event, excluded = -1)
            val w = image.viewWidth
            val h = image.viewHeight
            var next = TransformMath.pan(draft, now.x - last.x, now.y - last.y, w, h)
            val before = last.angle
            if (before != null && now.angle != null && last.span >= minSpan && now.span >= minSpan) {
                next = TransformMath.zoomAround(next, now.span / last.span, now.x, now.y, w, h)
                rawRotation = normalizeDegrees(rawRotation + normalizeDegrees(Math.toDegrees(now.angle - before).toFloat()))
                val target = TransformMath.snapRotation(rawRotation)
                next = TransformMath.rotateAround(
                    next, normalizeDegrees(target - next.rotationDegrees), now.x, now.y,
                    image.sourceWidth, image.sourceHeight, w, h,
                )
            }
            last = now
            change(next, event.eventTime, fromGesture = true)
        }

        /** Centroid of the fingers still down (screen pixels), plus span and angle of the first two. */
        private fun frame(event: MotionEvent, excluded: Int): PointerFrame {
            var sumX = 0f
            var sumY = 0f
            var count = 0
            var first = -1
            var second = -1
            for (i in 0 until event.pointerCount) {
                if (i == excluded) continue
                sumX += event.getX(i)
                sumY += event.getY(i)
                count++
                if (first < 0) first = i else if (second < 0) second = i
            }
            if (count == 0) return last
            val x = sumX / count + origin[0]
            val y = sumY / count + origin[1]
            if (second < 0) return PointerFrame(x, y, 0f, null)
            val dx = event.getX(second) - event.getX(first)
            val dy = event.getY(second) - event.getY(first)
            // y points down, so a growing angle is a clockwise twist, matching the transform.
            return PointerFrame(x, y, hypot(dx, dy), atan2(dy.toDouble(), dx.toDouble()))
        }
    }
}

private class PointerFrame(val x: Float, val y: Float, val span: Float, val angle: Double?)
