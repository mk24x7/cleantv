package app.cleantv.sendtotv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local web server for Send to TV.
 *  GET /?k=KEY          the drop page (assets/drop.html)
 *  GET /ws?k=KEY        WebSocket: JSON text frames for control, binary frames for data
 *  GET /stream/SID      loopback range proxy used only by the TV's own player
 *
 * The browser keeps the media file; the TV asks it for byte ranges on demand, so nothing is
 * stored on the TV. Binary frames carry an 8-byte header: request id (4 bytes) and a reserved
 * word (4 bytes), both big-endian.
 */
class DropServer(private val ctx: Context, port: Int, private val key: String) : NanoWSD(port) {

    private class Offer(val sid: String, val name: String, val size: Long, val mime: String, val client: Client)

    private val main = Handler(Looper.getMainLooper())
    private val clients: MutableSet<Client> = Collections.newSetFromMap(ConcurrentHashMap())
    private val offers = ConcurrentHashMap<String, Offer>()
    private val inflight = ConcurrentHashMap<Int, BlockingQueue<ByteArray>>()
    private val nextId = AtomicInteger(1)
    @Volatile private var pendingPhoto: Pair<String, String>? = null // id, mime

    // ---------------------------------------------------------------- HTTP

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val k = session.parameters["k"]?.firstOrNull()
        return when {
            uri == "/" || uri == "/index.html" -> {
                if (k != key) return forbidden()
                val html = runCatching { ctx.assets.open("drop.html").use { it.readBytes() } }.getOrNull()
                    ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "drop.html missing")
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(html), html.size.toLong())
                    .apply { addHeader("Cache-Control", "no-store") }
            }
            uri.startsWith("/stream/") -> serveStream(session, uri.removePrefix("/stream/"))
            else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")
        }
    }

    private fun forbidden(): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "Scan the code on the TV to open this page.")

    private fun serveStream(session: IHTTPSession, sid: String): Response {
        val remote = session.remoteIpAddress ?: ""
        if (remote != "127.0.0.1" && remote != "::1" && remote != "localhost") return forbidden()
        val offer = offers[sid] ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "no such file")
        if (!offer.client.ws.isOpen) return NanoHTTPD.newFixedLengthResponse(Response.Status.GONE, NanoHTTPD.MIME_PLAINTEXT, "source disconnected")
        val size = offer.size
        val rangeHeader = session.headers["range"]
        var start = 0L
        var end = size - 1
        var partial = false
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "bad range")
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            if (a.isEmpty()) { // suffix range: last N bytes
                val n = b.toLongOrNull() ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "bad range")
                start = maxOf(0L, size - n)
            } else {
                start = a.toLongOrNull() ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "bad range")
                if (b.isNotEmpty()) end = minOf(size - 1, b.toLongOrNull() ?: (size - 1))
            }
            if (start >= size || start > end) {
                return NanoHTTPD.newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, NanoHTTPD.MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */$size") }
            }
            partial = true
        }
        val length = end - start + 1
        val stream = RangeStream(offer, start, end)
        val resp = NanoHTTPD.newFixedLengthResponse(if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, offer.mime, stream, length)
        resp.addHeader("Accept-Ranges", "bytes")
        if (partial) resp.addHeader("Content-Range", "bytes $start-$end/$size")
        resp.addHeader("Cache-Control", "no-store")
        resp.setChunkedTransfer(false)
        return resp
    }

    /** Pulls byte ranges from the browser one chunk at a time, prefetching the next chunk. */
    private inner class RangeStream(private val offer: Offer, start: Long, private val endInclusive: Long) : InputStream() {
        private var pos = start
        private var buf: ByteArray? = null
        private var bufOff = 0
        private var prefetch: Pair<Int, BlockingQueue<ByteArray>>? = null
        private var prefetchEnd = -1L
        @Volatile private var closed = false

        private fun request(from: Long, to: Long): Pair<Int, BlockingQueue<ByteArray>> {
            val id = nextId.getAndIncrement()
            val q = ArrayBlockingQueue<ByteArray>(1)
            inflight[id] = q
            val msg = JSONObject().put("t", "range").put("id", id).put("sid", offer.sid).put("start", from).put("end", to)
            try {
                offer.client.send(msg.toString())
            } catch (e: IOException) {
                inflight.remove(id)
                throw IOException("source disconnected")
            }
            return id to q
        }

        private fun await(req: Pair<Int, BlockingQueue<ByteArray>>): ByteArray {
            try {
                val data = req.second.poll(20, TimeUnit.SECONDS)
                inflight.remove(req.first)
                if (data == null) throw IOException(if (closed) "closed" else "range timeout")
                return data
            } catch (e: InterruptedException) {
                inflight.remove(req.first)
                throw IOException("interrupted")
            }
        }

        private fun fill() {
            val chunkEnd = minOf(endInclusive, pos + CHUNK - 1)
            val req = prefetch?.takeIf { prefetchEnd == chunkEnd } ?: request(pos, chunkEnd)
            prefetch = null
            val data = await(req)
            buf = data
            bufOff = 0
            val nextStart = pos + data.size
            if (!closed && nextStart <= endInclusive) {
                val nextEnd = minOf(endInclusive, nextStart + CHUNK - 1)
                prefetch = request(nextStart, nextEnd)
                prefetchEnd = nextEnd
            }
        }

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("closed")
            if (len == 0) return 0
            if (pos > endInclusive) return -1
            val cur = buf
            if (cur == null || bufOff >= cur.size) fill()
            val c = buf ?: return -1
            if (c.isEmpty()) return -1
            val n = minOf(len, c.size - bufOff)
            System.arraycopy(c, bufOff, b, off, n)
            bufOff += n
            pos += n
            return n
        }

        override fun available(): Int = buf?.let { it.size - bufOff } ?: 0

        override fun close() {
            closed = true
            prefetch?.let { inflight.remove(it.first) }
            prefetch = null
        }
    }

    // ---------------------------------------------------------------- WebSocket

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val ok = handshake.uri == "/ws" && handshake.parameters["k"]?.firstOrNull() == key
        return Client(handshake, ok)
    }

    inner class Client(handshake: IHTTPSession, private val authorised: Boolean) : WebSocket(handshake) {
        val ws: WebSocket get() = this
        private val lock = Any()

        override fun send(text: String) { synchronized(lock) { super.send(text) } }

        override fun onOpen() {
            if (!authorised) {
                runCatching { close(fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "bad key", false) }
                return
            }
            clients.add(this)
            runCatching { send(JSONObject().put("t", "hello").put("name", "Lean TV").toString()) }
        }

        override fun onClose(code: fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            clients.remove(this)
            offers.values.removeAll { it.client === this }
        }

        override fun onMessage(message: fi.iki.elonen.NanoWSD.WebSocketFrame) {
            if (!authorised) return
            try {
                when (message.opCode) {
                    fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Text -> handleText(message.textPayload)
                    fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Binary -> handleBinary(message.binaryPayload)
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad message", e)
                runCatching { send(JSONObject().put("t", "error").put("msg", e.message ?: "bad message").toString()) }
            }
        }

        override fun onPong(pong: fi.iki.elonen.NanoWSD.WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            clients.remove(this)
        }

        private fun handleText(text: String) {
            if (text == "ping") { send("pong"); return }
            val j = JSONObject(text)
            when (j.optString("t")) {
                "offer" -> {
                    val sid = j.getString("sid")
                    offers[sid] = Offer(sid, j.optString("name", "Video"), j.getLong("size"), j.optString("mime", "video/mp4").ifEmpty { "video/mp4" }, this)
                    send(JSONObject().put("t", "offered").put("sid", sid).toString())
                }
                "play" -> {
                    val offer = offers[j.getString("sid")] ?: throw IllegalArgumentException("file not offered")
                    val url = "http://127.0.0.1:$listeningPort/stream/${offer.sid}"
                    startPlayer(url, offer.name)
                }
                "link" -> {
                    val url = j.getString("url").trim()
                    val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: ""
                    if (host.contains("youtube.com") || host == "youtu.be" || host.endsWith(".youtu.be")) openYouTube(url)
                    else startPlayer(url, j.optString("title", url))
                }
                "pause" -> player { it.pause() }
                "resume" -> player { it.resume() }
                "seek" -> { val ms = j.getLong("ms"); player { it.seekTo(ms) } }
                "volume" -> { val v = j.getDouble("v").toFloat(); player { it.setVolume(v) } }
                "stop" -> player { it.stopAndFinish() }
                "photo" -> pendingPhoto = j.getString("id") to j.optString("mime", "image/jpeg")
                "slide" -> {
                    val i = j.getInt("i")
                    PhotoStore.index = i
                    val p = RemotePlayerActivity.current()
                    if (p != null && p.isSlides) main.post { p.showSlide(i) } else startSlides(i)
                }
                "slideshow" -> {
                    val on = j.optBoolean("on", true)
                    val secs = j.optInt("secs", 8).coerceIn(2, 120)
                    PhotoStore.autoOn = on
                    PhotoStore.autoSecs = secs
                    val p = RemotePlayerActivity.current()
                    if (p != null && p.isSlides) main.post { p.applySlideshow() } else if (on) startSlides(PhotoStore.index)
                }
                "clearphotos" -> PhotoStore.clear()
            }
        }

        private fun handleBinary(payload: ByteArray) {
            if (payload.size < 8) return
            val bb = ByteBuffer.wrap(payload, 0, 8)
            val id = bb.int
            val data = payload.copyOfRange(8, payload.size)
            if (id == 0) {
                val pending = pendingPhoto ?: return
                pendingPhoto = null
                val index = PhotoStore.add(pending.first, pending.second, data)
                send(JSONObject().put("t", "photook").put("id", pending.first).put("i", index).put("count", PhotoStore.count()).toString())
                return
            }
            inflight[id]?.offer(data)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun player(block: (RemotePlayerActivity) -> Unit) {
        val p = RemotePlayerActivity.current() ?: return
        main.post { block(p) }
    }

    private fun startPlayer(url: String, title: String) {
        val i = Intent(ctx, RemotePlayerActivity::class.java)
            .putExtra(RemotePlayerActivity.EXTRA_URI, url)
            .putExtra(RemotePlayerActivity.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        main.post { ctx.startActivity(i) }
    }

    private fun startSlides(index: Int) {
        val i = Intent(ctx, RemotePlayerActivity::class.java)
            .putExtra(RemotePlayerActivity.EXTRA_MODE, RemotePlayerActivity.MODE_SLIDES)
            .putExtra(RemotePlayerActivity.EXTRA_INDEX, index)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        main.post { ctx.startActivity(i) }
    }

    private fun openYouTube(url: String) {
        main.post {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val withPkg = Intent(i).setPackage("com.google.android.youtube.tv")
            try { ctx.startActivity(withPkg) } catch (e: Exception) {
                try { ctx.startActivity(i) } catch (e2: Exception) { broadcast(JSONObject().put("t", "error").put("msg", "No app can open that link")) }
            }
        }
    }

    /** Sends a JSON message to every connected page. Safe from any thread. */
    fun broadcast(json: JSONObject) {
        val s = json.toString()
        for (c in clients) runCatching { c.send(s) }
    }

    companion object {
        private const val TAG = "DropServer"
        private const val CHUNK = 512 * 1024L
    }
}

/** Photos sent from the page for the slideshow, kept in memory only. */
object PhotoStore {
    class Photo(val id: String, val mime: String, val bytes: ByteArray)

    private val photos = ArrayList<Photo>()
    private const val MAX_BYTES = 40L * 1024 * 1024
    private var totalBytes = 0L
    @Volatile var index = 0
    @Volatile var autoOn = false
    @Volatile var autoSecs = 8

    @Synchronized fun add(id: String, mime: String, bytes: ByteArray): Int {
        photos.removeAll { it.id == id }.also { if (it) recount() }
        photos.add(Photo(id, mime, bytes))
        totalBytes += bytes.size
        while (totalBytes > MAX_BYTES && photos.size > 1) {
            val removed = photos.removeAt(0)
            totalBytes -= removed.bytes.size
            if (index > 0) index--
        }
        return photos.size - 1
    }

    @Synchronized private fun recount() { totalBytes = photos.sumOf { it.bytes.size.toLong() } }
    @Synchronized fun get(i: Int): Photo? = photos.getOrNull(i)
    @Synchronized fun count(): Int = photos.size
    @Synchronized fun clear() { photos.clear(); totalBytes = 0; index = 0 }
}
