package dev.abdus.apps.shimmer.ui.settings

import android.widget.Toast
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
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
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
                // P9-SETTINGS-02：调度已移到特效页，这里不再重复
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
                    onAboutClick = { showAbout = true }
                )
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
            Text("系统桌面手势兼容（可选）", style = MaterialTheme.typography.titleMedium)
            Text(
                when (status.availability) {
                    CompatModuleEntry.Availability.NOT_INSTALLED -> "未安装（不影响主应用使用）"
                    CompatModuleEntry.Availability.UNAVAILABLE -> "暂时无法读取模块安装状态"
                    CompatModuleEntry.Availability.INSTALLED ->
                        "已安装：${status.versionName ?: "版本名未提供"}（${status.versionCode}）"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "主壁纸无需 Root 或 LSPosed。仅桌面不转发手势时，按需安装兼容模块。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "模块需已有 Root／LSPosed，仅选“系统框架”。安装不等于生效；开启后持续运行、重启恢复，关闭时安全恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.availability == CompatModuleEntry.Availability.INSTALLED && !status.canOpen) {
                Text("模块入口不可用，请检查模块是否被停用。", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onOpen, enabled = status.canOpen, modifier = Modifier.fillMaxWidth()) {
                Text("打开兼容模块")
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("刷新安装状态")
            }
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
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BlurredCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("高级", style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.bodyMedium)
            }

            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("静态验收", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (staticAcceptanceEnabled) "已暂停过渡动画" else "开启后暂停过渡，不改变播放顺序",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = staticAcceptanceEnabled, onCheckedChange = onStaticAcceptanceToggle)
                }

                TextButton(onClick = onLockCapabilityClick, modifier = Modifier.fillMaxWidth()) {
                    Text("P10 独立锁屏能力确认", style = MaterialTheme.typography.bodyLarge)
                }

            // 诊断按钮：打开 P0 诊断页
            TextButton(
                onClick = onDiagnosticsClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text("诊断", style = MaterialTheme.typography.bodyLarge)
                }
            }

            // 关于按钮
            TextButton(
                onClick = onAboutClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text("关于", style = MaterialTheme.typography.bodyLarge)
                }
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
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                Text("包名：${BuildConfig.APPLICATION_ID}")
                Text("基于开源项目 Shimmer 修改，个人非商业使用。")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
