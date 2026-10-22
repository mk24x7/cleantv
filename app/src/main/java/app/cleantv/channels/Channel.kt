package app.cleantv.channels

enum class ChannelKind { HLS, YOUTUBE }

/**
 * One live channel from the playlist.
 * For YOUTUBE entries, youtubeId is the fixed video id when the playlist gave one,
 * and liveUrl is a broadcaster live page (youtube.com/@handle/live) that is resolved
 * to the current video id at tune time when youtubeId is null.
 */
data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val group: String,
    val logoUrl: String?,
    val url: String,
    val kind: ChannelKind,
    val youtubeId: String? = null,
    val liveUrl: String? = null
)
