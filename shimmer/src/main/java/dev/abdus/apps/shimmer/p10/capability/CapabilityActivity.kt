package dev.abdus.apps.shimmer.p10.capability

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.abdus.apps.shimmer.BuildConfig
import dev.abdus.apps.shimmer.ShimmerWallpaperService
import dev.abdus.apps.shimmer.ui.ShimmerTheme
import dev.abdus.apps.shimmer.ui.theme.ThemeModeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Internal user-driven page; incoming Intent extras never start a preview or export. */
class CapabilityActivity : ComponentActivity() {
    private var pendingExport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = ThemeModeStore(this).load()
        setContent {
            val snapshot by CapabilityObservations.snapshots.collectAsState()
            var previewComponent by remember { mutableStateOf<Class<*>?>(null) }
            var writing by remember { mutableStateOf(false) }
            val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                val text = pendingExport
                pendingExport = null
                if (uri != null && text != null) {
                    writing = true
                    lifecycleScope.launch {
                        val success = withContext(Dispatchers.IO) {
                            try {
                                val output = contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
                                output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                                true
                            } catch (_: Exception) { false }
                        }
                        writing = false
                        Toast.makeText(this@CapabilityActivity,
                            if (success) "能力摘要已导出" else "导出失败，内存摘要保留，可重试", Toast.LENGTH_LONG).show()
                    }
                }
            }
            ShimmerTheme(darkTheme = theme.isDark(isSystemInDarkTheme())) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Text("P10 独立锁屏能力确认", style = MaterialTheme.typography.headlineSmall)
                        Text("这是标定，不是锁屏播放器。两种颜色只确认系统目标和输入，双击等动作只计数，不切图或进入编辑。",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("原生基线前请在现兼容模块保存关闭并确认恢复；此页不能自动核实模块开关。应用标定会替换所选目标的壁纸，图库、历史和排序不修改。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { previewComponent = HomeCapabilityWallpaperService::class.java }, modifier = Modifier.fillMaxWidth()) {
                                Text("预览蓝色 HOME 标定组件")
                            }
                            Button(onClick = { previewComponent = LockCapabilityWallpaperService::class.java }, modifier = Modifier.fillMaxWidth()) {
                                Text("预览紫色 LOCK 标定组件")
                            }
                            OutlinedButton(onClick = { openSystemSelector() }, modifier = Modifier.fillMaxWidth()) { Text("系统动态壁纸选择器") }
                            OutlinedButton(onClick = { previewComponent = ShimmerWallpaperService::class.java }, modifier = Modifier.fillMaxWidth()) {
                                Text("恢复图片壁纸：打开正式组件预览")
                            }
                            OutlinedButton(onClick = {
                                pendingExport = CapabilityReport.format(CapabilityObservations.snapshot(),
                                    "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_CODE}/${BuildConfig.VERSION_NAME}")
                                try { exporter.launch("Hyper-Wallpaper-P10-Capability-${System.currentTimeMillis()}.txt") }
                                catch (_: RuntimeException) { pendingExport = null; notice("无法打开文件保存页") }
                            }, enabled = !writing, modifier = Modifier.fillMaxWidth()) { Text(if (writing) "正在保存…" else "导出当前能力摘要") }
                            TextButton(onClick = { finish() }) { Text("返回应用") }
                        }
                    }
                    item {
                        Text("进程会话 ${snapshot.session.take(8)} · ${snapshot.engines.size} 个实例 · 丢弃事件 ${snapshot.droppedEvents}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("组件名不证明系统目标；flags=1桌面、2锁屏、3共享，预览不算正式独立实例。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    items(snapshot.engines, key = { it.id }) { row ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${row.id} · ${if (row.route.isPreview) "预览" else "正式"}", style = MaterialTheme.typography.titleMedium)
                                Text("组件标记 ${row.markerTarget} / 实测 ${row.route.exposure} / flags ${row.route.observedFlags ?: "未知"}")
                                Text("存活 ${row.alive} · 可见 ${row.visible} · Surface ${row.surfaceReady} · ${row.width}×${row.height}")
                                Text("原始事件 ${row.rawTouchEvents}，最多 ${row.maxPointerCount} 指；双击 ${row.doubleTaps}／三击 ${row.tripleTaps}／双指双击 ${row.twoFingerDoubleTaps}")
                                Text("Surface创建 ${row.surfaceCreates}／销毁 ${row.surfaceDestroys} · flags变化 ${row.flagChanges} · 失败 ${row.failureCount}")
                            }
                        }
                    }
                    if (snapshot.engines.isEmpty()) item { Text("尚未创建标定实例。请通过系统预览和目标选择后再返回。") }
                }
                previewComponent?.let { component ->
                    AlertDialog(onDismissRequest = { previewComponent = null },
                        title = { Text("由系统选择应用目标") },
                        text = { Text("接下来只打开指定组件的系统预览。请自行选择桌面或锁屏；组件名称不是强制目标，也不代表系统支持独立应用。取消预览不会应用标定。") },
                        confirmButton = { TextButton(onClick = { previewComponent = null; openPreview(component) }) { Text("打开系统预览") } },
                        dismissButton = { TextButton(onClick = { previewComponent = null }) { Text("取消") } })
                }
                }
            }
        }
    }

    private fun openPreview(component: Class<*>) {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, component)))
        } catch (_: ActivityNotFoundException) { notice("直接预览未打开，请使用系统动态壁纸选择器") }
        catch (_: SecurityException) { notice("系统阻止预览，请检查系统壁纸权限或使用选择器") }
    }

    private fun openSystemSelector() {
        try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
        catch (_: ActivityNotFoundException) { notice("系统未提供动态壁纸选择器，请手动进入系统壁纸设置") }
        catch (_: SecurityException) { notice("系统阻止打开选择器，请手动进入系统壁纸设置") }
    }

    private fun notice(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
