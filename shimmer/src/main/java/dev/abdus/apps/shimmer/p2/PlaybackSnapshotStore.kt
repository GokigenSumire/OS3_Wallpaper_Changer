package dev.abdus.apps.shimmer.p2

import android.content.Context
import android.content.SharedPreferences
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p3.ImageTransform
import dev.abdus.apps.shimmer.p3.TransformCodec
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class StoreWriteWork(val runtimeCharacters: Int = 0, val manualIdsEncoded: Int = 0,
    val manualConfigurationWrites: Int = 0, val manualIdsCopied: Int = 0)
data class StoreCommitResult(val accepted: Boolean, val work: StoreWriteWork = StoreWriteWork())

/**
 * Runtime and transforms remain in one small preference file/editor. Low-frequency manual
 * configuration lives in two copy-on-write slots in a separate file: stable commits never
 * rewrite its XML. A new slot is committed before publishing its runtime reference.
 */
class PlaybackSnapshotStore(context: Context, val target: WallpaperTarget = WallpaperTarget.HOME) {
    private val preferences = context.applicationContext.getSharedPreferences(nameFor(target), Context.MODE_PRIVATE)
    private val manualPreferences = context.applicationContext.getSharedPreferences(manualNameFor(target), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cachedEncoded: String? = null
    private var cachedDecoded: PlaybackSnapshot? = null
    private var cachedReference: ManualReference? = null
    private var cachedManualSlot = -1
    private var cachedManualEncoded: String? = null
    private var cachedManual: ManualConfiguration? = null

    @Serializable private data class ManualReference(val slot: Int, val token: String)
    @Serializable private data class ManualConfiguration(val token: String, val ids: List<String>)
    @Serializable private data class RuntimeEnvelope(
        val format: Int = 2, val state: PlaybackSnapshot, val manual: ManualReference? = null,
    )
    private data class PendingWrite(
        val snapshot: PlaybackSnapshot, val encoded: String, val reference: ManualReference?,
        val config: ManualConfiguration?, val configEncoded: String?, val work: StoreWriteWork,
    )

    fun load(): PlaybackSnapshot? = synchronized(lock) {
        val encoded = stringOrNull(preferences, KEY) ?: return@synchronized null.also { clearDecodedCache() }
        val reference = cachedReference
        if (encoded == cachedEncoded && cachedDecoded != null &&
            (reference == null || stringOrNull(manualPreferences, slotKey(reference.slot)) == cachedManualEncoded)) {
            return@synchronized cachedDecoded
        }
        try {
            val objectValue = json.parseToJsonElement(encoded) as? JsonObject ?: error("Snapshot must be an object")
            val envelope = if ("format" in objectValue || "state" in objectValue) {
                require("format" in objectValue)
                json.decodeFromString<RuntimeEnvelope>(encoded).also { require(it.format == 2) }
            } else null
            val ref = envelope?.manual
            val state = envelope?.state ?: json.decodeFromString<PlaybackSnapshot>(encoded)
            val resolved = if (envelope == null) {
                state
            } else if (state.order == PlaybackOrder.MANUAL) {
                require(state.queueIds.isEmpty() && ref != null && ref.slot in 0..1 && ref.token.isNotBlank())
                val config = readConfiguration(ref) ?: error("Unusable manual order reference")
                state.copy(queueIds = config.ids)
            } else {
                require(ref == null && state.queueIds.isEmpty())
                state
            }
            resolved.frozen().also {
                cachedEncoded = encoded
                cachedDecoded = it
                cachedReference = ref
            }
        } catch (failed: Exception) {
            // Corruption is not permission to erase committed configuration or revive a stale memo.
            clearDecodedCache()
            null
        }
    }

    private fun readConfiguration(ref: ManualReference): ManualConfiguration? {
        val encoded = stringOrNull(manualPreferences, slotKey(ref.slot)) ?: return null
        val decoded = if (cachedManualSlot == ref.slot && encoded == cachedManualEncoded) cachedManual
        else try {
            json.decodeFromString<ManualConfiguration>(encoded).let {
                require(it.token.isNotBlank())
                it.copy(ids = it.ids.immutablePlaybackIds())
            }
        } catch (failed: Exception) { null }
        if (decoded == null || decoded.token != ref.token) return null
        cachedManualSlot = ref.slot
        cachedManualEncoded = encoded
        cachedManual = decoded
        return decoded
    }

    /** Legacy loading is read-only. The first real save publishes the new referenced format. */
    fun save(snapshot: PlaybackSnapshot) { synchronized(lock) { saveInsideLock(snapshot) } }

    private fun prepareWrite(snapshot: PlaybackSnapshot): PendingWrite {
        val copied = if (snapshot.order == PlaybackOrder.MANUAL && snapshot.queueIds !is ImmutablePlaybackIds) snapshot.queueIds.size else 0
        val frozen = snapshot.forPersistence().frozen()
        latestOrRequireAbsent() // Resolve the latest real reference, never overwrite corrupt stored data.
        var reference: ManualReference? = null
        var config: ManualConfiguration? = null
        var configEncoded: String? = null
        if (frozen.order == PlaybackOrder.MANUAL) {
            val oldReference = cachedReference
            val old = oldReference?.let(::readConfiguration)
            if (old != null && old.ids == frozen.queueIds) {
                reference = oldReference
            } else {
                // Flush the old runtime reference before ever reusing its unreferenced slot.
                // Failure aborts before config changes. This is COW ordering, NOT cross-file atomicity.
                check(preferences.edit().commit()) { "Cannot flush preceding playback state" }
                reference = ManualReference(if (oldReference?.slot == 0) 1 else 0, UUID.randomUUID().toString())
                config = ManualConfiguration(reference.token, frozen.queueIds)
                configEncoded = json.encodeToString(config)
                check(manualPreferences.edit().putString(slotKey(reference.slot), configEncoded).commit()) {
                    "Cannot persist manual configuration"
                }
            }
        }
        val encoded = json.encodeToString(RuntimeEnvelope(
            state = frozen.copy(queueIds = emptyList()), manual = reference,
        ))
        return PendingWrite(frozen, encoded, reference, config, configEncoded,
            StoreWriteWork(encoded.length, config?.ids?.size ?: 0, if (config == null) 0 else 1, copied))
    }

    private fun publish(write: PendingWrite, editor: SharedPreferences.Editor) {
        editor.putString(KEY, write.encoded).apply()
        if (write.config != null) {
            cachedManualSlot = write.reference!!.slot
            cachedManualEncoded = write.configEncoded
            cachedManual = write.config
        }
        cachedEncoded = write.encoded
        cachedDecoded = write.snapshot
        cachedReference = write.reference
    }

    private fun saveInsideLock(snapshot: PlaybackSnapshot): StoreWriteWork {
        val write = prepareWrite(snapshot)
        publish(write, preferences.edit())
        return write.work
    }

    fun clear() {
        synchronized(lock) { clearDecodedCache(); preferences.edit().remove(KEY).apply() }
    }

    fun clearHistory(resetManualOrder: Boolean = false): PlaybackSnapshot =
        update { latest -> clearedPlaybackHistory(latest, resetManualOrder) }

    fun update(transform: (PlaybackSnapshot?) -> PlaybackSnapshot): PlaybackSnapshot = synchronized(lock) {
        val latest = latestOrRequireAbsent()
        transform(latest).also { if (it.forPersistence() != latest) saveInsideLock(it) }
    }

    /** Exact latest composite comparison remains the admission boundary for other instances. */
    fun compareAndSave(expected: PlaybackSnapshot?, snapshot: PlaybackSnapshot): Boolean =
        compareAndSaveWithWork(expected, snapshot).accepted

    fun compareAndSaveWithWork(expected: PlaybackSnapshot?, snapshot: PlaybackSnapshot): StoreCommitResult = synchronized(lock) {
        val latest = load()
        // Failed decoding of a nonempty record must never authorize overwriting it as "no state".
        if ((latest == null && preferences.contains(KEY)) ||
            latest != expected?.forPersistence()) return@synchronized StoreCommitResult(false)
        StoreCommitResult(true, saveInsideLock(snapshot))
    }

    fun compareAndSaveIfAllowed(expected: PlaybackSnapshot?, snapshot: PlaybackSnapshot, admit: () -> Boolean): Boolean =
        compareAndSaveIfAllowedWithWork(expected, snapshot, admit).accepted

    /** Always acquire the Store monitor before an optional external publication authority. */
    fun compareAndSaveIfAllowed(
        expected: PlaybackSnapshot?, snapshot: PlaybackSnapshot,
        publicationGuard: (() -> Boolean) -> Boolean, admit: () -> Boolean,
    ): Boolean = synchronized(lock) {
        publicationGuard {
            if (!admit()) false else compareAndSaveWithWork(expected, snapshot).accepted
        }
    }

    fun compareAndSaveIfAllowedWithWork(
        expected: PlaybackSnapshot?, snapshot: PlaybackSnapshot, admit: () -> Boolean,
    ): StoreCommitResult = compareAndSaveIfAllowedWithWork(expected, snapshot, { it() }, admit)

    fun compareAndSaveIfAllowedWithWork(
        expected: PlaybackSnapshot?, snapshot: PlaybackSnapshot,
        publicationGuard: (() -> StoreCommitResult) -> StoreCommitResult, admit: () -> Boolean,
    ): StoreCommitResult = synchronized(lock) {
        publicationGuard {
            if (!admit()) StoreCommitResult(false) else compareAndSaveWithWork(expected, snapshot)
        }
    }

    fun forgetSource(sourceId: String) {
        synchronized(lock) {
            val latest = load() ?: return
            val updated = latest.withoutSource(sourceId)
            if (updated != latest) saveInsideLock(updated)
        }
    }

    fun loadImageTransform(itemId: String): ImageTransform? =
        TransformCodec.decode(preferences.getString(transformKey(itemId), null))

    /** Runtime and transform changes use the same small-file editor, including inherited copies. */
    fun commitImageTransform(itemId: String, transform: ImageTransform?): PlaybackSnapshot? = synchronized(lock) {
        val latest = latestOrRequireAbsent()
        val updated = latest?.withoutTransformsFor(itemId)
        val editor = preferences.edit()
        if (transform == null) editor.remove(transformKey(itemId))
        else editor.putString(transformKey(itemId), TransformCodec.encode(transform))
        if (updated != null && updated != latest) publish(prepareWrite(updated), editor) else editor.apply()
        updated
    }

    fun commitImageTransformIfAllowed(itemId: String, transform: ImageTransform?, admit: () -> Boolean): Boolean =
        commitImageTransformIfAllowed(itemId, transform, { it() }, admit)

    fun commitImageTransformIfAllowed(
        itemId: String, transform: ImageTransform?,
        publicationGuard: (() -> Boolean) -> Boolean, admit: () -> Boolean,
    ): Boolean = synchronized(lock) {
        publicationGuard {
            if (!admit()) false else {
                commitImageTransform(itemId, transform)
                true
            }
        }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { preferences.registerOnSharedPreferenceChangeListener(listener) }
    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { preferences.unregisterOnSharedPreferenceChangeListener(listener) }

    private fun clearDecodedCache() {
        cachedEncoded = null; cachedDecoded = null; cachedReference = null
        cachedManualSlot = -1; cachedManualEncoded = null; cachedManual = null
    }
    private fun latestOrRequireAbsent(): PlaybackSnapshot? = load().also {
        check(it != null || !preferences.contains(KEY)) { "Unusable playback state; preserved without replacement" }
    }
    private fun stringOrNull(prefs: SharedPreferences, key: String): String? =
        try { prefs.getString(key, null) } catch (invalidType: ClassCastException) { null }
    private fun PlaybackSnapshot.frozen(): PlaybackSnapshot = copy(
        queueIds = queueIds.immutablePlaybackIds(),
        historyIds = historyIds.immutablePlaybackIds(),
        historyTransforms = Collections.unmodifiableList(ArrayList(historyTransforms)),
        historySourceIds = historySourceIds.immutablePlaybackIds(),
        sourcePositions = Collections.unmodifiableMap(LinkedHashMap(sourcePositions)),
        sourceHistoryCursors = Collections.unmodifiableMap(LinkedHashMap(sourceHistoryCursors)),
        resumeSourceIds = resumeSourceIds.immutablePlaybackIds(),
    )

    companion object {
        private val lock = Any()
        const val NAME = "p2_playback_state"
        const val LOCK_NAME = "p10_lock_playback_state"
        const val SNAPSHOT_KEY = "committed_snapshot"
        private const val KEY = SNAPSHOT_KEY
        internal const val MANUAL_NAME = "p2_manual_orders_v2"
        internal const val LOCK_MANUAL_NAME = "p10_lock_manual_orders_v2"
        /** HOME keeps its exact existing files; constructing LOCK neither reads nor copies them. */
        fun nameFor(target: WallpaperTarget): String = when (target) {
            WallpaperTarget.HOME -> NAME
            WallpaperTarget.LOCK -> LOCK_NAME
        }
        internal fun manualNameFor(target: WallpaperTarget): String = when (target) {
            WallpaperTarget.HOME -> MANUAL_NAME
            WallpaperTarget.LOCK -> LOCK_MANUAL_NAME
        }
        internal fun slotKey(slot: Int): String = "slot_$slot"
        const val TRANSFORM_KEY_PREFIX = "p3_transform:"
        fun transformKey(itemId: String): String = TRANSFORM_KEY_PREFIX + itemId
    }
}
