package dev.abdus.apps.shimmer.p2

import kotlinx.serialization.Serializable

/** Only original filesystem times; null is unknown, never an import or capture date. */
@Serializable
data class FileTimestamps(val createdMillis: Long? = null, val modifiedMillis: Long? = null)

@Serializable
data class PlaybackDirectoryTime(val path: String, val times: FileTimestamps = FileTimestamps())
