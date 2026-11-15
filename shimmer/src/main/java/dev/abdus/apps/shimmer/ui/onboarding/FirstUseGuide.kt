package dev.abdus.apps.shimmer.ui.onboarding

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val LocalFirstUseGuide = staticCompositionLocalOf<() -> Unit> { {} }
val LocalGuideController = staticCompositionLocalOf<GuideController?> { null }
enum class GuideAnchor { TARGET, ADD_SOURCE, PLAYBACK_TAB, ORDER, WALLPAPER }
data class GuideNavigationRequest(val serial: Int, val route: String, val userAction: Boolean)
data class GuideAnchorBounds(val owner: Any, val rect: Rect)

@Stable
class GuideController(initial: GuideProgress) {
    var progress by mutableStateOf(initial)
        private set
    val anchors = mutableStateMapOf<GuideAnchor, GuideAnchorBounds>()
    private var currentTarget = "HOME"
    private var serial = 1
    var navigationRequest by mutableStateOf(
        if (initial.active) GuideNavigationRequest(0, routeFor(initial), false) else null)
        private set

    fun observeContext(target: String) {
        currentTarget = target
        progress = GuideFlow.context(progress, target)
    }
    fun start() {
        progress = GuideProgress(initialTarget = currentTarget)
        requestRoute("gallery")
    }
    fun stop() { progress = progress.copy(active = false); navigationRequest = null }
    fun targetSelected(target: String) { progress = GuideFlow.targetSelected(progress, target) }
    fun observeSources(target: String, ids: List<String>, busy: Boolean) {
        progress = GuideFlow.sources(progress, target, ids, busy)
    }
    fun useExistingSources() { progress = GuideFlow.existingSources(progress) }
    fun routeSelected(route: String) { progress = GuideFlow.routeSelected(progress, route) }
    fun orderApplied() { progress = GuideFlow.orderApplied(progress) }
    fun wallpaperPageOpened() { progress = GuideFlow.wallpaperPageOpened(progress) }
    fun practiceArmed(id: String) { progress = progress.copy(practiceSession = id, ready = false) }
    fun practiceProof(proof: GuidePracticeRequest?) { progress = GuideFlow.practiceCompleted(progress, proof, currentTarget) }
    fun currentTarget(): dev.abdus.apps.shimmer.p10.WallpaperTarget =
        dev.abdus.apps.shimmer.p10.WallpaperTarget.entries.firstOrNull { it.name == currentTarget }
            ?: dev.abdus.apps.shimmer.p10.WallpaperTarget.HOME
    fun advance(skip: Boolean = false) {
        val next = GuideFlow.advance(progress, skip)
        if (next == progress) return
        progress = next
        when (next.current) {
            GuideStep.SOURCE -> requestRoute("gallery")
            GuideStep.ORDER, GuideStep.WALLPAPER, GuideStep.GESTURE_NEXT, GuideStep.GESTURE_PREVIOUS, GuideStep.GESTURE_EDIT -> requestRoute("playback")
            else -> Unit
        }
    }
    fun requestRoute(route: String, userAction: Boolean = false) {
        navigationRequest = GuideNavigationRequest(serial++, route, userAction)
    }
    fun navigationHandled(request: GuideNavigationRequest) {
        if (navigationRequest?.serial == request.serial) navigationRequest = null
    }
    companion object {
        private fun routeFor(state: GuideProgress): String = when (state.current) {
            GuideStep.ORDER, GuideStep.WALLPAPER, GuideStep.GESTURE_NEXT, GuideStep.GESTURE_PREVIOUS, GuideStep.GESTURE_EDIT -> "playback"
            GuideStep.PLAYBACK -> if (state.ready) "playback" else "gallery"
            else -> "gallery"
        }
        fun saver(initialActive: Boolean) = Saver<GuideController, Any>(
            save = { GuideProgressCodec.encode(it.progress) },
            restore = { raw -> GuideController(GuideProgressCodec.restore(raw, initialActive)) },
        )
    }
}

