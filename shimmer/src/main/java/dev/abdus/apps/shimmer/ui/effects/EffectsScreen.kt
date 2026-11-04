package dev.abdus.apps.shimmer.ui.effects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abdus.apps.shimmer.ui.effects.components.GlobalSettingsCard
import dev.abdus.apps.shimmer.ui.effects.components.SlideDirectionCard
import dev.abdus.apps.shimmer.ui.effects.components.TransitionSettingsCard
import dev.abdus.apps.shimmer.ui.navigation.LegacyPages
import dev.abdus.apps.shimmer.ui.navigation.tabContentPadding
import dev.abdus.apps.shimmer.ui.navigation.RefreshOnResume
import dev.abdus.apps.shimmer.ui.theme.Spacing
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.ui.navigation.wallpaperTargetViewModel
import dev.abdus.apps.shimmer.ui.navigation.WallpaperTargetUi

/**
 * P8.5 特效页面
 * 整合全局设置、过渡效果、编辑入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsScreen(
    modifier: Modifier = Modifier,
    wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME,
    viewModel: EffectsViewModel = wallpaperTargetViewModel(wallpaperTarget, ::EffectsViewModel)
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    RefreshOnResume { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            dev.abdus.apps.shimmer.ui.navigation.WallpaperPageTopAppBar(title = "特效")
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    // P9-FLOAT-INSET-01：悬浮底栏开启时底部多留白，最后一项才不被药丸挡住
                    .padding(tabContentPadding()),
                verticalArrangement = Arrangement.spacedBy(Spacing.l)
            ) {
                // 全局默认设置
                GlobalSettingsCard(
                    selectedFitMode = uiState.selectedFitMode,
                    onFitModeSelected = { viewModel.setFitMode(it) },
                    blurFillEnabled = uiState.blurFillEnabled,
                    onBlurFillToggle = { viewModel.toggleBlurFill(it) },
                    inheritPreviousEnabled = uiState.inheritPreviousEnabled,
                    onInheritPreviousToggle = { viewModel.toggleInheritPrevious(it) }
                )

                // 过渡效果设置
                TransitionSettingsCard(
                    selectedEffect = uiState.selectedTransition,
                    onEffectSelected = { viewModel.setTransitionEffect(it) },
                    duration = uiState.transitionDuration,
                    onDurationChange = { viewModel.setTransitionDuration(it) }
                )

                // P9-EFFECTS-01：滑动方向原先只在旧 P5 过渡页可改，现迁入本页。
                // 旧页的过渡诊断（X1～X7 面板）按第三批范围冻结不迁入，仍在旧界面。
                SlideDirectionCard(
                    selected = uiState.slideDirection,
                    onSelected = { viewModel.setSlideDirection(it) },
                )

                // 编辑当前图片（备用入口）。
                //
                // P9-EFFECTS-01 要求"不再跳转旧界面"，但这一处按用户 2026-10-06 决定**保留**：
                // 它打开的 P3EditActivity 是 P3 阶段交付、版本 18 已通过目标机验收的正式实时编辑
                // 页，不是调试界面。正式入口是桌面两指双击，此按钮是手势失效时的退路。
                // 其余跳转（添加来源、完整播放控制、过渡诊断、更多调度设置）已全部消除。
                if (WallpaperTargetUi.canOpenLegacyEditor(wallpaperTarget)) Button(
                    onClick = { LegacyPages.openEditor(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text("编辑当前图片")
                }
                else Text(
                    "锁屏编辑请在锁屏触发编辑手势，并完成系统认证。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }


}

/**
 * 适配方式；智能按图片比例自动选择，对应 P3 的 SMART 标记
 */
enum class FitMode(val displayName: String) {
    SMART("智能（按图片比例自动选择）"),
    FILL("铺满"),
    FIT_WIDTH("适宽"),
    FIT_HEIGHT("适高"),
    COMPLETE("完整")
}

/**
 * 过渡效果
 */
enum class TransitionEffect(val displayName: String) {
    NONE("无动画"),
    FADE_IN_OUT("淡入淡出"),
    FADE_TO_BLACK("淡出到背景再淡入"),
    SLIDE("方向滑动"),
    ZOOM("缩放"),
    RANDOM("随机效果")
}
