package dev.abdus.apps.shimmer.ui.components

import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.coroutines.CancellationException

enum class BackdropPhase { PENDING, READY, EMPTY, ERROR }

/** 不包含 URI；同域、同序号也不能伪造同一次请求的对象身份。 */
class BackdropTicket internal constructor(val target: WallpaperTarget, val read: Long)

/** 仅限模糊装饰层的已解码内容，不是播放器当前项，也不能用作其他域的来源。 */
data class BackdropFrame<T>(val ticket: BackdropTicket, val content: T)

/**
 * 主线程所有的装饰帧交接：读取及解码期间保留上一帧，成功后一次替换。
 * 空目标或读取／解码失败必须释放旧帧；旧成功、旧失败和 A→B→A 均不能发布。
 * 只持有一个已解码装饰帧，不缓存 URI，不读取或写入任何播放状态。
 */
class BackdropFrameCoordinator<T> {
    var selectedTarget: WallpaperTarget? = null
        private set
    var phase = BackdropPhase.EMPTY
        private set
    var frame: BackdropFrame<T>? = null
        private set
    private var read = 0L
    private var active: BackdropTicket? = null

    fun selectTarget(target: WallpaperTarget) {
        if (selectedTarget == target) return
        selectedTarget = target
        active = null
        phase = BackdropPhase.PENDING
        // 旧帧只画在无语义的装饰背景；绝不返回成新目标的当前 URI。
    }

    fun begin(target: WallpaperTarget): BackdropTicket? {
        if (target != selectedTarget) return null
        return BackdropTicket(target, ++read).also {
            active = it
            phase = BackdropPhase.PENDING
        }
    }

    fun isCurrent(ticket: BackdropTicket): Boolean =
        selectedTarget == ticket.target && read == ticket.read && active === ticket

    fun ready(ticket: BackdropTicket, content: T): Boolean = finish(ticket, BackdropPhase.READY, content)
    fun empty(ticket: BackdropTicket): Boolean = finish(ticket, BackdropPhase.EMPTY, null)
    fun error(ticket: BackdropTicket): Boolean = finish(ticket, BackdropPhase.ERROR, null)

    fun cancelPending(): Boolean {
        if (phase != BackdropPhase.PENDING) return false
        active = null
        frame = null
        phase = BackdropPhase.ERROR
        return true
    }

    fun release() {
        active = null
        frame = null
        phase = BackdropPhase.EMPTY
    }

    private fun finish(ticket: BackdropTicket, nextPhase: BackdropPhase, content: T?): Boolean {
        if (!isCurrent(ticket)) return false
        frame = content?.let { BackdropFrame(ticket, it) }
        phase = nextPhase
        active = null
        return true
    }
}

/** 真正的读取→解码→交帧路径；注入点允许测试延迟、异常与取消，不需要访问私人图片。 */
internal suspend fun <T> loadBackdropFrame(
    coordinator: BackdropFrameCoordinator<T>,
    ticket: BackdropTicket,
    readUri: suspend () -> String?,
    decode: suspend (String) -> T,
): Boolean = try {
    val uri = readUri()
    if (!coordinator.isCurrent(ticket)) false
    else if (uri == null) coordinator.empty(ticket)
    else coordinator.ready(ticket, decode(uri))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    coordinator.error(ticket)
}
