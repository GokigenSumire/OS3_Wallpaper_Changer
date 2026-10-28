package dev.abdus.apps.shimmer.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import dev.abdus.apps.shimmer.p2.PlaybackSnapshotStore
import dev.abdus.apps.shimmer.ui.effects.BackdropBlurTransformation
import dev.abdus.apps.shimmer.ui.effects.GlassEffectDefaults
import dev.abdus.apps.shimmer.ui.playback.displayedItemId
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P8.7 页面背景：当前壁纸图片的模糊版本，上覆主题色遮罩保证文字可读。
 *
 * 图片由 [rememberBackdropFrame] 取自当前目标已提交的播放快照。没有已提交图片、授权失效或解码失败时
 * 只显示主题背景色。只在应用内绘制，不使用 FLAG_SHOW_WALLPAPER 或窗口模糊，
 * 不改变壁纸引擎的可见性（P4 的可见时钟依赖它）。
 *
 * 主界面和预测返回各绘制同一张已解码小图；读取／解码之间不移除上一装饰帧，避免主题底色闪现。
 */
@Composable
fun WallpaperBackdrop(frame: BackdropFrame<ImageBitmap>?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (frame != null) {
            Image(
                bitmap = frame.content,
                // 纯装饰背景，读屏跳过
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(GlassEffectDefaults.scrimColor()))
    }
}

/** 单份装饰帧的读取／解码所有者；两个背景图层共用，不改变任何播放快照。 */
@Composable
fun rememberBackdropFrame(target: WallpaperTarget = WallpaperTarget.HOME): BackdropFrame<ImageBitmap>? {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableStateOf<BackdropFrame<ImageBitmap>?>(null) }
    val owner = remember(context, scope) {
        BackdropFrameOwner<ImageBitmap>(scope,
            clockMs = SystemClock::elapsedRealtime,
            readUri = { requestedTarget ->
                withContext(Dispatchers.IO) { PlaybackSnapshotStore(context, requestedTarget).load()?.displayedItemId() }
            },
            decode = { uri ->
                withContext(Dispatchers.IO) {
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .size(GlassEffectDefaults.BackdropDecodeSizePx)
                        .precision(Precision.INEXACT)
                        .allowHardware(false)
                        .transformations(BackdropBlurTransformation())
                        .diskCachePolicy(CachePolicy.DISABLED)
                        // 新图完整解码后才整体交帧，不能从透明底色 crossfade。
                        .crossfade(false)
                        .build()
                    val result = context.imageLoader.execute(request)
                    check(result is SuccessResult) { "装饰背景解码失败" }
                    result.drawable.toBitmap().asImageBitmap()
                }
            },
            onFrame = { frame = it },
        )
    }
    owner.selectTarget(target)
    LaunchedEffect(owner, target) { owner.refresh() }
    DisposableEffect(lifecycleOwner, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) owner.setVisible(true)
            if (event == Lifecycle.Event.ON_STOP) owner.setVisible(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            owner.close()
        }
    }
    return frame
}
