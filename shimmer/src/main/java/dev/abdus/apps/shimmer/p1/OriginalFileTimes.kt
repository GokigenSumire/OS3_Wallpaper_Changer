package dev.abdus.apps.shimmer.p1

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import dev.abdus.apps.shimmer.p2.FileTimestamps
import java.io.File
import java.util.concurrent.CancellationException

/** Optional metadata only. Never grants access, follows guessed paths, or substitutes copy dates. */
internal object OriginalFileTimes {
    private val nativeAvailable = try {
        System.loadLibrary("file_times"); true
    } catch (_: LinkageError) { false }

    private external fun nativeFd(fd: Int): LongArray?
    private external fun nativePath(path: ByteArray): LongArray?

    internal fun decode(values: LongArray?): FileTimestamps = FileTimestamps(
        values?.getOrNull(0)?.takeIf { it > 0 }, values?.getOrNull(1)?.takeIf { it > 0 },
    )

    fun file(file: File): FileTimestamps {
        val original = if (nativeAvailable) decode(nativePath(file.path.toByteArray(Charsets.UTF_8))) else FileTimestamps()
        return original.copy(modifiedMillis = original.modifiedMillis ?: file.lastModified().takeIf { it > 0 })
    }

    fun document(context: Context, uri: Uri, signal: CancellationSignal? = null): FileTimestamps {
        if (!nativeAvailable) return FileTimestamps()
        signal?.throwIfCanceled()
        // Only the platform external-storage provider's already queried document can map to a
        // physical original. A third-party provider may return a cache file/pipe: don't stat it.
        if (uri.authority != "com.android.externalstorage.documents") return FileTimestamps()
        return try {
            val id = DocumentsContract.getDocumentId(uri)
            val volume = id.substringBefore(':')
            val relative = id.substringAfter(':', "")
            val manager = context.getSystemService(StorageManager::class.java)
            val base = manager?.storageVolumes?.firstOrNull {
                if (volume == "primary") it.isPrimary else it.uuid.equals(volume, ignoreCase = true)
            }?.directory
            val candidate = base?.let { resolveInside(it, relative) }
            val byPath = candidate?.let(::file) ?: FileTimestamps()
            if (byPath.createdMillis != null) byPath else {
                val byFd = context.contentResolver.openFileDescriptor(uri, "r", signal)?.use { decode(nativeFd(it.fd)) }
                    ?: FileTimestamps()
                FileTimestamps(byFd.createdMillis, byPath.modifiedMillis ?: byFd.modifiedMillis)
            }
        } catch (cancelled: OperationCanceledException) { throw cancelled
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { FileTimestamps() }
    }

    internal fun resolveInside(base: File, relative: String): File? {
        if (relative.split('/').any { it == ".." } || relative.startsWith('/') || '\u0000' in relative) return null
        val root = base.canonicalFile
        val child = File(root, relative).canonicalFile
        return child.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }
}
