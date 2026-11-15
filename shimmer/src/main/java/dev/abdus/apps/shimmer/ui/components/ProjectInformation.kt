package dev.abdus.apps.shimmer.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import android.widget.Toast

object ProjectInformation {
    const val MAIN = "https://github.com/GokigenSumire/OS3_Wallpaper_Changer"
    const val MODULE = "https://github.com/GokigenSumire/OS4_E1_Observer"
    val links = listOf("主应用源码" to MAIN, "兼容模块源码" to MODULE,
        "版本发布" to "$MAIN/releases", "问题反馈" to "$MAIN/issues")
}

@Composable
fun ProjectInformationLinks() {
    val handler = LocalUriHandler.current
    val context = LocalContext.current
    ProjectInformation.links.forEach { (label, url) ->
        TextButton(onClick = {
            try { handler.openUri(url) } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
            } catch (_: IllegalArgumentException) {
                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
        }) { Text(label) }
    }
}
