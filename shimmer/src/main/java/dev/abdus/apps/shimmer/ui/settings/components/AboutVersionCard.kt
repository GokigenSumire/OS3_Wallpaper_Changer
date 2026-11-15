package dev.abdus.apps.shimmer.ui.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abdus.apps.shimmer.BuildConfig
import dev.abdus.apps.shimmer.R
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import personal.wallpaper.update.ReleaseUpdateChecker

data class UpdateUiState(val checking: Boolean = false, val result: ReleaseUpdateChecker.Result? = null)

class ReleaseUpdateViewModel(
    private val checker: ReleaseUpdateChecker = ReleaseUpdateChecker(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    fun check() {
        if (mutableState.value.checking) return
        mutableState.value = UpdateUiState(checking = true)
        viewModelScope.launch(dispatcher) {
            val result = checker.check(ReleaseUpdateChecker.Product.MAIN, BuildConfig.VERSION_CODE.toLong())
            mutableState.value = UpdateUiState(result = result)
        }
    }
}

@Composable
fun AboutVersionCard(onAbout: () -> Unit, updates: ReleaseUpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val handler = LocalUriHandler.current
    val state by updates.state.collectAsState()
    val result = state.result
    BlurredCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.shimmer), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Text("${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Text(if (state.checking) "正在检查更新…" else result?.message() ?: "手动检查最新正式版本",
                style = MaterialTheme.typography.bodyMedium,
                color = if (result?.state == ReleaseUpdateChecker.State.AVAILABLE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = updates::check, enabled = !state.checking, modifier = Modifier.weight(1f)) {
                    if (state.checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("检查更新")
                }
                OutlinedButton(onClick = {
                    try { handler.openUri(result?.pageUrl ?: ReleaseUpdateChecker.Product.MAIN.releasesUrl()) }
                    catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
                    } catch (_: IllegalArgumentException) {
                        Toast.makeText(context, "无法打开发布页", Toast.LENGTH_SHORT).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(context, "系统未允许打开发布页", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f)) { Text("发布与下载") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("版本信息",
                        "${context.getString(R.string.app_name)}\n${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n${BuildConfig.APPLICATION_ID}"))
                    Toast.makeText(context, "已复制版本信息", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) { Text("复制版本") }
                TextButton(onClick = onAbout, modifier = Modifier.weight(1f)) { Text("关于与项目") }
            }
        }
    }
}
