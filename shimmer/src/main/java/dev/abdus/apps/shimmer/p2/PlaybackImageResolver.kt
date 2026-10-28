package dev.abdus.apps.shimmer.p2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolves a provisional image; the caller still owns the final commit and presentation gate. */
class PlaybackImageResolver<T : Any>(private val coordinator: PlaybackCoordinator) {
    data class Loaded<T>(val request: PlaybackRequest, val image: T)

    /**
     * 显式指定只解码该目标一次。坏项／取消／异常均拒绝待提交状态，不替换成另一张图，
     * 成功也仍由调用方执行原generation／可见性／CAS／图片接管边界。
     */
    suspend fun resolveSelected(
        itemId: String,
        load: suspend (PlaybackRequest, PlaybackItem) -> T?,
    ): Loaded<T>? {
        currentCoroutineContext().ensureActive()
        val request = coordinator.requestSelected(itemId)
        val item = request.item ?: return null.also { coordinator.reject(request.version) }
        val image = try {
            load(request, item)
        } catch (failed: Throwable) {
            coordinator.reject(request.version)
            throw failed
        }
        if (image == null) {
            coordinator.reject(request.version)
            return null
        }
        return Loaded(request, image)
    }

    /**
     * 单根来源自动接管的可取消真实解码。URI失败最多一次，不发布未解码基准；调用方仍负责
     * generation／可见性／commit／最新快照CAS与Main图片所有权。不改变普通前后手势契约。
     */
    suspend fun resolveSourceRestore(
        load: suspend (PlaybackRequest, PlaybackItem) -> T?,
    ): Loaded<T>? {
        val unavailable = mutableSetOf<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val request = coordinator.requestSourceRestore(unavailable) ?: return null
            val item = request.item ?: return null.also { coordinator.reject(request.version) }
            val image = try {
                load(request, item)
            } catch (failed: Throwable) {
                coordinator.reject(request.version)
                throw failed
            }
            if (image == null) {
                if (!coordinator.reject(request.version)) return null
                unavailable += item.id
                continue
            }
            return Loaded(request, image)
        }
    }

    suspend fun resolve(direction: PlaybackDirection, load: suspend (PlaybackItem) -> T?): Loaded<T>? =
        resolveRequest(direction) { _, item -> load(item) }

    /** Same as [resolve]; the loader also sees the request, e.g. its P3 history placement. */
    suspend fun resolveRequest(
        direction: PlaybackDirection,
        load: suspend (PlaybackRequest, PlaybackItem) -> T?,
    ): Loaded<T>? {
        val unavailable = mutableSetOf<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val request = when (direction) {
                PlaybackDirection.NEXT -> coordinator.requestNext(unavailable)
                PlaybackDirection.PREVIOUS -> coordinator.requestPrevious(unavailable)
            }
            val item = request.item ?: return null.also { coordinator.reject(request.version) }
            val image = try {
                load(request, item)
            } catch (cancelled: CancellationException) {
                coordinator.reject(request.version)
                throw cancelled
            }
            if (image == null) {
                if (!coordinator.reject(request.version)) return null
                unavailable += item.id
                continue
            }
            return Loaded(request, image)
        }
    }
}
