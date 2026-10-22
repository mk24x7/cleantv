package app.cleantv.sendtotv

import android.content.Context
import android.util.Log
import app.cleantv.Prefs
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Facade for the Send to TV feature. The launcher calls [start] when its screen is visible
 * and [stop] when it is not, except while [RemotePlayerActivity] is running.
 */
object SendToTv {
    const val PORT = 8765
    private const val TAG = "SendToTv"

    @Volatile private var server: DropServer? = null

    @Synchronized
    fun start(ctx: Context) {
        if (server?.isAlive == true) return
        val app = ctx.applicationContext
        var key = Prefs.sessionKey(app)
        if (key == null || key.length != 6) {
            val bytes = ByteArray(3).also { SecureRandom().nextBytes(it) }
            key = bytes.joinToString("") { "%02x".format(it) }
            Prefs.setSessionKey(app, key)
        }
        val s = DropServer(app, PORT, key)
        try {
            s.start(60_000, true)
            server = s
            Log.i(TAG, "server started on $PORT")
        } catch (e: Exception) {
            Log.w(TAG, "could not start server", e)
        }
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
    }

    fun isRunning(): Boolean = server?.isAlive == true

    /** New key on every launcher session so a scanned code stops working once the TV restarts. */
    fun rotateKey(ctx: Context) {
        Prefs.setSessionKey(ctx.applicationContext, null)
    }

    fun url(ctx: Context): String? {
        val key = Prefs.sessionKey(ctx.applicationContext) ?: return null
        val ip = lanAddress() ?: return null
        return "http://$ip:$PORT/?k=$key"
    }

    internal fun current(): DropServer? = server

    fun lanAddress(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
        val ordered = ifaces.sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
        for (iface in ordered) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) return addr.hostAddress
            }
        }
        return null
    }
}
