package app.cleantv.channels

/** Minimal #EXTM3U parser for #EXTINF lines with tvg-id, tvg-logo and group-title attributes. */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val watchRegex = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
    private val shortRegex = Regex("""youtu\.be/([A-Za-z0-9_-]{11})""")
    private val liveIdRegex = Regex("""youtube\.com/live/([A-Za-z0-9_-]{11})""")
    private val handleLiveRegex = Regex("""youtube\.com/(@[A-Za-z0-9_.-]+|channel/[A-Za-z0-9_-]+|c/[A-Za-z0-9_.-]+)/live""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var pendingAttrs: Map<String, String>? = null
        var pendingName: String? = null
        var number = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                val comma = line.lastIndexOf(',')
                val head = if (comma >= 0) line.substring(0, comma) else line
                pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                pendingAttrs = attrRegex.findAll(head).associate { it.groupValues[1] to it.groupValues[2] }
                continue
            }
            if (line.startsWith("#")) continue
            val name = pendingName ?: continue
            val attrs = pendingAttrs ?: emptyMap()
            number += 1
            out.add(build(number, name, attrs, line))
            pendingAttrs = null
            pendingName = null
        }
        return out
    }

    private fun build(number: Int, name: String, attrs: Map<String, String>, url: String): Channel {
        val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "General"
        val logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
        val id = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: slug(name)
        val isYoutube = url.contains("youtube.com") || url.contains("youtu.be")
        if (!isYoutube) {
            return Channel(id, number, name, group, logo, url, ChannelKind.HLS)
        }
        val videoId = watchRegex.find(url)?.groupValues?.get(1)
            ?: shortRegex.find(url)?.groupValues?.get(1)
            ?: liveIdRegex.find(url)?.groupValues?.get(1)
        val liveUrl = if (videoId == null && handleLiveRegex.containsMatchIn(url)) url else null
        return Channel(id, number, name, group, logo, url, ChannelKind.YOUTUBE, videoId, liveUrl)
    }

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
