package dev.abdus.apps.shimmer.p2

import dev.abdus.apps.shimmer.p3.ImageTransform
import java.math.BigInteger
import java.util.Locale
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** Stable identity and display metadata used by the P2 playback state machine. */
@Serializable
data class PlaybackItem(
    val id: String,
    val displayName: String,
    val relativePath: String = "",
    val sourceId: String = "",
    val sourceOrder: Long = 0,
    val sourceKind: String = "",
    val sourceRootUri: String = "",
)

@Serializable
enum class PlaybackOrder {
    NATURAL_ASC,
    NATURAL_DESC,
    RANDOM,
    MANUAL,
}

enum class PlaybackDirection {
    NEXT,
    PREVIOUS,
}

data class PlaybackRequest(
    val version: Long,
    val direction: PlaybackDirection,
    val item: PlaybackItem?,
    /** True only when this step appends a new history entry; P3 inheritance applies only then. */
    val freshAdvance: Boolean = false,
    /** Placement recorded on the history entry this step lands on, if any. */
    val entryTransform: ImageTransform? = null,
    /** 显式指定图片，不是自动NEXT；仍新增真实历史，但不得继承离开图的变换。 */
    val selected: Boolean = false,
)

/**
 * Persistable playback state. The item catalog is intentionally kept outside this snapshot so
 * rescans can update metadata without changing stable history identities.
 */
@Serializable
data class PlaybackSnapshot(
    val order: PlaybackOrder = PlaybackOrder.NATURAL_ASC,
    val queueIds: List<String> = emptyList(),
    val queueIndex: Int = -1,
    val historyIds: List<String> = emptyList(),
    val historyCursor: Int = -1,
    val currentId: String? = null,
    val randomRound: Int = 0,
    val previousRoundLastId: String? = null,
    /**
     * Catalog identity the random round was generated from; 0 before any round exists.
     * It lets a persisted round be rebuilt from the seed instead of stored as thousands of IDs.
     */
    val queueFingerprint: Int = 0,
    /**
     * P3 inherited placements, index-aligned with [historyIds]. A missing or null slot means the
     * entry shows its image's saved config or the global default. Trailing nulls are not stored.
     */
    val historyTransforms: List<ImageTransform?> = emptyList(),
    /** 已确认的逐槽根来源身份；旧JSON未知槽为""，不凭URI失活猜来源。 */
    val historySourceIds: List<String> = emptyList(),
    /**
     * P4 source resume: the last committed image of each P1 source, by source ID. Updated only
     * when an image is committed, never while sanitising, so a stored snapshot restores equal.
     */
    val sourcePositions: Map<String, String> = emptyMap(),
    /** 同一URI可跨轮重复；真实历史槽位用于恢复离开来源时的调整，旧JSON默认空。 */
    val sourceHistoryCursors: Map<String, Int> = emptyMap(),
    /** Sources that left the catalog after playing; the next visit resumes at their position. */
    val resumeSourceIds: List<String> = emptyList(),
    /**
     * 每次显式清历史递增；即使内容已只有当前基准，也形成新的提交边界，使旧解码的 CAS 失效。
     * 偏好快照格式前向添加，旧 JSON 未含此字段时默认 0；不涉及 Room 数据库迁移。
     */
    val historyResetSerial: Long = 0,
    /** 显式改模式／有效手动重排后，下一次新推进不重播旧顺序的未来历史；旧 JSON 默认兼容。 */
    val forwardHistoryInvalidated: Boolean = false,
)

/** Drops a deleted source's resume point; re-adding the folder starts it afresh. */
fun PlaybackSnapshot.withoutSource(sourceId: String): PlaybackSnapshot =
    if (sourceId !in sourcePositions && sourceId !in sourceHistoryCursors && sourceId !in resumeSourceIds) this
    else copy(sourcePositions = sourcePositions - sourceId, sourceHistoryCursors = sourceHistoryCursors - sourceId,
        resumeSourceIds = resumeSourceIds - sourceId)

/** Inherited placement recorded on the current history entry, if it belongs to [itemId]. */
fun PlaybackSnapshot.currentEntryTransform(itemId: String?): ImageTransform? =
    historyTransforms.getOrNull(historyCursor)?.takeIf { itemId != null && historyIds.getOrNull(historyCursor) == itemId }

/** Drops every inherited placement recorded for [itemId]; used when the user saves that image. */
fun PlaybackSnapshot.withoutTransformsFor(itemId: String): PlaybackSnapshot {
    val recorded = historyTransforms.indices.any { historyTransforms[it] != null && historyIds.getOrNull(it) == itemId }
    if (!recorded) return this
    val cleared = historyTransforms.mapIndexed { index, transform ->
        transform.takeUnless { historyIds.getOrNull(index) == itemId }
    }
    return copy(historyTransforms = cleared.dropLastWhile { it == null })
}

/**
 * Persisted form of a snapshot.
 *
 * Only a manual queue carries information that cannot be recomputed: it also holds the saved
 * positions of temporarily disabled sources. Natural orders are recomputed from the catalog and a
 * random round from ([PlaybackSnapshot.randomRound], [PlaybackSnapshot.queueFingerprint]), so
 * storing their IDs meant encoding, writing and re-parsing megabytes of content URIs on every
 * change — the dominant cost on a library of tens of thousands of images.
 */
fun PlaybackSnapshot.forPersistence(): PlaybackSnapshot =
    if (order == PlaybackOrder.MANUAL) this else copy(queueIds = emptyList())

/** Request-local preparation work, not a duration, SQL count or GPU measurement. */
data class CoordinatorPreparationWork(
    val catalogItemsVisited: Long = 0L,
    val fullRestoreCount: Int = 0,
    /** Queue derivations and position-table builds performed during preparation only. */
    val queueRebuilds: Int = 0,
)

/**
 * Application-level queue/history coordinator for P2.
 *
 * Requests operate on a provisional copy. Only a matching commit can publish it, so a failed
 * decode or a stale asynchronous result cannot advance history or replace a newer request.
 */
