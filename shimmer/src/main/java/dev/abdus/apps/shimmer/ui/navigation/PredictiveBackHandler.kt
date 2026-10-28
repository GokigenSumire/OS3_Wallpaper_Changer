package dev.abdus.apps.shimmer.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.animation.SpringAnimations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * P8.8 预测性返回
 *
 * - 跨 Activity 返回（从 P0～P5 旧页面回到主界面、从主界面回到桌面）由系统完成：
 *   清单已开 enableOnBackInvokedCallback，旧页面与 P3 编辑页都通过 OnBackPressedDispatcher
 *   注册回调，系统会显示上一页预览。这里不拦截，以免覆盖系统动画。
 * - 主界面内只在非“图库”Tab 拦截：手势进度按 [BackGestureTransform] 缩小并平移当前 Tab，
 *   露出背后的上一页（[previous]，即只读的“图库”）；当前 Tab 在缩小期间自带一份 [background]，
 *   与上一页分开。取消时弹回原位，完成时回到“图库”。在“图库”Tab 返回则交给系统退出。
 * - Android 13 及以下没有手势进度，返回键直接完成。
 */
@Composable
fun TabPredictiveBack(
    enabled: Boolean,
    onBack: () -> Unit,
    previous: @Composable () -> Unit,
    background: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val swipeEdge = remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val scope = rememberCoroutineScope()
    // 只在手势进行中及取消回弹期间组合上一页，平时不多占一份页面；回弹会短暂越过 0，不能只看进度
    val gestureActive by remember { derivedStateOf { progress.value > 0f || progress.isRunning } }

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                swipeEdge.intValue = event.swipeEdge
                progress.snapTo(event.progress)
            }
            onBack()
            progress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            // 手势取消：处理器协程已取消，回弹放到组合作用域里执行
            scope.launch { progress.animateTo(0f, SpringAnimations.BackCancel) }
            throw cancelled
        }
    }

    Box(modifier = modifier) {
        if (gestureActive) {
            // 预览只供观看：读屏不重复朗读，手势期间也不会收到点击
            Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { previous() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    val scale = BackGestureTransform.scale(p)
                    scaleX = scale
                    scaleY = scale
                    translationX = BackGestureTransform.translationX(p, size.width, swipeEdge.intValue == BackEventCompat.EDGE_RIGHT)
                    shape = RoundedCornerShape(BackGestureTransform.cornerRadius(p))
                    clip = p > 0f
                },
        ) {
            // 平时整页透明，透出主界面背景；缩小时补上自己的背景，避免与上一页叠在一起
            if (gestureActive) background()
            content()
        }
    }
}

/** 返回手势进度到页面变换的映射；纯函数，便于单测。 */
object BackGestureTransform {
    /** 手势完成时页面缩到的比例。 */
    const val MinScale = 0.9f

    /** 最大横移占页面宽度的比例。 */
    const val MaxShiftFraction = 0.06f

    val MaxCornerRadius: Dp = 28.dp

    fun scale(progress: Float): Float = 1f - (1f - MinScale) * progress.coerceIn(0f, 1f)

    /** 从左缘划向右移，从右缘划向左移，页面跟手。 */
    fun translationX(progress: Float, width: Float, fromRightEdge: Boolean): Float {
        val shift = width * MaxShiftFraction * progress.coerceIn(0f, 1f)
        return if (fromRightEdge) -shift else shift
    }

    fun cornerRadius(progress: Float): Dp = MaxCornerRadius * progress.coerceIn(0f, 1f)
}
