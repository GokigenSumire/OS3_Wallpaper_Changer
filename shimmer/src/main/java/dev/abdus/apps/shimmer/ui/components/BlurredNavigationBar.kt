// 悬浮底栏的形状、尺寸、选中胶囊与滑动切换参考 KernelSU（GPL-3.0）的
// manager/app/src/main/java/me/weishu/kernelsu/ui/component/FloatingBottomBar.kt：
// 胶囊形状（CircleShape）、固定 64dp 高度与 4dp 内边距、选中胶囊为按连续进度平移的独立层、
// 以及用连续值而非离散索引驱动的横向拖动切换。
// 依本项目自 P9 起的许可处置（CHG-P9-LICENSE-SELFUSE-20261006）保留该声明。

package dev.abdus.apps.shimmer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.effects.GlassEffectDefaults
import dev.abdus.apps.shimmer.ui.effects.LocalAppearanceSettings
import dev.abdus.apps.shimmer.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * P8.7 半透明底栏：叠在 [WallpaperBackdrop] 之上，透出背后的模糊壁纸。
 * 与 [BlurredCard] 相同，底栏自身不做 RenderEffect 模糊，以免糊掉图标和文字。
 *
 * P9-GLASS-03：开启"悬浮底栏"后脱离屏幕底边；关闭时保持版本 34 已验证的贴边形态。
 *
 * 版本 35 的悬浮形态经用户实测提出四点（`P9-FLOAT-SHAPE-01`～`04`）：圆角不够明显、高度偏低、
 * 选中白底只包图标不包文字且色块过重、希望左右滑动切换 Tab。本版按参考实现返工：
 * - 形状改 [CircleShape]、高度固定 [FloatingBarHeight]，得到胶囊外形；
 * - 选中指示器改为一个按连续进度平移、宽度等于单个 Tab 的独立胶囊层，因此自然同时覆盖图标与
 *   文字；底色取主色低透明度而非实心白底；
 * - 内容区支持横向拖动，松手按最近的 Tab 吸附。
 *
 * [selectedIndex] 与 [tabCount] 只有悬浮形态需要（用于定位选中胶囊与换算拖动距离）；贴边形态
 * 仍由 Material3 的 `NavigationBarItem` 自己画指示器，两条路径互不影响。
 */
@Composable
fun BlurredNavigationBar(
    modifier: Modifier = Modifier,
    selectedIndex: Int = 0,
    tabCount: Int = 1,
    onTabSelected: (Int) -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalAppearanceSettings.current.floatingBottomBarEnabled) {
        FloatingBar(
            modifier = modifier,
            selectedIndex = selectedIndex,
            tabCount = tabCount,
            onTabSelected = onTabSelected,
            content = content,
        )
    } else {
        val edgeColor = GlassEffectDefaults.bottomBarContainerColor()
        NavigationBar(
            modifier = modifier,
            containerColor = edgeColor,
            tonalElevation = 0.dp,
            content = content,
        )
    }
}

