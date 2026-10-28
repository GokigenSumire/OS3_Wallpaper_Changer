package dev.abdus.apps.shimmer.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ui.components.BlurredNavigationBar
import dev.abdus.apps.shimmer.ui.components.WallpaperBackdrop
import dev.abdus.apps.shimmer.ui.components.rememberBackdropFrame
import dev.abdus.apps.shimmer.ui.effects.EffectsScreen
import dev.abdus.apps.shimmer.ui.effects.LocalAppearanceSettings
import dev.abdus.apps.shimmer.ui.gallery.GalleryScreen
import dev.abdus.apps.shimmer.ui.playback.PlaybackScreen
import dev.abdus.apps.shimmer.ui.settings.SettingsScreen
import dev.abdus.apps.shimmer.ui.theme.Spacing
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import androidx.compose.runtime.key

/**
 * 主导航框架：模糊壁纸背景 + 半透明底栏 + 内容区域（P8.7）。
 * 非“图库”Tab 的返回手势回到“图库”，手势中露出只读的“图库”预览（P8.8，见 [TabPredictiveBack]）。
 */
@Composable
fun MainNavigation(
    modifier: Modifier = Modifier,
) {
    // 按路由保存，旋转屏幕或系统回收后仍停在原 Tab
    var selectedRoute by rememberSaveable { mutableStateOf(NavigationDestination.Gallery.route) }
    var selectedTargetName by rememberSaveable { mutableStateOf(WallpaperTarget.HOME.name) }
    val wallpaperTarget = WallpaperTargetUi.restoreTarget(selectedTargetName)
    // P9：动画切页会移除离开的页面，按路由保存列表位置、偏移和页面 rememberSaveable 状态。
    // 只读的预测返回预览不经过此容器，避免与正式图库共享保存键。
    val tabStateHolder = rememberSaveableStateHolder()
    val selectedDestination = try {
        NavigationDestination.items.firstOrNull { it?.route == selectedRoute }
            ?: NavigationDestination.Gallery
    } catch (e: Exception) {
        NavigationDestination.Gallery
    }

    // 主界面背景与返回手势中缩小页面的背景共用同一张图
    val backdropFrame = rememberBackdropFrame(wallpaperTarget)

    Box(modifier.fillMaxSize()) {
        WallpaperBackdrop(backdropFrame)
        val floatingBar = LocalAppearanceSettings.current.floatingBottomBarEnabled
        Scaffold(
            // 透明容器，透出背后的模糊壁纸
            containerColor = Color.Transparent,
            topBar = {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.m)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        WallpaperTarget.entries.forEach { target ->
                            FilterChip(
                                selected = wallpaperTarget == target,
                                onClick = { selectedTargetName = target.name },
                                label = { Text(WallpaperTargetUi.label(target)) },
                            )
                        }
                    }
                    if (selectedDestination == NavigationDestination.Settings) {
                        Text("设置中的外观与静态验收对桌面、锁屏全局生效。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            // P9-FLOAT-OVERLAY-01：悬浮形态不进 bottomBar 槽。Scaffold 会为该槽预留等高空间，
            // 页面内容到此为止，于是药丸两侧与下方是 Scaffold 的空白留白区而非透出的页面内容——
            // 用户实测的"仍是在底栏上悬浮，胶囊之外页面内容不能正常显示"就是这个。悬浮形态改由
            // 下面的 Box 叠在内容之上，页面因此铺到屏幕底部、从药丸周围透出。
            bottomBar = {
                if (!floatingBar) {
                    BottomNavigationBar(
                        selectedDestination = selectedDestination,
                        onDestinationSelected = { selectedRoute = it.route }
                    )
                }
            }
        ) { paddingValues ->
            TabPredictiveBack(
                enabled = selectedDestination != NavigationDestination.Gallery,
                onBack = { selectedRoute = NavigationDestination.Gallery.route },
                previous = {
                    key(wallpaperTarget) {
                        GalleryScreen(wallpaperTarget = wallpaperTarget, interactive = false)
                    }
                },
                background = { WallpaperBackdrop(backdropFrame, Modifier.extendToWindow(paddingValues)) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    // 外层已按状态栏/导航栏留白；声明已消费，内层页面的顶栏不再重复加状态栏高度
                    .consumeWindowInsets(paddingValues),
            ) {
                val items = NavigationDestination.items
                val selectedIndex = items.indexOf(selectedDestination).coerceAtLeast(0)
                val swipeEnabled = LocalAppearanceSettings.current.floatingBottomBarEnabled

                Box(
                    Modifier
                        .fillMaxSize()
                        // P9-FLOAT-SHAPE-03：内容区左右滑动切换 Tab。只在悬浮底栏开启时生效，
                        // 与该形态的底栏拖动配套。
                        .then(
                            if (swipeEnabled) {
                                Modifier.tabSwipe(
                                    selectedIndex = selectedIndex,
                                    tabCount = items.size,
                                    onTabSelected = { index ->
                                        items.getOrNull(index)?.let { selectedRoute = it.route }
                                    },
                                )
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (swipeEnabled) {
                        // 用户在版本 36 后补充"左右滑动添加简单卡片滑动过渡效果"。
                        // 只在滑动可用的悬浮形态加过渡：贴边形态仍是即时切换，与版本 34 一致，
                        // 也避免与 TabPredictiveBack 的返回预览动画叠加成两层位移。
                        TabContentTransition(targetIndex = selectedIndex) { index ->
                            TabContent(items.getOrNull(index) ?: NavigationDestination.Gallery, tabStateHolder, wallpaperTarget)
                        }
                    } else {
                        TabContent(selectedDestination, tabStateHolder, wallpaperTarget)
                    }
                }
            }
        }

        // 悬浮形态叠在内容之上，居中贴近屏幕底边。页面内容因此铺满整屏，从药丸周围透出。
        if (floatingBar) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = Spacing.s),
                contentAlignment = Alignment.BottomCenter,
            ) {
                BottomNavigationBar(
                    selectedDestination = selectedDestination,
                    onDestinationSelected = { selectedRoute = it.route }
                )
            }
        }
    }
}

/**
 * 按 Tab 取对应页面。
 *
 * 抽出来是因为有两个调用点：加过渡的悬浮形态（[TabContentTransition] 内）与即时切换的贴边形态。
 */
@Composable
private fun TabContent(
    destination: NavigationDestination,
    stateHolder: SaveableStateHolder,
    wallpaperTarget: WallpaperTarget,
) {
    stateHolder.SaveableStateProvider(WallpaperTargetUi.stateKey(destination.route, wallpaperTarget)) {
    when (destination) {
        NavigationDestination.Gallery -> GalleryScreen(wallpaperTarget = wallpaperTarget)
        NavigationDestination.Playback -> PlaybackScreen(wallpaperTarget = wallpaperTarget)
        NavigationDestination.Effects -> EffectsScreen(wallpaperTarget = wallpaperTarget)
        NavigationDestination.Settings -> SettingsScreen()
    }
    }
}

/**
 * 向外扩出 Scaffold 留白，铺满整个窗口：手势开始时与主界面背景的裁切完全重合，
 * 之后随页面一起缩放并被圆角裁切。
 */
private fun Modifier.extendToWindow(padding: PaddingValues): Modifier = layout { measurable, constraints ->
    val start = padding.calculateStartPadding(LayoutDirection.Ltr).roundToPx()
    val end = padding.calculateEndPadding(LayoutDirection.Ltr).roundToPx()
    val top = padding.calculateTopPadding().roundToPx()
    val bottom = padding.calculateBottomPadding().roundToPx()
    val placeable = measurable.measure(
        Constraints.fixed(constraints.maxWidth + start + end, constraints.maxHeight + top + bottom)
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-start, -top) }
}

