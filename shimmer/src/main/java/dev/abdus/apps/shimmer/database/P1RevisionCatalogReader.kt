package dev.abdus.apps.shimmer.database

import android.annotation.SuppressLint
import androidx.room.withTransaction
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A bounded memo of an exact catalog certified by this database's real transactional revision.
 * Each call reads the stamp and the complete trigger contract in the same SQLite transaction as
 * the fallback full reader. Neither Room invalidation delivery nor a timer/hash authorises a hit.
 * Preferences, rule selection, image permissions and decoding remain the caller's live work.
 */
class P1RevisionCatalogReader(private val database: ShimmerDatabase) {
    private val fullReader = P1PlaybackCursorReader(database)
    private val readMutex = Mutex()
    private val cacheGuard = Any()
    private val entries = ArrayList<Entry>(2)
    private var invalidationToken = Any()

    suspend fun getEnabledPlaybackImages(): List<P1PlaybackImage> =
        read(Key.Enabled) { fullReader.getEnabledPlaybackImages() }

    suspend fun getRulePlaybackImages(sourceIds: List<String>): List<P1PlaybackImage> {
        // Selection is membership-based in the original query; duplicate IDs/order are not state.
        if (sourceIds.isEmpty()) return emptyList()
        val canonical = Collections.unmodifiableList(sourceIds.toSet().sorted())
        return read(Key.Rule(canonical)) { fullReader.getRulePlaybackImages(canonical) }
    }

    private suspend fun read(key: Key, fullRead: suspend () -> List<P1PlaybackImage>): List<P1PlaybackImage> {
        currentCoroutineContext().ensureActive()
        // A caller-owned write transaction can roll back to a revision later reused by a different
        // write. Never use/publish such provisional rows. Do not wait on readMutex while that caller
        // holds SQLite's transaction: another reader may already own the mutex waiting for SQLite.
        if (callerHasTransaction()) {
            invalidate()
            return database.withTransaction {
                currentCoroutineContext().ensureActive()
                freeze(fullRead()).also { currentCoroutineContext().ensureActive() }
            }
        }
        return readMutex.withLock {
            currentCoroutineContext().ensureActive()
            val token = synchronized(cacheGuard) { invalidationToken }
            val result = database.withTransaction {
                currentCoroutineContext().ensureActive()
                val stamp = try {
                    P1CatalogRevision.readVerified(database)
                } catch (cancelled: CancellationException) {
                    invalidate()
                    throw cancelled
                } catch (unverified: Exception) {
                    // A missing/malformed revision table disables the optimisation, not playback.
                    // The original full query still runs and its failures are never caught here.
                    invalidate()
                    null
                }
                // Missing/tampered/saturated contracts must invalidate earlier memos too: changes
                // while a trigger was absent must not become stale hits when it is restored.
                if (stamp == null) invalidate()
                val cached = if (stamp == null) null else synchronized(cacheGuard) {
                    if (token !== invalidationToken) null else entries.firstOrNull { it.stamp == stamp && it.key == key }
                }
                val images = cached?.images ?: freeze(fullRead())
                currentCoroutineContext().ensureActive()
                Result(images, stamp, token)
            }
            currentCoroutineContext().ensureActive()
            // Publication occurs only after our read transaction returns successfully. A nested
            // caller/invalid contract racing this handoff replaces the identity token and wins.
            synchronized(cacheGuard) {
                if (result.stamp != null && result.token === invalidationToken) {
                    entries.removeAll { it.stamp != result.stamp || it.key == key }
                    entries.add(Entry(result.stamp, key, result.images))
                    while (entries.size > 2) entries.removeAt(0)
                }
            }
            result.images
        }
    }

    private fun invalidate() = synchronized(cacheGuard) {
        entries.clear()
        invalidationToken = Any()
    }

    /** Fixed Room 2.7.2 compatibility mode carries this marker across withContext dispatchers.
     * The platform inTransaction check alone is thread-local and misses an inherited transaction.
     * This deliberately narrow internal dependency must be rechecked before upgrading Room.
     */
    @SuppressLint("RestrictedApi")
    private fun callerHasTransaction(): Boolean =
        database.inTransaction() || database.suspendingTransactionId.get() != null

    private fun freeze(images: List<P1PlaybackImage>): List<P1PlaybackImage> =
        P1ImmutablePlaybackImages(images)

    private sealed interface Key {
        data object Enabled : Key
        data class Rule(val sourceIds: List<String>) : Key
    }
    private data class Entry(val stamp: P1CatalogStamp, val key: Key, val images: List<P1PlaybackImage>)
    private data class Result(val images: List<P1PlaybackImage>, val stamp: P1CatalogStamp?, val token: Any)
}
