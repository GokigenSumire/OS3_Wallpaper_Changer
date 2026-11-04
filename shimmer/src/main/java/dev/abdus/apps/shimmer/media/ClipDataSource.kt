package dev.abdus.apps.shimmer.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Rebase every extractor seek, not only the first open. Never expose the photo or trailing bytes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ClipDataSource(private val upstream: DataSource, private val range: MediaByteRange) : DataSource {
    private var remaining = 0L
    private var opened = false
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long {
        require(!opened)
        require(dataSpec.position in 0..range.length)
        if (dataSpec.position == range.length || dataSpec.length == 0L) { remaining = 0; return 0 }
        val part = range.subrange(dataSpec.position, dataSpec.length)
        val available = upstream.open(dataSpec.buildUpon().setPosition(part.offset).setLength(part.length).build())
        opened = true
        remaining = if (available == C.LENGTH_UNSET.toLong()) part.length else minOf(part.length, available)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = upstream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count else if (count == C.RESULT_END_OF_INPUT) remaining = 0
        return count
    }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        try { if (opened) upstream.close() } finally { opened = false; remaining = 0 }
    }
}