/**
 * 底栏导航组件
 */
@Composable
private fun BottomNavigationBar(
    selectedDestination: NavigationDestination,
    onDestinationSelected: (NavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = NavigationDestination.items
    // P9-FLOAT-SHAPE-02/03：悬浮形态自己画横跨图标与文字的选中胶囊，并支持横向拖动切换，
    // 所以要知道当前索引与总数；贴边形态用不到这两个值，仍由 NavigationBarItem 画指示器。
    val floating = LocalAppearanceSettings.current.floatingBottomBarEnabled
    val selectedIndex = items.indexOf(selectedDestination).coerceAtLeast(0)

    BlurredNavigationBar(
        modifier = modifier,
        selectedIndex = selectedIndex,
        tabCount = items.size,
        onTabSelected = { index -> items.getOrNull(index)?.let(onDestinationSelected) },
    ) {
        items.forEach { destination ->
            val selected = selectedDestination == destination
            if (floating) {
                // P9-FLOAT-RIPPLE-01：版本 36 用户实测"切换实时仍残留有包含图标的特效，可以删掉"。
                // 那是 NavigationBarItem 内部的点击涟漪，既不受 indicatorColor 控制也无法通过
                // 参数关闭。悬浮形态改用自绘项：选中反馈已由横跨整项的胶囊承担，这里只要图标与
                // 文字和一个无涟漪的点击区。
                FloatingTabItem(
                    selected = selected,
                    icon = iconOf(destination),
                    label = stringResource(destination.titleRes),
                    onClick = { onDestinationSelected(destination) },
                )
            } else {
                NavigationBarItem(
                    selected = selected,
                    onClick = { onDestinationSelected(destination) },
                    // 标签已朗读 Tab 名称，图标不重复描述
                    icon = { Icon(iconOf(destination), contentDescription = null) },
                    label = { Text(stringResource(destination.titleRes)) },
                )
            }
        }
    }
}

/**
 * 悬浮底栏的 Tab 项：无涟漪、无自带指示器，只有图标与文字。
 *
 * 选中状态只通过内容颜色体现——底色胶囊由 [BlurredNavigationBar] 统一绘制并平移，所以这里不画
 * 任何背景，否则会与那层胶囊叠加。
 */
@Composable
private fun RowScope.FloatingTabItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // indication = null 去掉涟漪；role = Tab 保留无障碍语义
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // P9-FLOAT-SIZE-03：用户实测"内部图标文字显得有一点偏大"，图标 24dp → 20dp、文字 labelMedium → labelSmall
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(FloatingTabIconSize))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

private val FloatingTabIconSize = 20.dp

private fun iconOf(destination: NavigationDestination): ImageVector = when (destination) {
    NavigationDestination.Gallery -> Icons.Filled.PhotoLibrary
    NavigationDestination.Playback -> Icons.Filled.PlayCircle
    NavigationDestination.Effects -> Icons.Filled.AutoAwesome
    NavigationDestination.Settings -> Icons.Filled.Settings
}
