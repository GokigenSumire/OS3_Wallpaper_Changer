package dev.abdus.apps.shimmer.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

object MediaCopy {
    suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    suspend fun transfer(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            count = Math.addExact(count, read.toLong())
        }
        currentCoroutineContext().ensureActive()
        return count
    }
    fun sameDocument(a: android.net.Uri, b: android.net.Uri): Boolean {
        if (a == b) return true
        if (a.authority != b.authority || a.scheme != b.scheme) return false
        return runCatching {
            android.provider.DocumentsContract.getDocumentId(a) == android.provider.DocumentsContract.getDocumentId(b)
        }.getOrDefault(false)
    }
}
