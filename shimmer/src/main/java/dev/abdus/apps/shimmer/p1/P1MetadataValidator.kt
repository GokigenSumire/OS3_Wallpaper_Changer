package dev.abdus.apps.shimmer.p1

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Bounded IO workers; original enumeration order and all-or-preserve failure semantics remain. */
internal object P1MetadataValidator {
    const val MAX_WORKERS = 4
    data class Dimensions(val width: Int, val height: Int)

    suspend fun validate(
        outcome: P1ScanOutcome.Complete,
        readBounds: suspend (P1ScannedImage) -> Dimensions?,
        onProcessed: (Int, Int) -> Unit = { _, _ -> },
    ): P1ScanOutcome {
        val accepted = arrayOfNulls<P1ScannedImage>(outcome.images.size)
        val nextIndex = AtomicInteger()
        val progressLock = Any()
        var processed = 0
        return try {
            coroutineScope {
                val workers = minOf(MAX_WORKERS, outcome.images.size)
                (0 until workers).map {
                    async(Dispatchers.IO) {
                        while (true) {
                            coroutineContext.ensureActive()
                            // Keep the same bounded worker count, but do not strand a worker
                            // behind one slow URI while unrelated images remain to validate.
                            val index = nextIndex.getAndIncrement()
                            if (index >= outcome.images.size) break
                            val image = outcome.images[index]
                            val bounds = try {
                                readBounds(image)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (security: SecurityException) {
                                throw security
                            } catch (_: Exception) {
                                null // Isolate a malformed/stale individual image, never delete it.
                            }
                            coroutineContext.ensureActive()
                            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                                accepted[index] = image.copy(width = bounds.width, height = bounds.height)
                            }
                            synchronized(progressLock) {
                                processed++
                                onProcessed(processed, outcome.images.size)
                            }
                        }
                    }
                }.awaitAll()
            }
            coroutineContext.ensureActive()
            val images = accepted.filterNotNull()
            if (outcome.images.isNotEmpty() && images.isEmpty()) {
                P1ScanOutcome.Failed(P1ScanFailure.METADATA, outcome.visitedNodes, 0)
            } else {
                outcome.copy(images = images, skippedMetadata = outcome.images.size - images.size)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            // coroutineScope has cancelled AND joined siblings before publishing a failure.
            P1ScanOutcome.Failed(P1ScanFailure.PERMISSION, outcome.visitedNodes, accepted.count { it != null })
        }
    }
}
