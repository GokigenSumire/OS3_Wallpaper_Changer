package dev.abdus.apps.shimmer.p1

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.BitmapFactory
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.InputStream

/**
 * 每张图片仍真实解码 bounds，不缓存权限或旧尺寸，也不将 content URI 猜测为裸文件路径。
 * 仅完整、无声明长度限制且能可靠定位至 0 的 AFD 可走 FD；子段和管道沿同次 AFD 流式读取。
 * 调用方在 IO 工人内使用。异常保留给原校验器分类；本类不吞权限失败或取消。
 */
internal class P1ImageBoundsReader(
    private val resolver: ContentResolver,
    private val decoder: Decoder = AndroidDecoder,
    private val videoDecoder: ((AssetFileDescriptor) -> P1MetadataValidator.Dimensions?)? =
        if (decoder === AndroidDecoder) { asset ->
            dev.abdus.apps.shimmer.media.MediaProbe.videoInfo(asset)?.let { P1MetadataValidator.Dimensions(it.width, it.height) }
        } else null,
    private val seekDescriptor: (FileDescriptor) -> Long = { fd ->
        Os.lseek(fd, 0L, OsConstants.SEEK_SET)
    },
) {
    /** 注入仅用于验证范围／资源契约；默认三条路径均调用真实 BitmapFactory。 */
    internal interface Decoder {
        fun file(path: String?): P1MetadataValidator.Dimensions?
        fun descriptor(fd: FileDescriptor): P1MetadataValidator.Dimensions?
        fun stream(input: InputStream): P1MetadataValidator.Dimensions?
    }

    fun read(uri: Uri): P1MetadataValidator.Dimensions? = when (uri.scheme) {
        "file" -> decoder.file(uri.path).positiveOrNull() ?: videoDecoder?.let { video ->
            resolver.openAssetFileDescriptor(uri, "r")?.use { video(it).positiveOrNull() }
        }
        "content" -> resolver.openAssetFileDescriptor(uri, "r")?.let(::readAsset)
        else -> resolver.openInputStream(uri)?.use { decoder.stream(it).positiveOrNull() }
    }

    /** 持有并关闭真实 AFD；测试直接覆盖该资源边界，避免 Resolver 重新包装时擦除子类行为。 */
    internal fun readAsset(asset: AssetFileDescriptor): P1MetadataValidator.Dimensions? =
        asset.use { current -> decodeAsset(current) }

    private fun decodeAsset(asset: AssetFileDescriptor): P1MetadataValidator.Dimensions? {
        // getLength() may substitute statSize: only the original declaredLength proves no subrange.
        if (asset.startOffset == 0L && asset.declaredLength == AssetFileDescriptor.UNKNOWN_LENGTH) {
            val descriptor = asset.fileDescriptor
            // A nonzero seek result may have moved the position: never decode uncertain tail bytes.
            val positionedAtStart = seekToStart(descriptor) ?: return null
            if (positionedAtStart) {
                decoder.descriptor(descriptor).positiveOrNull()?.let { return it }
                // A native decoder may reject a provider representation accepted by stream decoding.
                // Rewind the same descriptor before fallback; never reopen the provider or decode tail bytes.
                if (seekToStart(descriptor) != true) return null
            }
        }
        return asset.createInputStream().use { input ->
            // AutoCloseInputStream owns the same PFD. Probe video BEFORE use closes it.
            decoder.stream(input).positiveOrNull() ?: videoDecoder?.invoke(asset).positiveOrNull()
        }
    }

    private fun P1MetadataValidator.Dimensions?.positiveOrNull(): P1MetadataValidator.Dimensions? =
        this?.takeIf { it.width > 0 && it.height > 0 }

    private object AndroidDecoder : Decoder {
        override fun file(path: String?): P1MetadataValidator.Dimensions? {
            val options = boundsOptions()
            BitmapFactory.decodeFile(path, options)
            return dimensions(options)
        }

        override fun descriptor(fd: FileDescriptor): P1MetadataValidator.Dimensions? {
            val options = boundsOptions()
            BitmapFactory.decodeFileDescriptor(fd, null, options)
            return dimensions(options)
        }

        override fun stream(input: InputStream): P1MetadataValidator.Dimensions? {
            val options = boundsOptions()
            BitmapFactory.decodeStream(input, null, options)
            return dimensions(options)
        }

        private fun boundsOptions() = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        private fun dimensions(options: BitmapFactory.Options) =
            P1MetadataValidator.Dimensions(options.outWidth, options.outHeight)
    }

    // true: positioned at zero; false: nonseekable without reposition; null: uncertain position.
    private fun seekToStart(fd: FileDescriptor): Boolean? = try {
        if (seekDescriptor(fd) == 0L) true else null
    } catch (_: ErrnoException) {
        false // e.g. ESPIPE: do not let decodeFileDescriptor implicitly consume/close a pipe.
    }
}
