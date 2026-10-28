package dev.abdus.apps.shimmer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 设计系统形状规范
 * 参考 KernelSU 和 LSPosed 的圆角风格
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // 小组件、标签
    small = RoundedCornerShape(8.dp),        // 芯片、小卡片
    medium = RoundedCornerShape(12.dp),      // 按钮、输入框
    large = RoundedCornerShape(16.dp),       // 卡片
    extraLarge = RoundedCornerShape(28.dp),  // 对话框、底部表单
)
