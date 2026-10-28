package dev.abdus.apps.shimmer.ui.animation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

/**
 * P8.7 液态动画（WP8.7-04）
 *
 * 只用弹簧曲线，不用固定时长：手势中途被打断时会从当前速度接着走，不会跳变。
 */
object SpringAnimations {
    /** 卡片展开/收起、内容增减时的高度变化：轻微回弹，偏柔。 */
    val CardResize: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 预测性返回手势取消后页面回到原位。 */
    val BackCancel: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

/** 卡片尺寸变化时以弹簧过渡，替代瞬间跳变。 */
fun Modifier.liquidResize(): Modifier = animateContentSize(SpringAnimations.CardResize)
