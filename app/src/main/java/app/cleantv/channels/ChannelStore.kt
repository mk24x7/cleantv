package app.cleantv.channels

import android.content.Context
import java.io.File

/**
 * Channel list source. The bundled assets/channels.m3u is the default;
 * if /sdcard/CleanTV/channels.m3u exists it replaces the bundled list entirely.
 */
object ChannelStore {
    private const val ASSET = "channels.m3u"
    private val overrideFile = File("/sdcard/CleanTV/channels.m3u")

    @Volatile private var cached: List<Channel>? = null
    @Volatile private var cachedStamp: Long = -1L

    fun list(ctx: Context): List<Channel> {
        val stamp = if (overrideFile.exists()) overrideFile.lastModified() else 0L
        cached?.let { if (stamp == cachedStamp) return it }
        val text = try {
            if (overrideFile.exists()) overrideFile.readText()
            else ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        val parsed = M3uParser.parse(text)
        cached = parsed
        cachedStamp = stamp
        return parsed
    }

    fun find(ctx: Context, id: String?): Channel? =
        if (id == null) null else list(ctx).firstOrNull { it.id == id }

    fun isOverridden(): Boolean = overrideFile.exists()
}