@Composable
private fun FloatingBar(
    modifier: Modifier,
    selectedIndex: Int,
    tabCount: Int,
    onTabSelected: (Int) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val safeTabCount = tabCount.coerceAtLeast(1)

    // 内容区总宽与单个 Tab 宽度；拖动距离按 Tab 宽度换算成进度
    var contentWidthPx by remember { mutableFloatStateOf(0f) }
    val tabWidthPx = if (safeTabCount > 0) contentWidthPx / safeTabCount else 0f

    // P9-SWIPE-REBOUND-01 第二轮修复无效后的重做。
    //
    // 版本 38 的做法是两个状态源：拖动时用 dragProgress，松手后交还给
    // animateFloatAsState(targetValue = selectedIndex)。折返的真因在于后者——它在整个拖动期间的
    // 当前值一直停在旧索引（拖动时 selectedIndex 未变），所以交还控制权的瞬间，位置从松手处跳回
    // 旧索引附近，再补间到目标。调整通知顺序对此无效。
    //
    // 本轮改为单一 Animatable 持有位置：拖动中 snapTo 跟手，松手时 animateTo 从当前值接续，外部
    // 点击切换同样 animateTo。位置只有一个来源，不存在两个状态互相覆盖。
    val indicator = remember { Animatable(selectedIndex.toFloat()) }
    val scope = rememberCoroutineScope()
    val progress = indicator.value
    val barColor = GlassEffectDefaults.bottomBarContainerColor()

    // 手势协程的 key 不含 selectedIndex（否则每次切 Tab 都会重建、中断拖动），所以用
    // rememberUpdatedState 读最新值。
    val currentIndex by rememberUpdatedState(selectedIndex)

    // 点击切换或外部改变选中项时，从当前位置动画到新位置。拖动结束时手势侧已自行 animateTo，
    // 这里的条件判断会因位置已等于目标而跳过，不会二次触发。
    LaunchedEffect(selectedIndex) {
        if (indicator.value != selectedIndex.toFloat()) {
            indicator.animateTo(selectedIndex.toFloat(), IndicatorSpring)
        }
    }

    Box(
        modifier = modifier
            // P9-FLOAT-OVERLAY-01：用户实测"这个悬浮仍是在底栏上悬浮，并非在页面上悬浮，
            // 胶囊之外的底栏位置页面内容不能正常显示仍被模糊"。原因有两层：
            // ①胶囊 fillMaxWidth 占满整宽，"胶囊之外"其实没有空隙；
            // ②它放在 Scaffold 的 bottomBar 槽里，Scaffold 会为底栏预留等高空间，页面内容到此
            //   为止，所以胶囊两侧与下方是空白的 Scaffold 留白区，而非透出的页面内容。
            // 本轮改为 wrapContentWidth 并居中——胶囊只占内容所需宽度，真正"悬浮"于页面之上；
            // 脱离 bottomBar 槽改由 MainNavigation 以 Box 叠放（见那里的注释）。
            // P9-FLOAT-SIZE-02：用户实测"尺寸仍需缩小，长度缩小至原来的 3/4 更居中一些，宽度也
            // 对应减少"。原先占满可用宽度；本轮限制为屏幕宽度的 [FloatingBarWidthFraction] 并
            // 居中，高度随之降到 [FloatingBarHeight]。
            .fillMaxWidth(FloatingBarWidthFraction)
            .navigationBarsPadding()
            .padding(vertical = Spacing.s)
    ) {
        Box(
            Modifier
                // 药丸形：高度减小、宽度只包内容，圆角等于高度一半由 CircleShape 保证
                .height(FloatingBarHeight)
                .clip(CircleShape)
                .background(barColor, CircleShape)
                .padding(FloatingBarInnerPadding)
                .onGloballyPositioned { contentWidthPx = it.size.width.toFloat() }
                .pointerInput(safeTabCount, tabWidthPx, isLtr) {
                    if (tabWidthPx <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            // 从当前位置起步（可能正在动画中途，用 value 而非索引更跟手）
                            scope.launch { indicator.snapTo(indicator.value) }
                        },
                        onDragEnd = {
                            // 从手指离开的位置直接动画到目标，不经由任何回落
                            val target = indicator.value
                                .roundToInt()
                                .coerceIn(0, safeTabCount - 1)
                            scope.launch { indicator.animateTo(target.toFloat(), IndicatorSpring) }
                            if (target != currentIndex) onTabSelected(target)
                        },
                        onDragCancel = {
                            scope.launch {
                                indicator.animateTo(currentIndex.toFloat(), IndicatorSpring)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val delta = dragAmount / tabWidthPx * if (isLtr) 1f else -1f
                            val next = (indicator.value + delta)
                                .coerceIn(0f, (safeTabCount - 1).toFloat())
                            scope.launch { indicator.snapTo(next) }
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 选中胶囊：宽度为单个 Tab，按进度平移，因此横跨图标与文字
            if (tabWidthPx > 0f) {
                Box(
                    Modifier
                        .graphicsLayer {
                            val offset = progress * tabWidthPx
                            translationX = if (isLtr) offset else -offset
                        }
                        .height(FloatingBarHeight - FloatingBarInnerPadding * 2)
                        .width(with(density) { tabWidthPx.toDp() })
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = IndicatorAlpha),
                            CircleShape,
                        )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * 药丸高度。
 *
 * 版本 35 用 Material3 默认 80dp（用户评"偏低"，指的是形状不像胶囊）；版本 36 改 64dp 后用户评
 * "高度合适，但整体仍偏大，没有药丸胶囊的感觉"。本轮降到 56dp 并改为宽度只包内容，使高宽都收紧，
 * 圆角由 CircleShape 取高度一半、形成真正的药丸。
 */
private val FloatingBarHeight = 48.dp

/**
 * 药丸占屏幕宽度的比例。
 *
 * 用户在版本 38 要求"长度缩小至原来的 3/4 更居中一些"。原先是占满可用宽度（减去两侧 16dp 留白），
 * 版本 39 取 0.75 并居中。P9-FLOAT-SIZE-03：用户实测版本 39"比版本 38 更短但仍需再缩短一些"，
 * 版本 41 取 0.62，并同步缩小 MainNavigation 的 FloatingTabItem 图标与文字。
 * 用户随后要求稍微加宽拉长，本轮取 0.68，介于此前 0.62 与 0.75 之间；高度、图标和文字保持不变。
 */
private const val FloatingBarWidthFraction = 0.68f

/** 选中胶囊的移动动画。拖动与点击共用，保证两种路径观感一致。 */
private val IndicatorSpring = spring<Float>(
    dampingRatio = 0.8f,
    stiffness = Spring.StiffnessMediumLow,
)

/** 胶囊与选中指示器之间的留白，参考实现为 4dp。 */
private val FloatingBarInnerPadding = 4.dp

/** 选中指示器用主色低透明度，替代版本 35 的实心大白底。 */
private const val IndicatorAlpha = 0.18f

