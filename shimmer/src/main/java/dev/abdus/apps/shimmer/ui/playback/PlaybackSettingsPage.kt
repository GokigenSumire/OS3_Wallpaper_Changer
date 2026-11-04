package dev.abdus.apps.shimmer.ui.playback

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p4.SchedulePageTarget
import dev.abdus.apps.shimmer.ui.effects.EffectsViewModel
import dev.abdus.apps.shimmer.ui.effects.components.IntervalSettingsDialog
import dev.abdus.apps.shimmer.ui.navigation.RefreshOnResume
import dev.abdus.apps.shimmer.ui.navigation.tabContentPadding
import dev.abdus.apps.shimmer.ui.navigation.wallpaperTargetViewModel
import dev.abdus.apps.shimmer.ui.settings.components.MediaSettingsCard
import dev.abdus.apps.shimmer.ui.settings.components.ScheduleCard
import dev.abdus.apps.shimmer.ui.theme.Spacing

/** Existing P4/P11 controls, kept separate from the long playback queue. Stores are unchanged. */
@Composable
fun PlaybackSettingsPage(
    wallpaperTarget: WallpaperTarget,
    scrollState: ScrollState = rememberScrollState(),
    onClearHistory: () -> Unit,
    historyEnabled: Boolean = true,
    viewModel: EffectsViewModel = wallpaperTargetViewModel(wallpaperTarget, ::EffectsViewModel),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showIntervalDialog by remember { mutableStateOf(false) }
    RefreshOnResume { viewModel.refresh() }
    LaunchedEffect(uiState.message) {
        uiState.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.messageShown() }
    }
    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(tabContentPadding()),
        verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        ScheduleCard(
            intervalEnabled = uiState.intervalEnabled,
            onIntervalToggle = { viewModel.toggleInterval(it) },
            intervalLabel = uiState.intervalLabel,
            unlockEnabled = uiState.unlockEnabled,
            onUnlockToggle = { viewModel.toggleUnlock(it) },
            lockEnabled = uiState.lockEnabled,
            onLockToggle = { viewModel.toggleLock(it) },
            screenOffEnabled = uiState.screenOffEnabled,
            onScreenOffToggle = { viewModel.toggleScreenOff(it) },
            onDateRulesClick = { context.startActivity(SchedulePageTarget.createIntent(context, wallpaperTarget)) },
            onMoreScheduleClick = { showIntervalDialog = true },
        )
        MediaSettingsCard()
        Text("播放记录", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onClearHistory, enabled = historyEnabled, modifier = Modifier.fillMaxWidth()) { Text("清空播放历史") }

    }
    if (showIntervalDialog) IntervalSettingsDialog(
        value = uiState.intervalValue, unit = uiState.intervalUnit, clock = uiState.intervalClock,
        onDismiss = { showIntervalDialog = false },
        onConfirm = { v, u, c -> showIntervalDialog = false; viewModel.setInterval(v, u, c) },
    )
}
