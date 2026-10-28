package dev.abdus.apps.shimmer.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * P9-FLOAT-SHAPE-03：在页面内容区左右滑动切换 Tab。
 *
 * 用户在版本 35 反馈"在当前界面左右滑动可切换不同界面（图库播放特效设置）"。实现上有三处必须避让，
 * 否则会抢掉已经验证通过的交互：
 *
 * 1. **系统返回手势**：从屏幕左右边缘起手的滑动属于系统预测性返回（第一批 `TC-NAV-02` 已验证
 *    非图库 Tab 返回到图库）。因此起手点落在 [EdgeExclusion] 以内的手势一律不处理。
 * 2. **竖直滚动**：系统 touch slop 内不锁方向；明确纵向滚动让出且不重新接管。
 * 3. **页内横向控件**：滑块（透明度、过渡时长）需要横向拖动。本修饰符用
 *    [PointerEventPass.Final] 观察已被子级消费的事件——子级消费过的手势不会被这里接管。
 *
 * 只在悬浮底栏开启时挂载；贴边形态保持版本 34 的行为不变。
 */
fun Modifier.tabSwipe(
    selectedIndex: Int,
    tabCount: Int,
    onTabSelected: (Int) -> Unit,
): Modifier = composed {
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    pointerInput(selectedIndex, tabCount, isLtr) {
        if (tabCount <= 1) return@pointerInput
        val edgePx = EdgeExclusion.toPx()
        val thresholdPx = SwipeThreshold.toPx()

        awaitEachGesture {
            // 点击控件会消费按下，仍允许从卡片开始横滑；消费了运动才永久让给子级。
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val startX = down.position.x
            // 边缘起手留给系统返回手势
            if (startX < edgePx || startX > size.width - edgePx) return@awaitEachGesture

            val gesture = TabSwipeGesture(
                selectedIndex = selectedIndex,
                tabCount = tabCount,
                isLtr = isLtr,
                startX = startX,
                width = size.width.toFloat(),
                edgeExclusion = edgePx,
                threshold = thresholdPx,
                touchSlop = viewConfiguration.touchSlop,
                longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
            )

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id }
                    ?: break
                val movement = change.positionChangeIgnoreConsumed()
                val moving = movement.x != 0f || movement.y != 0f
                val decision = gesture.update(
                    deltaX = movement.x,
                    deltaY = movement.y,
                    elapsedMillis = change.uptimeMillis - down.uptimeMillis,
                    pointerCount = event.changes.count { it.pressed || it.previousPressed },
                    consumedByChild = change.isConsumed && moving,
                    released = change.changedToUpIgnoreConsumed(),
                )
                if (decision.consume) change.consume()
                decision.targetIndex?.let(onTabSelected)
                if (decision.finished || !change.pressed) break
            }
        }
    }
}

/**
 * Tab 切换的卡片滑动过渡。
 *
 * 用户在版本 36 反馈后补充"左右滑动添加简单卡片滑动过渡效果"。按切换方向决定进出方向：前进时新页
 * 从右侧滑入、旧页向左滑出，后退时相反；这样过渡方向与手势方向一致，点击切换也沿用同一规则。
 *
 * 用 [AnimatedContent] 而非自绘位移：它负责旧页与新页的同时存在与生命周期，避免手写两份布局。
 * 时长取 [TransitionMillis]，与 Material 的标准页面切换相当，不随 P5 的壁纸过渡时长设置变化——
 * 那是壁纸切换的时长，与界面导航无关。
 *
 * [targetIndex] 是目标 Tab 的索引；方向由它与上一次的差值决定。
 */
@Composable
fun TabContentTransition(
    targetIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    AnimatedContent(
        targetState = targetIndex,
        modifier = modifier,
        transitionSpec = {
            // initialState 是切走的那个 Tab，targetState 是要显示的
            val forward = targetState > initialState
            // 右手方向上，"前进"意味着新页从右侧进入
            val enterFromRight = if (isLtr) forward else !forward
            val enter = slideInHorizontally(animationSpec = tween(TransitionMillis)) { width ->
                if (enterFromRight) width else -width
            }
            val exit = slideOutHorizontally(animationSpec = tween(TransitionMillis)) { width ->
                if (enterFromRight) -width else width
            }
            enter togetherWith exit
        },
        label = "tab_content",
    ) { index ->
        content(index)
    }
}

/** 左右边缘的排除宽度，留给系统预测性返回手势。 */
private val EdgeExclusion: Dp = 32.dp

/**
 * 触发切换的最小横向位移。
 *
 * P9-SWIPE-SENS-02：由 48dp 降到 32dp；系统 slop、子控件优先及边缘避让仍保留。
 */
private val SwipeThreshold: Dp = 32.dp

/** 卡片滑动过渡的时长。 */
private const val TransitionMillis = 260
