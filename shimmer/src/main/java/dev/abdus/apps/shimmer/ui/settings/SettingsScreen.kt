package dev.abdus.apps.shimmer.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.filled.Refresh
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abdus.apps.shimmer.BuildConfig
import dev.abdus.apps.shimmer.R
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.navigation.LegacyPages
import dev.abdus.apps.shimmer.ui.navigation.tabContentPadding
import dev.abdus.apps.shimmer.ui.navigation.RefreshOnResume
import dev.abdus.apps.shimmer.ui.settings.components.AppearanceCard
import dev.abdus.apps.shimmer.ui.settings.components.AppearanceTransparencyCard
import dev.abdus.apps.shimmer.ui.theme.Spacing
import dev.abdus.apps.shimmer.p10.capability.CapabilityActivity

/**
 * P8.6 设置页面
 * 外观、可选兼容与高级设置。逐图停用列表统一位于播放页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: P8SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showAbout by remember { mutableStateOf(false) }
    val compatPackage = BuildConfig.COMPAT_MODULE_APPLICATION_ID
    var compatStatus by remember(context, compatPackage) {
        mutableStateOf(CompatModuleEntry.read(context, compatPackage))
    }

    // 调度页、诊断页返回后重新读取，避免显示进入前的旧值
    RefreshOnResume {
        viewModel.refresh()
        compatStatus = CompatModuleEntry.read(context, compatPackage)
    }

    // P9-GALLERY-03：恢复已停用图片等操作的反馈改用 toast，不遮挡设置项
    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.messageShown()
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            dev.abdus.apps.shimmer.ui.navigation.WallpaperPageTopAppBar(title = "设置")
        }
    ) { paddingValues ->
        dev.abdus.apps.shimmer.ui.navigation.WallpaperTargetContent {
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
                // 外观设置卡片
                AppearanceCard(
                    themeMode = uiState.themeMode,
                    onThemeModeSelected = { viewModel.setThemeMode(it) },
                )

                // 透明度自定义与悬浮底栏。
                AppearanceTransparencyCard(
                    settings = uiState.appearance,
                    onScrimAlphaChange = { viewModel.setScrimAlpha(it) },
                    onCardAlphaChange = { viewModel.setCardAlpha(it) },
                    onBottomBarAlphaChange = { viewModel.setBottomBarAlpha(it) },
                    onFloatingBottomBarToggle = { viewModel.toggleFloatingBottomBar(it) }
                )

                CompatModuleCard(
                    status = compatStatus,
                    onRefresh = { compatStatus = CompatModuleEntry.read(context, compatPackage) },
                    onOpen = {
                        val result = CompatModuleEntry.open(context, compatPackage)
                        compatStatus = CompatModuleEntry.read(context, compatPackage)
                        val message = when (result) {
                            CompatModuleEntry.OpenResult.OPENED -> null
                            CompatModuleEntry.OpenResult.NOT_INSTALLED -> "未安装可选兼容模块。桌面手势正常时无需安装。"
                            CompatModuleEntry.OpenResult.NO_LAUNCHER -> "模块入口不可用，请检查模块是否被停用。"
                            CompatModuleEntry.OpenResult.BLOCKED -> "系统未允许打开模块，请检查安装和应用状态。"
                        }
                        if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    },
                )

                // 高级设置卡片
                AdvancedCard(
                    staticAcceptanceEnabled = uiState.staticAcceptanceEnabled,
                    onStaticAcceptanceToggle = { viewModel.toggleStaticAcceptance(it) },
                    onDiagnosticsClick = { LegacyPages.openDiagnostics(context) },
                    onLockCapabilityClick = { context.startActivity(Intent(context, CapabilityActivity::class.java)) },
                )
                dev.abdus.apps.shimmer.ui.settings.components.AboutVersionCard(onAbout = { showAbout = true })
            }
        }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun CompatModuleCard(
    status: CompatModuleEntry.Status,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
) {
    BlurredCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("桌面手势兼容", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRefresh) {
                    Icon(androidx.compose.material.icons.Icons.Default.Refresh, contentDescription = "刷新安装状态")
                }
            }
            Text("可选 · 需要Root／LSPosed，作用域为系统框架", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            dev.abdus.apps.shimmer.ui.settings.components.SettingsActionRow(
                title = "打开兼容模块",
                description = when (status.availability) {
                    CompatModuleEntry.Availability.NOT_INSTALLED -> "未安装，不影响主应用使用"
                    CompatModuleEntry.Availability.UNAVAILABLE -> "暂时无法读取安装状态"
                    CompatModuleEntry.Availability.INSTALLED ->
                        if (status.canOpen) "${status.versionName ?: "已安装"}（${status.versionCode}）" else "模块入口不可用，请检查应用状态"
                },
                onClick = onOpen, enabled = status.canOpen,
            )
        }
    }
}

/**
 * 高级设置卡片
 */
@Composable
private fun AdvancedCard(
    staticAcceptanceEnabled: Boolean,
    onStaticAcceptanceToggle: (Boolean) -> Unit,
    onDiagnosticsClick: () -> Unit,
    onLockCapabilityClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val showFirstUseGuide = dev.abdus.apps.shimmer.ui.onboarding.LocalFirstUseGuide.current
    BlurredCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text("高级", style = MaterialTheme.typography.titleMedium)
            dev.abdus.apps.shimmer.ui.settings.components.SettingsActionRow(
                "重新查看使用引导", "含三种手势操作练习", showFirstUseGuide)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("调试与诊断", style = MaterialTheme.typography.bodyLarge)
                    if (staticAcceptanceEnabled) Text("静态验收已开启", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(if (expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
            }
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        Column(Modifier.weight(1f)) {
                            Text("静态验收", style = MaterialTheme.typography.bodyLarge)
                            Text("只暂停过渡动画，不暂停视频或自动换图", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = staticAcceptanceEnabled, onCheckedChange = onStaticAcceptanceToggle)
                    }
                    dev.abdus.apps.shimmer.ui.settings.components.SettingsActionRow(
                        "锁屏能力检查", "查看系统对锁屏壁纸的支持", onLockCapabilityClick)
                    dev.abdus.apps.shimmer.ui.settings.components.SettingsActionRow(
                        "诊断", "查看运行状态与排查信息", onDiagnosticsClick)
                }
            }
        }
    }
}

/**
 * 关于对话框：显示实际安装包的身份，便于测试时核对候选版本
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(BuildConfig.APPLICATION_ID, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("个人壁纸管理：桌面与锁屏独立设置。")
                dev.abdus.apps.shimmer.ui.components.ProjectInformationLinks()
                HorizontalDivider()
                Text("基于开源项目 Shimmer 修改，个人非商业使用。")
                Text("保留原作者署名与许可；第三方声明及修改记录见项目仓库。")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
