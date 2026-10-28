package dev.abdus.apps.shimmer.ui.components

import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 主线程的装饰背景工作所有者。单通道避免取消不及时的 Provider 叠加；独立期限不等待 IO 返回。
 * 期限从首次保留前帧起单调计时，连续切域／刷新不能续期；隐藏时直接撤销未完成帧并释放引用。
 * Coil 自己可能缓存位图，因此只释放引用，不手动 recycle 共用的 Bitmap。
 */
internal class BackdropFrameOwner<T>(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long,
    private val readUri: suspend (WallpaperTarget) -> String?,
    private val decode: suspend (String) -> T,
    private val onFrame: (BackdropFrame<T>?) -> Unit,
    private val timeoutMs: Long = 5_000L,
    private val waitForDeadline: suspend (Long) -> Unit = { delay(it) },
) {
    val coordinator = BackdropFrameCoordinator<T>()
    private val ioLane = Mutex()
    private var visible = false
    private var worker: Job? = null
    private var deadline: Job? = null
    private var retainedUntilMs: Long? = null

    init { require(timeoutMs > 0) }

    /** 组合新目标时先撤旧票，不在组合过程中启动读取或解码。 */
    fun selectTarget(target: WallpaperTarget) {
        if (coordinator.selectedTarget == target) return
        coordinator.selectTarget(target)
        cancelJobs()
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (value) refresh()
        else {
            if (coordinator.cancelPending()) onFrame(coordinator.frame)
            retainedUntilMs = null
            cancelJobs()
        }
    }

    fun refresh() {
        if (!visible) return
        val target = coordinator.selectedTarget ?: return
        // 在取消旧 worker 前创建新票；旧 worker 的取消收尾不能清掉当前装饰帧。
        val ticket = coordinator.begin(target) ?: return
        cancelJobs()
        val until = retainedUntilMs ?: (clockMs() + timeoutMs).also { retainedUntilMs = it }
        val remaining = until - clockMs()
        if (remaining <= 0L) {
            if (coordinator.error(ticket)) onFrame(coordinator.frame)
            retainedUntilMs = null
            return
        }
        // 不作为 IO worker 的子任务：阻塞 Provider 的取消即使未完成，也可在主线程及时清帧。
        val thisDeadline = scope.launch {
            waitForDeadline(remaining)
            if (coordinator.error(ticket)) {
                retainedUntilMs = null
                onFrame(coordinator.frame)
                worker?.cancel()
            }
        }
        deadline = thisDeadline
        worker = scope.launch {
            try {
                val published = ioLane.withLock {
                    if (!coordinator.isCurrent(ticket)) false
                    else loadBackdropFrame(coordinator, ticket, { readUri(target) }, decode)
                }
                if (published) {
                    retainedUntilMs = null
                    thisDeadline.cancel()
                    onFrame(coordinator.frame)
                }
            } catch (cancelled: CancellationException) {
                if (coordinator.error(ticket)) {
                    retainedUntilMs = null
                    thisDeadline.cancel()
                    onFrame(coordinator.frame)
                }
                throw cancelled
            }
        }
    }

    fun close() {
        setVisible(false)
        coordinator.release()
        onFrame(null)
    }

    private fun cancelJobs() {
        worker?.cancel()
        deadline?.cancel()
        worker = null
        deadline = null
    }
}
