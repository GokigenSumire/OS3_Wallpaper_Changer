package dev.abdus.apps.shimmer.media

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

/** Opens originals read-only. AFD subranges are respected for both metadata and frame extraction. */
object MediaProbe {
    private fun length(asset: AssetFileDescriptor): Long =
        MediaSourceBounds.length(asset.declaredLength, asset.parcelFileDescriptor.statSize, asset.startOffset)
    data class VideoInfo(val width: Int, val height: Int, val durationMs: Long?, val hdr: Boolean)
    data class Poster(val bitmap: Bitmap, val info: VideoInfo)

    private fun configure(retriever: MediaMetadataRetriever, asset: AssetFileDescriptor, range: MediaByteRange?) {
        val total = length(asset)
        if (range != null) require(total < 0 || range.fits(total))
        val start = Math.addExact(asset.startOffset, range?.offset ?: 0)
        val length = range?.length ?: total
        if (length > 0) retriever.setDataSource(asset.fileDescriptor, start, length)
        else {
            require(start == 0L)
            retriever.setDataSource(asset.fileDescriptor)
        }
    }

    private fun metadata(retriever: MediaMetadataRetriever): VideoInfo? {
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val swap = Math.floorMod(rotation, 180) == 90
        return VideoInfo(if (swap) h else w, if (swap) w else h,
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it >= 0 }, false)
    }

    fun videoInfo(asset: AssetFileDescriptor, range: MediaByteRange? = null): VideoInfo? {
        val retriever = MediaMetadataRetriever()
        return try { configure(retriever, asset, range); metadata(retriever) } catch (_: Exception) { null }
        finally { retriever.release() }
    }

    fun videoInfo(resolver: ContentResolver, uri: Uri, range: MediaByteRange? = null): VideoInfo? =
        resolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            videoInfo(asset, range)?.let { info -> info.copy(hdr = hasHdr(asset, range)) }
        }

    private fun hasHdr(asset: AssetFileDescriptor, range: MediaByteRange?): Boolean {
        val extractor = MediaExtractor()
        return try {
            val start = Math.addExact(asset.startOffset, range?.offset ?: 0)
            val length = range?.length ?: length(asset)
            if (length > 0) extractor.setDataSource(asset.fileDescriptor, start, length)
            else if (start == 0L) extractor.setDataSource(asset.fileDescriptor) else return false
            (0 until extractor.trackCount).any { index ->
                val format = extractor.getTrackFormat(index)
                format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                    format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) in setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)
            }
        } catch (_: Exception) { false } finally { extractor.release() }
    }

    suspend fun poster(resolver: ContentResolver, uri: Uri, maxWidth: Int, maxHeight: Int): Poster? =
        MediaPlaybackOwner.withTransientDecoder {
        resolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            val retriever = MediaMetadataRetriever()
            try {
                configure(retriever, asset, null)
                val info = metadata(retriever) ?: return@use null
                val factor = minOf(1.0, maxWidth.coerceAtLeast(1).toDouble() / info.width,
                    maxHeight.coerceAtLeast(1).toDouble() / info.height)
                val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (info.width * factor).toInt().coerceAtLeast(1), (info.height * factor).toInt().coerceAtLeast(1)) ?: return@use null
                Poster(frame, info.copy(hdr = hasHdr(asset, null)))
            } catch (_: Exception) { null } finally { retriever.release() }
        }
        }

    /** No full-file read, copying, or writer. Invalid/unknown metadata keeps the original still. */
    fun motionPhoto(resolver: ContentResolver, uri: Uri): MotionPhotoParser.Result? = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            val total = length(asset).takeIf { it > 0 } ?: return@use null
            val duplicate = ParcelFileDescriptor.dup(asset.fileDescriptor)
            ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
                val channel = input.channel
                fun read(position: Long, size: Int): ByteArray? {
                    if (position < 0 || size < 0 || position > total || size.toLong() > total - position) return null
                    val buffer = ByteBuffer.allocate(size)
                    channel.position(Math.addExact(asset.startOffset, position))
                    while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) return null
                    return buffer.array()
                }
                MotionPhotoParser.inspect(total, ::read)
            }
        }
    }.getOrNull()
}
