package dev.abdus.apps.shimmer.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.animation.liquidResize
import dev.abdus.apps.shimmer.ui.effects.GlassEffectDefaults

/**
 * 半透明卡片：透出下层 [WallpaperBackdrop] 的模糊壁纸，不使用折射着色器。
 *
 * 卡片自身不做模糊：RenderEffect 只能模糊绘制在它之内的内容，加在卡片上会把卡片
 * 自己的文字糊掉。背后的模糊统一由页面最底层的背景完成。
 */
@Composable
fun BlurredCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = GlassEffectDefaults.cardContainerColor()
    Card(
        modifier = modifier.liquidResize(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}
