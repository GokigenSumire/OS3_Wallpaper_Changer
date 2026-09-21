package dev.abdus.apps.shimmer

import java.util.concurrent.CancellationException

/** A failed or partial enumeration must never be confused with a complete empty folder. */
sealed interface ScanResult<out T> {
    data class Complete<T>(val images: List<T>) : ScanResult<T>
    data class Failed(val reason: ScanFailure) : ScanResult<Nothing>
}

enum class ScanFailure {
    NULL_RESULT,
    INCOMPLETE_RESULT,
    PERMISSION,
    METADATA_DECODE,
    READ,
}

internal class ScanException(val reason: ScanFailure) : Exception(reason.name)

/** Shared by the real scanners and fault-injection tests; never exposes provider error text. */
internal fun <T> captureScan(
    checkActive: () -> Unit,
    enumerate: () -> List<T>,
): ScanResult<T> = try {
    checkActive()
    val images = enumerate()
    checkActive()
    ScanResult.Complete(images.toList())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: SecurityException) {
    ScanResult.Failed(ScanFailure.PERMISSION)
} catch (failed: ScanException) {
    ScanResult.Failed(failed.reason)
} catch (_: Exception) {
    ScanResult.Failed(ScanFailure.READ)
}

/** No commit is entered for a failed scan or a cancellation received before the transaction. */
internal suspend fun <T> commitCompletedScan(
    result: ScanResult<T>,
    checkActive: () -> Unit,
    commit: suspend (List<T>) -> Unit,
): Boolean {
    checkActive()
    if (result !is ScanResult.Complete) return false
    commit(result.images)
    return true
}