class PlaybackCoordinator(
    private val randomSeed: Long = 0L,
) {
    private val itemsById = linkedMapOf<String, PlaybackItem>()
    private var sortedAscending: List<String>? = null
    private var sortedDescending: List<String>? = null
    private var catalogFingerprintCache: Int? = null
    private var catalogSourcesCache: Set<String>? = null
    private var randomQueueCache: RandomQueueCache? = null
    private var manualQueueCache: QueueDerivationCache? = null
    private var filteredRandomQueueCache: QueueDerivationCache? = null
    private var queuePositionCache: QueuePositionCache? = null
    /** Only one completely validated immutable catalog may take the identity fast path. */
    private var acceptedCatalog: CertifiedPlaybackItems? = null
    private var preparationCounter: PreparationCounter? = null
    private var committed = PlaybackSnapshot()
    private var working = committed
    private var nextVersion = 0L
    private var pendingVersion: Long? = null
    /** Whether the pending request appended a history entry; only such a commit may annotate it. */
    private var pendingFresh = false
    private var pendingSelected = false
    private var lastStepFresh = false

    fun setItems(items: List<PlaybackItem>): CoordinatorPreparationWork = measurePreparation {
        val changed = applyCatalog(items)
        cancelPending()
        // Preview state is owned and immutable; ordinary caller lists keep the old normalization.
        if (changed || items !is CertifiedPlaybackItems) {
            preparationCounter?.fullRestoreCount = 1
            committed = sanitize(committed)
            working = committed
        }
    }

    /**
     * 正式 Engine 每步已读取最新存储快照；先归一化旧 committed 会重复处理同一目录。
     * 组合操作只归一化权威快照一次。预览及独立 setItems 调用保留各自原有契约。
     * [onCatalogApplied] 是同步计时检查点；内部调用方只记录单调时钟，不抛异常或重入状态机。
     */
    fun setItemsAndRestore(
        items: List<PlaybackItem>, snapshot: PlaybackSnapshot, onCatalogApplied: (() -> Unit)? = null,
    ): CoordinatorPreparationWork = measurePreparation {
        val changed = applyCatalog(items)
        onCatalogApplied?.invoke()
        cancelPending()
        // Compare every authoritative persisted field, not an observer/version approximation.
        // Controlled manual IDs retain identity across the Store; arbitrary inputs compare fully.
        if (changed || snapshot != committed.forPersistence()) {
            preparationCounter?.fullRestoreCount = 1
            committed = sanitize(snapshot)
            working = committed
        }
    }

    private fun applyCatalog(items: List<PlaybackItem>): Boolean {
        if (items is CertifiedPlaybackItems && items === acceptedCatalog) return false
        // 输入每次来自权威读取。按完整元数据与次序逐项比较，绝不以 List 引用／数量／hash 放行。
        // 成功时已有目录全是经过验证的唯一非空身份，不需重新分配／哈希整张去重表。
        if (sameCatalog(items)) {
            acceptedCatalog = items as? CertifiedPlaybackItems
            return false
        }
        val deduplicated = linkedMapOf<String, PlaybackItem>()
        items.forEach { item ->
            preparationCounter?.visit()
            require(item.id.isNotBlank()) { "Playback item identity is required" }
            deduplicated.putIfAbsent(item.id, item)
        }
        // Every page action and wallpaper refresh re-supplies the catalog. Keep the sorted
        // orders when it is unchanged; re-sorting thousands of paths per action stalled P2.
        if (!sameCatalog(deduplicated)) {
            itemsById.clear()
            itemsById.putAll(deduplicated)
            sortedAscending = null
            sortedDescending = null
            catalogFingerprintCache = null
            catalogSourcesCache = null
            randomQueueCache = null
            manualQueueCache = null
            filteredRandomQueueCache = null
            queuePositionCache = null
            acceptedCatalog = items as? CertifiedPlaybackItems
            return true
        }
        acceptedCatalog = items as? CertifiedPlaybackItems
        return false
    }

    private inline fun measurePreparation(block: () -> Unit): CoordinatorPreparationWork {
        val previous = preparationCounter
        val measured = PreparationCounter()
        preparationCounter = measured
        return try {
            block()
            CoordinatorPreparationWork(measured.catalogItemsVisited, measured.fullRestoreCount, measured.queueRebuilds)
        } finally {
            preparationCounter = previous
        }
    }

    private class PreparationCounter {
        var catalogItemsVisited = 0L
        var fullRestoreCount = 0
        var queueRebuilds = 0
        fun visit() { catalogItemsVisited++ }
    }

    fun setOrder(order: PlaybackOrder) {
        cancelPending()
        val old = committed
        val queue = when (order) {
            PlaybackOrder.NATURAL_ASC -> sortedIds(descending = false)
            PlaybackOrder.NATURAL_DESC -> sortedIds(descending = true)
            PlaybackOrder.MANUAL -> savedManualIds(old.queueIds)
            // Materialise the round now so the page can show the upcoming random order instead
            // of waiting for the wallpaper to play the first image.
            PlaybackOrder.RANDOM -> randomQueue(old.randomRound, old.previousRoundLastId)
        }
        committed = old.copy(
            order = order,
            queueIds = queue,
            queueIndex = queuePosition(queue, old.currentId),
            queueFingerprint = if (order == PlaybackOrder.RANDOM) catalogFingerprint() else old.queueFingerprint,
            forwardHistoryInvalidated = old.forwardHistoryInvalidated || order != old.order,
        )
        committed = committed.immutableState()
        working = committed
    }

    /** Reorders the complete current catalog. Partial or duplicate orders are rejected. */
    fun reorder(ids: List<String>) {
        require(ids.size == itemsById.size && ids.toSet() == itemsById.keys) {
            "Manual order must contain every playback item exactly once"
        }
        cancelPending()
        // Reorder only visible slots. Disabled sources retain their saved positions so that a
        // later re-enable is not mistaken for a newly imported image.
        val activeOrder = ids.iterator()
        val queue = savedManualIds(committed.queueIds).map { id ->
            if (id in itemsById) activeOrder.next() else id
        }.frozenIds()
        committed = committed.copy(
            order = PlaybackOrder.MANUAL, queueIds = queue, queueIndex = queuePosition(queue, committed.currentId),
            forwardHistoryInvalidated = committed.forwardHistoryInvalidated ||
                committed.order != PlaybackOrder.MANUAL || queue != committed.queueIds,
        )
        working = committed
    }

    fun orderedItems(): List<PlaybackItem> {
        val ids = when (committed.order) {
            PlaybackOrder.NATURAL_ASC -> sortedIds(descending = false)
            PlaybackOrder.NATURAL_DESC -> sortedIds(descending = true)
            PlaybackOrder.MANUAL -> savedManualIds(committed.queueIds)
            PlaybackOrder.RANDOM -> effectiveRandomIds(committed.queueIds)
        }
        return ids.mapNotNull(itemsById::get)
    }

    fun current(): PlaybackItem? = committed.currentId?.let(itemsById::get)

    fun catalogItems(): List<PlaybackItem> = itemsById.values.toList()

    fun snapshot(): PlaybackSnapshot = committed

    fun restore(snapshot: PlaybackSnapshot) {
        cancelPending()
        committed = sanitize(snapshot)
        working = committed
    }

    /** Restores persisted ordering while keeping the image currently rendered on screen. */
    fun restoreKeepingCurrent(snapshot: PlaybackSnapshot, currentId: String?) {
        cancelPending()
        val restored = sanitize(snapshot)
        val retainedId = currentId?.takeIf(itemsById::containsKey)
        committed = if (retainedId == null) {
            restored
        } else {
            val history = if (retainedId in restored.historyIds) {
                restored.historyIds
            } else {
                restored.historyIds + retainedId
            }
            restored.copy(
                queueIndex = queuePosition(queueFor(restored), retainedId),
                historyIds = history,
                historyCursor = if (history.getOrNull(restored.historyCursor) == retainedId) {
                    restored.historyCursor
                } else {
                    history.indices.filter { history[it] == retainedId }
                        .minByOrNull { kotlin.math.abs(it - restored.historyCursor) } ?: history.lastIndex
                },
                currentId = retainedId,
                historySourceIds = history.mapIndexed { index, id ->
                    itemsById[id]?.sourceId ?: restored.historySourceIds.getOrElse(index) { "" }
                }.dropLastWhile { it.isEmpty() },
            )
        }.immutableState()
        working = committed
    }

    /** Seeds a restored database current item without inventing a second history entry. */
    fun restoreCurrent(itemId: String?) {
        if (itemId == null || itemId !in itemsById) return
        cancelPending()
        val queue = queueFor(committed)
        committed = committed.copy(
            queueIds = queue,
            queueIndex = queuePosition(queue, itemId),
            historyIds = listOf(itemId),
            historyCursor = 0,
            currentId = itemId,
            historyTransforms = emptyList(),
            historySourceIds = listOf(itemsById.getValue(itemId).sourceId).dropLastWhile { it.isEmpty() },
            sourceHistoryCursors = emptyMap(),
        ).withSourcePosition()
        working = committed
    }

    fun requestNext(unavailableIds: Set<String> = emptySet()): PlaybackRequest = request(PlaybackDirection.NEXT, unavailableIds)

    fun requestPrevious(unavailableIds: Set<String> = emptySet()): PlaybackRequest = request(PlaybackDirection.PREVIOUS, unavailableIds)

    /**
     * 任意模式指定一张本次真实目录中的图片。每次从已提交状态起步，较新指定覆盖旧待提交
     * 请求；无效身份／已是当前图返回无候选，不清历史、不改模式、不移动手动位置。
     * 随机模式保本轮原次序，游标定位到指定项；显式跳过不属于自然完整随机轮验收。
     */
    fun requestSelected(itemId: String): PlaybackRequest {
        cancelPending()
        nextVersion += 1
        val item = itemsById[itemId]?.takeUnless { itemId == committed.currentId }
        if (item != null) {
            // 旧快照可能只有真实current而未存历史。待提交副本保留这个离开基准；
            // 失败不得先发布补种，也不凭未知URI推断根来源或继承调整。
            val anchored = if (committed.historyIds.isEmpty() && committed.currentId != null) {
                committed.copy(
                    historyIds = listOf(committed.currentId!!), historyCursor = 0,
                    historyTransforms = emptyList(),
                    historySourceIds = listOf(itemsById[committed.currentId]?.sourceId.orEmpty())
                        .dropLastWhile { it.isEmpty() },
                )
            } else committed
            val prepared = prepareQueue(anchored)
            working = appendFreshHistory(prepared.copy(queueIndex = queuePosition(prepared.queueIds, itemId)), itemId)
        }
        pendingVersion = nextVersion.takeIf { item != null }
        pendingFresh = item != null
        pendingSelected = item != null
        return PlaybackRequest(
            version = nextVersion, direction = PlaybackDirection.NEXT, item = item,
            freshAdvance = item != null, entryTransform = null, selected = true,
        )
    }

    /**
     * 根来源互相替换时才接管：多来源的全局队列、空目录及仍有效的当前图片不重定向。
     * 此判断不修改已提交状态；恢复点必须真实解码后经原提交／CAS／呈现边界发布。
     */
    fun sourceRestoreNeeded(): Boolean {
        val current = committed.currentId ?: return false
        val source = singleCatalogSource() ?: return false
        // 当前仍属于同根但仅该单项／子目录被停用时，沿原有历史跳过契约，不抢先回到首项。
        return current !in itemsById && committed.sourcePositions[source] != current
    }

    /**
     * 选择唯一有效根来源自己的真实恢复身份，而不是套用另一来源的相对 queueIndex。
     * 只准备待提交恢复请求，失败／取消仍保留旧当前图、历史、来源点和完整手动位置。
     */
    fun requestSourceRestore(unavailableIds: Set<String> = emptySet()): PlaybackRequest? {
        if (!sourceRestoreNeeded()) return null
        val source = singleCatalogSource() ?: return null
        val state = prepareQueue(committed)
        val queue = state.queueIds
        val saved = state.sourcePositions[source]
        val preferred = if (saved != null) {
            saved.takeIf { itemsById[it]?.sourceId == source }
        } else {
            state.historyIds.lastOrNull { itemsById[it]?.sourceId == source }
        }
        val target = preferred?.takeUnless { it in unavailableIds }
            ?: queue.firstOrNull { it in itemsById && it !in unavailableIds }
            ?: return null
        val existingIndex = state.sourceHistoryCursors[source]
            ?.takeIf { state.historyIds.getOrNull(it) == target }
            ?: state.historyIds.indexOfLast { it == target }
        val history = if (existingIndex >= 0) state.historyIds else state.historyIds + target
        val owners = if (existingIndex >= 0) state.historySourceIds else
            state.historyIds.indices.map { state.historySourceIds.getOrElse(it) { "" } } + source
        working = state.copy(
            currentId = target,
            queueIndex = queuePosition(queue, target),
            historyIds = history,
            historyCursor = if (existingIndex >= 0) existingIndex else history.lastIndex,
            // 新首项没有继承上一来源变换；已有真实历史项沿其原对齐位置取调整。
            historyTransforms = state.historyTransforms,
            historySourceIds = owners,
        )
        nextVersion += 1
        pendingVersion = nextVersion
        pendingFresh = false
        pendingSelected = false
        lastStepFresh = false
        return PlaybackRequest(
            version = nextVersion, direction = PlaybackDirection.NEXT, item = itemsById[target],
            freshAdvance = false, entryTransform = working.currentEntryTransform(target),
        )
    }

    private fun singleCatalogSource(): String? = catalogSources().singleOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Publishes the pending request. [inheritedTransform] is recorded on the new history entry and
     * is accepted only for a fresh advance, so revisiting history can never overwrite a placement.
     */
    fun commit(version: Long, inheritedTransform: ImageTransform? = null): Boolean {
        if (pendingVersion != version) return false
        val annotated = if (inheritedTransform != null && pendingFresh && !pendingSelected) {
            working.withTransformAtCursor(inheritedTransform.sanitized())
        } else {
            working
        }
        committed = sanitize(annotated).withSourcePosition().let {
            if (pendingFresh) it.copy(forwardHistoryInvalidated = false) else it
        }
        working = committed
        pendingVersion = null
        pendingFresh = false
        pendingSelected = false
        return true
    }

    /**
     * Records the committed image as its source's resume point and marks every source that has a
     * point but no image in the catalog. Only commits call this; sanitising must stay a pure
     * normalisation or a stored snapshot would no longer restore equal.
     */
    private fun PlaybackSnapshot.withSourcePosition(): PlaybackSnapshot {
        val source = currentId?.let(itemsById::get)?.sourceId?.takeIf { it.isNotEmpty() }
        val positions = if (source == null) sourcePositions else sourcePositions + (source to currentId!!)
        val cursors = if (source == null || historyIds.getOrNull(historyCursor) != currentId) {
            sourceHistoryCursors
        } else sourceHistoryCursors + (source to historyCursor)
        // 纯归一化不得给旧JSON添加来源标签，避免同值通知被误作外部更新并取消当前请求。
        // 真解码提交时才确认仍有效历史的来源；同一URI的共享来源沿本次权威目录归属。
        val owners = historyIds.mapIndexed { index, id ->
            itemsById[id]?.sourceId ?: historySourceIds.getOrElse(index) { "" }
        }.dropLastWhile { it.isEmpty() }
        val present = catalogSources()
        val resume = (resumeSourceIds + positions.keys.filterNot(present::contains))
            .filter { it != source && it in positions }
            .distinct()
        if (positions == sourcePositions && cursors == sourceHistoryCursors && owners == historySourceIds && resume == resumeSourceIds) return immutableState()
        return copy(sourcePositions = positions, sourceHistoryCursors = cursors, historySourceIds = owners, resumeSourceIds = resume).immutableState()
    }

    private fun catalogSources(): Set<String> = catalogSourcesCache
        ?: itemsById.values.mapTo(hashSetOf()) { it.sourceId }.also { catalogSourcesCache = it }

    fun reject(version: Long): Boolean {
        if (pendingVersion != version) return false
        working = committed
        pendingVersion = null
        pendingFresh = false
        pendingSelected = false
        return true
    }

    private fun request(direction: PlaybackDirection, unavailableIds: Set<String>): PlaybackRequest {
        nextVersion += 1
        lastStepFresh = false
        working = when (direction) {
            PlaybackDirection.NEXT -> stepNext(working, unavailableIds)
            PlaybackDirection.PREVIOUS -> stepPrevious(working, unavailableIds)
        }
        pendingVersion = nextVersion
        pendingSelected = false
        val item = working.currentId?.takeUnless { it in unavailableIds }?.let(itemsById::get)
        pendingFresh = lastStepFresh && item != null
        return PlaybackRequest(
            version = nextVersion,
            direction = direction,
            item = item,
            freshAdvance = pendingFresh,
            entryTransform = if (pendingFresh) null else working.currentEntryTransform(item?.id),
        )
    }

    private fun PlaybackSnapshot.withTransformAtCursor(transform: ImageTransform): PlaybackSnapshot {
        if (historyCursor !in historyIds.indices) return this
        val slots = historyTransforms.take(historyIds.size).toMutableList()
        while (slots.size <= historyCursor) slots += null
        slots[historyCursor] = transform
        return copy(historyTransforms = slots)
    }

    private fun stepNext(state: PlaybackSnapshot, unavailableIds: Set<String>): PlaybackSnapshot {
        if (itemsById.keys.all { it in unavailableIds }) return state
        if (!state.forwardHistoryInvalidated && state.historyCursor >= 0 && state.historyCursor < state.historyIds.lastIndex) {
            val forwardIndex = ((state.historyCursor + 1)..state.historyIds.lastIndex)
                .firstOrNull { state.historyIds[it] in itemsById && state.historyIds[it] !in unavailableIds }
            if (forwardIndex != null) {
                val forwardId = state.historyIds[forwardIndex]
                return state.copy(
                    historyCursor = forwardIndex,
                    currentId = forwardId,
                    queueIndex = queuePosition(queueFor(state), forwardId),
                )
            }
        }

        var prepared = prepareQueue(state)
        if (prepared.queueIds.isEmpty()) return prepared.copy(currentId = null, queueIndex = -1)
        // Advance only provisional queue positions over failed images. They never enter history.
        // A random search can cross the old tail and an entire new round before reaching a
        // readable item. Counting only one round can stop before a valid earlier image.
        val searchBudget = if (prepared.order == PlaybackOrder.RANDOM) {
            prepared.queueIds.lastIndex - prepared.queueIndex + itemsById.size
        } else {
            prepared.queueIds.size
        }
        repeat(searchBudget) {
            val nextIndex = prepared.queueIndex + 1
            val (queue, index, round, previousLast) = if (nextIndex < prepared.queueIds.size) {
                Quad(prepared.queueIds, nextIndex, prepared.randomRound, prepared.previousRoundLastId)
            } else if (prepared.order == PlaybackOrder.RANDOM) {
                val previousRoundLast = prepared.queueIds.lastOrNull()
                val nextRound = makeRandomQueue(prepared.randomRound + 1, previousRoundLast)
                Quad(nextRound, 0, prepared.randomRound + 1, previousRoundLast)
            } else {
                Quad(prepared.queueIds, 0, prepared.randomRound, prepared.previousRoundLastId)
            }
            val candidate = queue[index]
            prepared = prepared.copy(
                queueIds = queue,
                queueIndex = index,
                randomRound = round,
                previousRoundLastId = previousLast,
            )
            if (candidate !in itemsById || candidate in unavailableIds) return@repeat
            // Even when the readable candidate is the current image, retain a crossed round.
            // Otherwise the next gesture would repeat the same old tail forever.
            if (candidate == prepared.currentId && prepared.historyIds.isNotEmpty()) return prepared
            resumeIndex(prepared, index, unavailableIds)
                ?.takeUnless { prepared.queueIds[it] == prepared.currentId }
                ?.let { prepared = prepared.copy(queueIndex = it) }
            val landing = prepared.queueIds[prepared.queueIndex]
            lastStepFresh = true
            return appendFreshHistory(prepared, landing)
        }
        return state
    }

    /** NEXT与显式指定共用原分支合同，避免指定图丢失另一暂时停用根的已确认历史。 */
    private fun appendFreshHistory(prepared: PlaybackSnapshot, landing: String): PlaybackSnapshot {
        val kept = prepared.historyCursor + 1
        // 只有逐槽真实来源记录可证明另一根仍有保存点；同根移除、未知旧JSON、
        // 删除来源及混合来源均沿原分支裁剪，不凭失活URI猜测它属于暂时停用B。
        val singleSource = singleCatalogSource()
        val retainedIndices = if (singleSource != null) {
            prepared.historyIds.indices.filter { index ->
                val owner = prepared.historySourceIds.getOrElse(index) { "" }
                index < kept || (prepared.historyIds[index] !in itemsById && owner.isNotEmpty() &&
                    owner != singleSource && owner in prepared.sourcePositions)
            }
        } else {
            (0 until kept).toList()
        }
        val history = retainedIndices.map { prepared.historyIds[it] } + landing
        val transforms = retainedIndices.map { prepared.historyTransforms.getOrNull(it) }
        val owners = retainedIndices.map { prepared.historySourceIds.getOrElse(it) { "" } } +
            itemsById.getValue(landing).sourceId
        val remappedIndices = retainedIndices.withIndex().associate { it.value to it.index }
        val cursors = prepared.sourceHistoryCursors.mapNotNull { (source, oldIndex) ->
            remappedIndices[oldIndex]?.let { source to it }
        }.toMap()
        return prepared.copy(
            historyIds = history, historyCursor = history.lastIndex, currentId = landing,
            historyTransforms = transforms.dropLastWhile { it == null },
            sourceHistoryCursors = cursors, historySourceIds = owners.dropLastWhile { it.isEmpty() },
        )
    }

    private fun stepPrevious(state: PlaybackSnapshot, unavailableIds: Set<String>): PlaybackSnapshot {
        if (state.historyCursor <= 0) return state
        val previousIndex = (state.historyCursor - 1 downTo 0)
            .firstOrNull { state.historyIds[it] in itemsById && state.historyIds[it] !in unavailableIds }
            ?: return state
        val previousId = state.historyIds[previousIndex]
        return state.copy(
            historyCursor = previousIndex,
            currentId = previousId,
            queueIndex = queuePosition(queueFor(state), previousId),
        )
    }

    private fun prepareQueue(state: PlaybackSnapshot): PlaybackSnapshot {
        if (state.order != PlaybackOrder.RANDOM) {
            val queue = queueFor(state)
            val index = queuePosition(queue, state.currentId).takeIf { it >= 0 } ?: anchorIndex(state, queue)
            return state.copy(queueIds = queue, queueIndex = index)
        }
        if (state.queueIds.isNotEmpty()) return state.copy(queueIds = effectiveRandomIds(state.queueIds))
        val queue = randomQueue(state.randomRound, state.previousRoundLastId)
        return state.copy(
            queueIds = queue,
            queueIndex = queuePosition(queue, state.currentId),
            queueFingerprint = catalogFingerprint(),
        )
    }

    /**
     * P4 source resume. When the step at [index] enters a source that left the catalog after
     * playing, returns the queue index of that source's last committed image, or null to play
     * [index] as usual.
     *
     * Only a forward jump inside one contiguous run of the source is taken, so an interleaved
     * manual order can never jump back and forth. A resume point on the run's last image (in a
     * natural order, the source's last image) starts the run from its first image instead of
     * skipping it. Random order has no position: a re-enabled image joins the running round's tail.
     */
    private fun resumeIndex(state: PlaybackSnapshot, index: Int, unavailableIds: Set<String>): Int? {
        if (state.order == PlaybackOrder.RANDOM || state.resumeSourceIds.isEmpty()) return null
        val queue = state.queueIds
        val source = itemsById[queue[index]]?.sourceId?.takeIf { it in state.resumeSourceIds } ?: return null
        val target = state.sourcePositions[source] ?: return null
        if (target == queue[index] || target !in itemsById || target in unavailableIds) return null
        var targetIndex = -1
        for (i in index + 1 until queue.size) {
            val id = queue[i]
            if (id == target) {
                targetIndex = i
                break
            }
            if (itemsById[id]?.sourceId != source) return null
        }
        if (targetIndex < 0) return null
        // Resuming at the run's last image would only repeat it and leave the source.
        val runContinues = queue.getOrNull(targetIndex + 1)?.let(itemsById::get)?.sourceId == source
        return targetIndex.takeIf { runContinues }
    }

    /**
     * Queue position a step continues from when the current image left the catalog: the newest
     * history entry at or before the cursor that is still queued. Without one the queue starts
     * over. Disabling the playing source therefore continues after it instead of restarting.
     */
    private fun anchorIndex(state: PlaybackSnapshot, queue: List<String>): Int {
        if (state.historyIds.isEmpty() || queue.isEmpty()) return -1
        val start = state.historyCursor.coerceIn(0, state.historyIds.lastIndex)
        for (i in start downTo 0) {
            queuePosition(queue, state.historyIds[i]).takeIf { it >= 0 }?.let { return it }
        }
        return -1
    }

    private fun queueFor(state: PlaybackSnapshot): List<String> = when (state.order) {
        PlaybackOrder.NATURAL_ASC -> sortedIds(descending = false)
        PlaybackOrder.NATURAL_DESC -> sortedIds(descending = true)
        PlaybackOrder.MANUAL -> savedManualIds(state.queueIds)
        PlaybackOrder.RANDOM -> effectiveRandomIds(state.queueIds)
    }

    private fun sanitize(state: PlaybackSnapshot): PlaybackSnapshot {
        // Keep history positions even when a source is temporarily disabled. Navigation skips
        // inactive items; filtering here would move the cursor before the user's next gesture.
        val (history, trimmedCursor, trimStart) = trimHistory(state)
        // Placements stay index-aligned with history: shift by the trimmed prefix, cut the tail.
        val transforms = state.historyTransforms
            .drop(trimStart)
            .take(history.size)
            .map { it?.sanitized() }
            .dropLastWhile { it == null }
        val sourceCursors = state.sourceHistoryCursors.mapNotNull { (source, oldIndex) ->
            val shifted = oldIndex.toLong() - trimStart.toLong()
            if (shifted >= 0 && shifted < history.size &&
                history[shifted.toInt()] == state.sourcePositions[source]) source to shifted.toInt() else null
        }.toMap()
        val owners = history.mapIndexed { index, id ->
            val savedOwner = state.historySourceIds.getOrElse(index + trimStart) { "" }
            if (savedOwner.isEmpty()) "" else itemsById[id]?.sourceId ?: savedOwner
        }.dropLastWhile { it.isEmpty() }
        val cursor = if (history.isEmpty()) -1 else trimmedCursor.coerceIn(0, history.lastIndex)
        val effectiveCurrent = history.getOrNull(cursor) ?: state.currentId
        val round = state.randomRound
        val previousLast = state.previousRoundLastId
        var fingerprint = state.queueFingerprint
        val queue = when (state.order) {
            PlaybackOrder.RANDOM -> when {
                // A round already in memory keeps the order it was drawn in. Re-deriving it from
                // the seed on every commit would discard the positions a catalog change moved.
                state.queueIds.isNotEmpty() -> when (fingerprint) {
                    catalogFingerprint() -> {
                        // 32位fingerprint不证明两个目录相同；碰撞时仍须检查真实身份集合。
                        val active = effectiveRandomIds(state.queueIds)
                        if (filteredRandomQueueCache?.coversCatalog == true) active
                        else extendRound(active, round, previousLast)
                    }
                    else -> {
                        fingerprint = catalogFingerprint()
                        extendRound(state.queueIds, round, previousLast)
                    }
                }
                // No round has ever been generated; the wallpaper or the page creates one later.
                fingerprint == 0 -> emptyList()
                // Restored without the ID list: the round is rebuilt from its seed. This is why the
                // persisted snapshot can leave a queue of tens of thousands of URIs unwritten.
                else -> {
                    fingerprint = catalogFingerprint()
                    effectiveRandomIds(randomQueue(round, previousLast))
                }
            }
            else -> queueFor(state)
        }
        return state.copy(
            queueIds = queue,
            queueIndex = queuePosition(queue, effectiveCurrent),
            historyIds = history,
            historyCursor = if (effectiveCurrent == null) -1 else cursor,
            currentId = effectiveCurrent,
            randomRound = round,
            previousRoundLastId = previousLast,
            queueFingerprint = fingerprint,
            historyTransforms = transforms,
            sourceHistoryCursors = sourceCursors,
            historySourceIds = owners,
        ).immutableState()
    }

    /**
     * Applies a catalog change to the round already in progress.
     *
     * Positions keep the order the round drew them in, disabled images drop out, and images added
     * by a scan join the tail so they play without waiting for the next round.
     *
     * Bound: an extended order is no longer a pure function of the seed, so a snapshot persisted
     * without [PlaybackSnapshot.queueIds] rebuilds the rest of this round from the seed after the
     * process restarts. Any repeat stays inside the round that saw the change.
     */
    private fun extendRound(queueIds: List<String>, round: Int, previousRoundLastId: String?): List<String> {
        val kept = effectiveRandomIds(queueIds)
        val rebuilt = randomQueue(round, previousRoundLastId)
        if (kept.isEmpty()) return rebuilt
        val known = kept.toHashSet()
        return (kept + rebuilt.filterNot { it in known }).frozenIds()
    }

    /**
     * Bounds the stored history.
     *
     * History grows by one entry per gesture and is persisted, so an unbounded list eventually
     * costs the same encode/parse work that the derived queues were removed for. The window keeps
     * the current image and its recent past; only reachable-by-gesture depth beyond
     * [HISTORY_LIMIT] entries is dropped.
     */
    private fun trimHistory(state: PlaybackSnapshot): Triple<List<String>, Int, Int> {
        val history = state.historyIds
        if (history.size <= HISTORY_LIMIT) return Triple(history, state.historyCursor, 0)
        val cursor = state.historyCursor.coerceIn(0, history.lastIndex)
        val start = (cursor - HISTORY_BEFORE_CURSOR).coerceAtLeast(0)
            .coerceAtMost(history.size - HISTORY_LIMIT)
        return Triple(history.subList(start, start + HISTORY_LIMIT).toList(), cursor - start, start)
    }

    /** Order-sensitive identity of the catalog, used to decide whether a round still applies. */
    private fun catalogFingerprint(): Int = catalogFingerprintCache ?: itemsById.keys
        .fold(itemsById.size * 31 + 17) { hash, id -> hash * 31 + id.hashCode() }
        .also { catalogFingerprintCache = it }

    /** Memoised [makeRandomQueue]; sanitize runs on every commit and restore. */
    private fun randomQueue(round: Int, previousLast: String?): List<String> {
        val fingerprint = catalogFingerprint()
        randomQueueCache?.let { cached ->
            if (cached.round == round && cached.previousLast == previousLast && cached.fingerprint == fingerprint) {
                return cached.queue
            }
        }
        val queue = makeRandomQueue(round, previousLast)
        randomQueueCache = RandomQueueCache(round, previousLast, fingerprint, queue)
        return queue
    }

    private class RandomQueueCache(
        val round: Int,
        val previousLast: String?,
        val fingerprint: Int,
        val queue: List<String>,
    )

    /** 精确目录内容与顺序比较；Map.equals会忽略次序，不能替代播放目录的身份。 */
    private fun sameCatalog(other: List<PlaybackItem>): Boolean {
        if (other.size != itemsById.size) return false
        val previous = itemsById.values.iterator()
        val sourceStrings = SourceStringComparison()
        return other.all { preparationCounter?.visit(); sameItem(previous.next(), it, sourceStrings) }
    }

    /** 有变化或重复输入沿原 first-wins 去重后，仍精确比较全部 entry。 */
    private fun sameCatalog(other: LinkedHashMap<String, PlaybackItem>): Boolean {
        if (other.size != itemsById.size) return false
        val previous = itemsById.entries.iterator()
        val sourceStrings = SourceStringComparison()
        return other.entries.all { entry ->
            preparationCounter?.visit()
            val old = previous.next()
            old.key == entry.key && sameItem(old.value, entry.value, sourceStrings)
        }
    }

    private fun sameItem(left: PlaybackItem, right: PlaybackItem, sources: SourceStringComparison): Boolean =
        left.id == right.id && left.displayName == right.displayName &&
            left.relativePath == right.relativePath && left.sourceOrder == right.sourceOrder &&
            sources.equalId(left.sourceId, right.sourceId) &&
            sources.equalKind(left.sourceKind, right.sourceKind) &&
            sources.equalRoot(left.sourceRootUri, right.sourceRootUri)

    /**
     * 同一次完整比较中，只复用已成功 String.equals 的引用对。String不可变；canonical来源
     * 字符串在每图共享时，不必重复扫描长rootUri。新引用仍真实比较，矛盾元数据仍拒绝。
     * 每轮比较各自持有memo，不以目录引用／数量／hash认定七字段相同，也不留跨动作缓存。
     */
    private class SourceStringComparison {
        private val ids = IdentityHashMap<String, String>()
        private val kinds = IdentityHashMap<String, String>()
        private val roots = IdentityHashMap<String, String>()
        fun equalId(left: String, right: String): Boolean = equal(left, right, ids)
        fun equalKind(left: String, right: String): Boolean = equal(left, right, kinds)
        fun equalRoot(left: String, right: String): Boolean = equal(left, right, roots)
        private fun equal(left: String, right: String, memo: IdentityHashMap<String, String>): Boolean {
            if (left === right || memo[left] === right) return true
            if (left != right) return false
            memo[left] = right
            return true
        }
    }

    private fun savedManualIds(existing: List<String>): List<String> {
        manualQueueCache?.let { cached ->
            if (existing === cached.output || existing == cached.input || existing == cached.output) return cached.output
        }
        preparationCounter?.let { it.queueRebuilds++ }
        val queue = (existing + itemsById.keys).distinct().frozenIds()
        manualQueueCache = QueueDerivationCache(existing.frozenIds(), queue)
        return queue
    }

    private fun effectiveRandomIds(existing: List<String>): List<String> {
        filteredRandomQueueCache?.let { cached ->
            if (existing === cached.output || existing == cached.input || existing == cached.output) return cached.output
        }
        preparationCounter?.let { it.queueRebuilds++ }
        val queue = existing.filter(itemsById::containsKey).frozenIds()
        filteredRandomQueueCache = QueueDerivationCache(
            existing.frozenIds(), queue,
            coversCatalog = queue.size == itemsById.size && queue.toSet().size == itemsById.size,
        )
        return queue
    }

    /** 同一不可变队列只建一次位置表，坏图／历史跳过不重复扫描整条队列。 */
    private fun queuePosition(queue: List<String>, id: String?): Int {
        if (id == null) return -1
        var cached = queuePositionCache
        if (cached == null || (queue !== cached.queue && queue != cached.queue)) {
            preparationCounter?.let { it.queueRebuilds++ }
            val positions = HashMap<String, Int>(queue.size * 2)
            queue.forEachIndexed { index, item -> positions.putIfAbsent(item, index) }
            // 所有调用点的queue来自冻结后的派生队列，不保留调用方可变输入引用。
            cached = QueuePositionCache(queue, positions)
            queuePositionCache = cached
        }
        return cached.positions[id] ?: -1
    }

    private class QueueDerivationCache(val input: List<String>, val output: List<String>, val coversCatalog: Boolean = false)
    private class QueuePositionCache(val queue: List<String>, val positions: Map<String, Int>)
    private fun List<String>.frozenIds(): List<String> = immutablePlaybackIds()

    /** No ordinary caller-owned collection can alias committed state and fool an equality gate. */
    private fun PlaybackSnapshot.immutableState(): PlaybackSnapshot = copy(
        queueIds = queueIds.immutablePlaybackIds(),
        historyIds = historyIds.immutablePlaybackIds(),
        historyTransforms = Collections.unmodifiableList(ArrayList(historyTransforms)),
        historySourceIds = historySourceIds.immutablePlaybackIds(),
        sourcePositions = Collections.unmodifiableMap(LinkedHashMap(sourcePositions)),
        sourceHistoryCursors = Collections.unmodifiableMap(LinkedHashMap(sourceHistoryCursors)),
        resumeSourceIds = resumeSourceIds.immutablePlaybackIds(),
    )

    /**
     * Sorts by the directory tree first, then by the file name inside that directory. Reversing
     * every sibling comparison for descending order keeps a parent directory ahead of its child;
     * reversing the completed list would put a child before its parent and interleave trees.
     */
    private fun sortedIds(descending: Boolean): List<String> {
        (if (descending) sortedDescending else sortedAscending)?.let { return it }
        // Tokenise once per item; splitting with a regex inside every comparison dominated
        // the sort for thousands of images.
        preparationCounter?.let { it.queueRebuilds++ }
        val sorted = itemsById.values.map(::SortKey)
            .sortedWith { left, right ->
                val bySourceOrder = left.item.sourceOrder.compareTo(right.item.sourceOrder)
                if (bySourceOrder != 0) return@sortedWith if (descending) -bySourceOrder else bySourceOrder
                val byPath = compareHierarchicalPath(left, right, descending)
                if (byPath != 0) return@sortedWith byPath

                val bySource = compareNaturalText(left.sourceId, right.sourceId, descending)
                if (bySource != 0) return@sortedWith bySource

                val byFullPath = compareNaturalText(left.relativePath, right.relativePath, descending)
                if (byFullPath != 0) return@sortedWith byFullPath

                compareNaturalText(left.id, right.id, descending)
            }
            .map { it.item.id }.frozenIds()
        if (descending) sortedDescending = sorted else sortedAscending = sorted
        return sorted
    }

    private fun compareHierarchicalPath(
        left: SortKey,
        right: SortKey,
        descending: Boolean,
    ): Int {
        val leftParts = left.pathParts
        val rightParts = right.pathParts
        val directoryCount = minOf(leftParts.lastIndex, rightParts.lastIndex)
        for (index in 0 until directoryCount) {
            val byDirectory = naturalCompare(leftParts[index], rightParts[index])
            if (byDirectory != 0) return if (descending) -byDirectory else byDirectory
        }

        // A path prefix is always the parent directory. Keep it before descendants in both
        // directions so a folder's contents remain contiguous and never cross its children.
        if (leftParts.size != rightParts.size) {
            return leftParts.size.compareTo(rightParts.size)
        }

        val byFileName = naturalCompare(leftParts.last(), rightParts.last())
        return if (descending) -byFileName else byFileName
    }

    private inner class SortKey(val item: PlaybackItem) {
        val pathParts = pathParts(item).map(::NaturalText)

        // Tie-breakers are only reached when two items share a directory path and file name.
        // Tokenising every source ID, path and content URI up front cost three extra regex splits
        // per image, which is tens of thousands of wasted splits on a large library.
        val sourceId: NaturalText get() = NaturalText(item.sourceId)
        val relativePath: NaturalText get() = NaturalText(item.relativePath)
        val id: NaturalText get() = NaturalText(item.id)
    }

    private class NaturalText(val raw: String) {
        val tokens: List<String> = TOKEN.split(raw.lowercase(Locale.ROOT))
    }

    private fun pathParts(item: PlaybackItem): List<String> {
        val path = item.relativePath
            .replace('\\', '/')
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?: item.displayName
        return path.split('/').filter { it.isNotBlank() }.ifEmpty { listOf(item.displayName) }
    }

    private fun compareNaturalText(left: NaturalText, right: NaturalText, descending: Boolean): Int {
        val result = naturalCompare(left, right)
        return if (descending) -result else result
    }

    private fun makeRandomQueue(round: Int, previousLast: String?): List<String> {
        preparationCounter?.let { it.queueRebuilds++ }
        val ids = itemsById.keys.toList()
        if (ids.size <= 1) return ids.frozenIds()
        val shuffled = ids.shuffled(Random(randomSeed xor round.toLong()))
        if (shuffled.firstOrNull() == previousLast) {
            return (shuffled.drop(1) + shuffled.first()).frozenIds()
        }
        return shuffled.frozenIds()
    }

    private fun cancelPending() {
        pendingVersion = null
        pendingFresh = false
        pendingSelected = false
        working = committed
    }

    private data class Quad(
        val queue: List<String>,
        val index: Int,
        val round: Int,
        val previousLast: String?,
    )

    private fun naturalCompare(left: NaturalText, right: NaturalText): Int {
        val leftParts = left.tokens
        val rightParts = right.tokens
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val l = leftParts.getOrNull(index) ?: ""
            val r = rightParts.getOrNull(index) ?: ""
            val result = if (l.all(Char::isDigit) && r.all(Char::isDigit)) {
                compareDigits(l, r)
            } else {
                l.compareTo(r)
            }
            if (result != 0) return result
        }
        return left.raw.compareTo(right.raw)
    }

    /** Numeric comparison of ASCII/Unicode digit runs without allocating big integers. */
    private fun compareDigits(left: String, right: String): Int {
        if (left.any { it !in '0'..'9' } || right.any { it !in '0'..'9' }) {
            return BigInteger(left.ifEmpty { "0" }).compareTo(BigInteger(right.ifEmpty { "0" }))
        }
        val l = left.trimStart('0')
        val r = right.trimStart('0')
        if (l.length != r.length) return l.length.compareTo(r.length)
        return l.compareTo(r)
    }

    internal companion object {
        private val TOKEN = Regex("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")

        /** Entries kept in the persisted history window. */
        const val HISTORY_LIMIT = 2_000

        /** Of that window, the depth kept behind the current image for 上一张. */
        const val HISTORY_BEFORE_CURSOR = 1_600
    }
}