/** Records only the real composable's bounds; stale pages cannot remove a new owner's anchor. */
fun Modifier.guideAnchor(anchor: GuideAnchor, enabled: Boolean = true): Modifier = composed {
    val controller = LocalGuideController.current
    val owner = remember { Any() }
    DisposableEffect(controller, anchor, enabled) {
        onDispose {
            controller?.let { if (it.anchors[anchor]?.owner === owner) it.anchors.remove(anchor) }
        }
    }
    if (!enabled || controller == null) this else onGloballyPositioned { coordinates ->
        val value = GuideAnchorBounds(owner, coordinates.boundsInRoot())
        if (controller.anchors[anchor] != value) controller.anchors[anchor] = value
    }
}

/** Inline coaching has no Dialog window, no dim layer and no blur on its explanation. */
@Composable
fun FirstUseGuideHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { FirstUseGuideStore(context) }
    val practice = remember(context) { GuidePracticeStore(context) }
    // Explicit diagnostic links must keep their requested tab; manual guide reopening still works.
    val initialActive = store.shouldShow() && dev.abdus.apps.shimmer.p0.LocalMainEntry.current.allowAutomaticGuide
    val controller = rememberSaveable(saver = GuideController.saver(initialActive)) {
        GuideController(GuideProgress(active = initialActive))
    }
    val close = {
        practice.cancel(controller.progress.practiceSession)
        if (!store.complete()) Toast.makeText(context, "引导状态未保存，下次可能再次显示", Toast.LENGTH_SHORT).show()
        controller.stop()
    }
    DisposableEffect(practice, controller) {
        val stop = practice.observe { controller.practiceProof(practice.current()) }
        onDispose(stop)
    }
    dev.abdus.apps.shimmer.ui.navigation.RefreshOnResume { controller.practiceProof(practice.current()) }
    CompositionLocalProvider(LocalFirstUseGuide provides {
        practice.cancel(controller.progress.practiceSession); controller.start()
    }, LocalGuideController provides controller) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (controller.progress.active) GuideCoach(controller, close, onAdvance = { skip ->
                practice.cancel(controller.progress.practiceSession); controller.advance(skip)
            }, onBeginPractice = begin@{
                if (!controller.progress.active || GuideFlow.expectedGesture(controller.progress.current) == null) return@begin
                val request = practice.arm(controller.currentTarget(), controller.progress.current)
                controller.practiceArmed(request.id)
                try {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: RuntimeException) {
                    practice.cancel(request.id)
                    Toast.makeText(context, "请按系统主页键返回桌面练习", Toast.LENGTH_LONG).show()
                }
            })
        }
        BackHandler(enabled = controller.progress.active, onBack = close)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.GuideCoach(controller: GuideController, onClose: () -> Unit,
    onAdvance: (Boolean) -> Unit, onBeginPractice: () -> Unit) {
    val state = controller.progress
    var top by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val anchor = when (state.current) {
        GuideStep.TARGET -> GuideAnchor.TARGET
        GuideStep.SOURCE -> GuideAnchor.ADD_SOURCE
        GuideStep.PLAYBACK -> GuideAnchor.PLAYBACK_TAB
        GuideStep.ORDER -> GuideAnchor.ORDER
        GuideStep.WALLPAPER -> GuideAnchor.WALLPAPER
        else -> null
    }
    val bounds = anchor?.let { controller.anchors[it]?.rect }
    val accent = MaterialTheme.colorScheme.primary
    if (bounds != null && !state.ready) Canvas(Modifier.matchParentSize()) {
        val rect = bounds.inflate(3.dp.toPx())
        drawRoundRect(accent, rect.topLeft, rect.size, CornerRadius(18.dp.toPx()), style = Stroke(3.dp.toPx()))
    }
    val (title, instruction) = when (state.current) {
        GuideStep.TARGET -> "试着切换目标" to "点击顶栏切换到另一目标，再切回原目标。只切换管理页面，不会替你更换系统壁纸。"
        GuideStep.SOURCE -> "添加或使用来源" to "点击高亮的＋，实际选择一张图片或文件夹。导入成功后才能继续；也可使用当前已有来源。取消选取不会算完成。"
        GuideStep.PLAYBACK -> "进入播放页" to "点击底部的“播放”，查看当前图片、播放顺序和分组队列。"
        GuideStep.ORDER -> "亲自选择播放顺序" to "点击高亮卡片中的一种排序方式。此操作会按正常规则保存；不想更改可跳过此步。"
        GuideStep.WALLPAPER -> "打开系统壁纸页面" to "点击高亮的“设为壁纸／重新设为壁纸”，按正常确认进入系统页面。是否应用由你决定；引导不会自动设置。"
        GuideStep.GESTURE_NEXT -> "单指双击：实际下一张" to "开始练习后，在当前目标的真实壁纸空白区域单指连续点两下。看到图片切换后回到应用继续；至少需要两张可播放媒体。"
        GuideStep.GESTURE_PREVIOUS -> "单指三击：实际上一张" to "开始练习后，在真实壁纸空白区域单指连续点三下。看到上一张后回到应用；没有上一张历史时可先跳过。"
        GuideStep.GESTURE_EDIT -> "双指双击：实际进入编辑" to "开始练习后，在真实壁纸上两根手指同时点下并抬起，连续做两次。进入编辑后可操作或取消返回；锁屏按正常流程认证。"
        GuideStep.FINISH -> "操作练习结束" to "已完成 ${state.completed.countOneBits()} 步，跳过 ${state.skipped.countOneBits()} 步。特效页可调整适配和进入编辑；以后可在设置→高级重新练习。"
    }
    Surface(
        modifier = Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .then(if (top) Modifier.statusBarsPadding().padding(top = 72.dp)
                else Modifier.navigationBarsPadding().padding(bottom = 72.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .heightIn(max = if (state.current in gestureSteps) 520.dp else 320.dp)
            .semantics { paneTitle = "操作引导"; liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.current == GuideStep.FINISH) "操作引导" else "操作引导 ${state.step + 1}/${GuideStep.FINISH.ordinal}",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { top = !top }) { Text("移动提示") }
                TextButton(onClick = { collapsed = !collapsed }) { Text(if (collapsed) "展开" else "收起") }
            }
            if (!collapsed) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    Text(instruction, style = MaterialTheme.typography.bodyMedium)
                    if (state.current in gestureSteps) {
                        GesturePractice(state.ready, state.practiceSession != null, controller.currentTarget(), onBeginPractice)
                        Text("本步会真实切图或进入编辑。请先应用对应目标壁纸并等待画面稳定；锁屏目标需自行锁屏后操作。无法触发可检查兼容模块，或跳过此步。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.ready) Text("已完成本步，可以继续。", color = MaterialTheme.colorScheme.primary)
                    if (state.current == GuideStep.SOURCE && state.sourceBusy) Text("正在读取／导入，请等待；也可先跳过。")
                    if (state.current == GuideStep.SOURCE && state.hasSources) OutlinedButton(
                        onClick = controller::useExistingSources, enabled = !state.sourceBusy && !state.ready) { Text("使用已有来源") }
                    if (state.current == GuideStep.PLAYBACK && !state.ready) OutlinedButton(
                        onClick = { controller.requestRoute("playback", userAction = true) }) { Text("前往播放页") }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose) { Text("结束引导") }
                    if (state.current != GuideStep.FINISH) TextButton(onClick = { onAdvance(true) }) { Text("跳过此步") }
                    Button(onClick = { if (state.current == GuideStep.FINISH) onClose() else onAdvance(false) },
                        enabled = state.ready || state.current == GuideStep.FINISH) {
                        Text(if (state.current == GuideStep.FINISH) "完成" else "继续")
                    }
                }
            }
        }
    }
}
