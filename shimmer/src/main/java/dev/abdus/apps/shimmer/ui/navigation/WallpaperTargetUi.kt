package dev.abdus.apps.shimmer.ui.navigation

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abdus.apps.shimmer.p10.WallpaperTarget

/** 页面切域只换读取者；不写播放器当前项、历史或应用系统壁纸。 */
object WallpaperTargetUi {
    fun label(target: WallpaperTarget): String = when (target) {
        WallpaperTarget.HOME -> "桌面 HOME"
        WallpaperTarget.LOCK -> "锁屏 LOCK"
    }

    fun stateKey(route: String, target: WallpaperTarget): String = "${target.name}:$route"
    fun viewModelKey(modelName: String, target: WallpaperTarget): String = "${target.name}:$modelName"
    fun restoreTarget(name: String): WallpaperTarget =
        WallpaperTarget.entries.firstOrNull { it.name == name } ?: WallpaperTarget.HOME
    fun canOpenLegacyEditor(target: WallpaperTarget): Boolean = target == WallpaperTarget.HOME
}

/** 单个目标的只读背景发布门；旧 IO 返回不得覆盖更新的同域读取。 */
class TargetBackdropReads {
    private var serial = 0L
    fun begin(): Long = ++serial
    fun isLatest(read: Long): Boolean = read == serial
}

@Composable
inline fun <reified T : ViewModel> wallpaperTargetViewModel(
    target: WallpaperTarget,
    crossinline create: (Application, WallpaperTarget) -> T,
): T {
    val application = LocalContext.current.applicationContext as Application
    val factory = remember(application, target) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
                require(modelClass == T::class.java) { "错误的目标页面 ViewModel 类型" }
                return create(application, target) as VM
            }
        }
    }
    return viewModel(
        key = WallpaperTargetUi.viewModelKey(T::class.java.name, target),
        factory = factory,
    )
}
