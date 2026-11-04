package dev.abdus.apps.shimmer.media

import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.VideoFrameDecoder
import coil.fetch.SourceResult
import coil.request.Options

/** Coil thumbnails share the same video budget as the formal and private-preview players. */
class BudgetedVideoDecoderFactory : Decoder.Factory {
    private val factory = VideoFrameDecoder.Factory()
    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val decoder = factory.create(result, options, imageLoader) ?: return null
        return object : Decoder {
            override suspend fun decode(): DecodeResult? = MediaPlaybackOwner.withTransientDecoder { decoder.decode() }
        }
    }
}
