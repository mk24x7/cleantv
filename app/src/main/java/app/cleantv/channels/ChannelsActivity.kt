package app.cleantv.channels

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.cleantv.Prefs
import app.cleantv.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live channels: a channel list on the left in the launcher style, video on a SurfaceView behind it.
 * HLS channels play with the framework MediaPlayer; YouTube channels hand off to the YouTube TV app.
 */
class ChannelsActivity : Activity() {

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, ChannelsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private const val BANNER_MS = 3000L
        private const val COLOR_PANEL = 0xFF0A0C0F.toInt()
        private const val COLOR_ROW_FOCUS = 0xFF171C22.toInt()
        private const val COLOR_TEXT = 0xFFEEF1F4.toInt()
        private const val COLOR_MUTED = 0xFF8C96A3.toInt()
        private const val COLOR_ERROR_BG = 0xE60F1317.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var surface: SurfaceView
    private lateinit var panel: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var banner: TextView
    private lateinit var error: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var empty: TextView

    private var channels: List<Channel> = emptyList()
    private var current: Channel? = null
    private var player: MediaPlayer? = null
    private var surfaceReady = false
    private var pendingTune: Channel? = null
    private var listVisible = true
    private var resolveSeq = 0

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        setContentView(buildUi())
        channels = ChannelStore.list(this)
        populateRows()
    }

    override fun onResume() {
        super.onResume()
        val target = current ?: ChannelStore.find(this, Prefs.lastChannelId(this))
        if (!listVisible && target != null && target.kind == ChannelKind.HLS) {
            tune(target)
        } else {
            showList(focusChannel = target)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    // ---- UI ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                player?.setDisplay(holder)
                pendingTune?.let { pendingTune = null; tune(it) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
        })
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PANEL)
            setPadding(dp(40), dp(30), 0, dp(20))
            clipChildren = false
        }
        val header = TextView(this).apply {
            text = getString(R.string.channels)
            setTextColor(COLOR_MUTED)
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
        }
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        empty = TextView(this).apply {
            text = getString(R.string.channels_empty)
            setTextColor(COLOR_MUTED)
            textSize = 16f
            visibility = View.GONE
        }
        panel.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll.addView(rows, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(panel, FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT))

        banner = TextView(this).apply {
            setTextColor(COLOR_TEXT)
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { setColor(COLOR_ERROR_BG); cornerRadius = dp(8).toFloat() }
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(40), 0, 0, dp(30))
        })

        error = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
            background = GradientDrawable().apply { setColor(COLOR_ERROR_BG); cornerRadius = dp(10).toFloat() }
            visibility = View.GONE
        }
        errorTitle = TextView(this).apply { setTextColor(COLOR_TEXT); textSize = 20f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val errorHint = TextView(this).apply { text = getString(R.string.channel_error_hint); setTextColor(COLOR_MUTED); textSize = 14f; setPadding(0, dp(6), 0, 0) }
        error.addView(errorTitle)
        error.addView(errorHint)
        root.addView(error, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })

        return root
    }

    private fun populateRows() {
        rows.removeAllViews()
        empty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        for (ch in channels) rows.addView(makeRow(ch), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
    }

    private fun makeRow(ch: Channel): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            tag = ch
            setPadding(0, 0, dp(16), 0)
        }
        val bar = View(this).apply { setBackgroundColor(COLOR_TEXT); visibility = View.INVISIBLE }
        row.addView(bar, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
        val number = TextView(this).apply {
            text = ch.number.toString()
            setTextColor(COLOR_MUTED)
            textSize = 14f
            setPadding(dp(12), 0, 0, 0)
        }
        row.addView(number, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        val name = TextView(this).apply {
            text = ch.name
            setTextColor(COLOR_TEXT)
            textSize = 20f
            maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val group = TextView(this).apply {
            text = ch.group
            setTextColor(COLOR_MUTED)
            textSize = 14f
            gravity = Gravity.END
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) COLOR_ROW_FOCUS else Color.TRANSPARENT)
            bar.visibility = if (has) View.VISIBLE else View.INVISIBLE
        }
        row.setOnClickListener { tune(ch) }
        return row
    }

    private fun showList(focusChannel: Channel? = null) {
        listVisible = true
        panel.visibility = View.VISIBLE
        error.visibility = View.GONE
        val target = focusChannel ?: current ?: channels.firstOrNull()
        val row = rowFor(target) ?: rows.getChildAt(0)
        row?.requestFocus()
    }

    private fun hideList() {
        listVisible = false
        panel.visibility = View.GONE
    }

    private fun rowFor(ch: Channel?): View? {
        if (ch == null) return null
        for (i in 0 until rows.childCount) {
            val v = rows.getChildAt(i)
            if ((v.tag as? Channel)?.id == ch.id) return v
        }
        return null
    }

    private fun showBanner(ch: Channel) {
        banner.text = getString(R.string.channel_banner_format, ch.number, ch.name)
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, BANNER_MS)
    }

    private fun showError(text: String) {
        errorTitle.text = text
        error.visibility = View.VISIBLE
    }

    // ---- Playback ----

    private fun tune(ch: Channel) {
        current = ch
        Prefs.setLastChannelId(this, ch.id)
        error.visibility = View.GONE
        when (ch.kind) {
            ChannelKind.YOUTUBE -> openYoutube(ch)
            ChannelKind.HLS -> {
                if (!surfaceReady) { pendingTune = ch; return }
                hideList()
                showBanner(ch)
                playHls(ch)
            }
        }
    }

    private fun playHls(ch: Channel) {
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            mp.setDisplay(surface.holder)
            mp.setScreenOnWhilePlaying(true)
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ ->
                showError(getString(R.string.channel_error, ch.name))
                true
            }
            mp.setOnVideoSizeChangedListener { _, w, h -> fitSurface(w, h) }
            mp.setDataSource(this, Uri.parse(ch.url))
            mp.prepareAsync()
        } catch (e: Exception) {
            showError(getString(R.string.channel_error, ch.name))
        }
    }

    private fun fitSurface(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val parent = surface.parent as View
        val pw = parent.width
        val ph = parent.height
        if (pw == 0 || ph == 0) return
        val scale = minOf(pw.toFloat() / w, ph.toFloat() / h)
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        lp.width = (w * scale).toInt()
        lp.height = (h * scale).toInt()
        lp.gravity = Gravity.CENTER
        surface.layoutParams = lp
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        player = null
    }

    private fun openYoutube(ch: Channel) {
        val id = ch.youtubeId
        if (id != null) { launchYoutube(id); return }
        val liveUrl = ch.liveUrl ?: run { showError(getString(R.string.channel_error, ch.name)); return }
        val seq = ++resolveSeq
        banner.text = getString(R.string.channel_loading, ch.name)
        banner.visibility = View.VISIBLE
        Thread {
            val resolved = resolveLiveVideoId(liveUrl)
            handler.post {
                if (seq != resolveSeq || isFinishing) return@post
                banner.visibility = View.GONE
                if (resolved != null) launchYoutube(resolved)
                else showError(getString(R.string.channel_youtube_resolve_failed, ch.name))
            }
        }.start()
    }

    /** Fetches a broadcaster's YouTube live page and returns the current live video id, or null. */
    private fun resolveLiveVideoId(liveUrl: String): String? {
        return try {
            val conn = URL(liveUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept-Language", "en")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val canonical = Regex("""rel="canonical" href="https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})""").find(body)
                ?: return null
            val live = body.contains("\"isLiveNow\":true") || body.contains("\"isLive\":true")
            if (live) canonical.groupValues[1] else null
        } catch (e: Exception) {
            null
        }
    }

    private fun launchYoutube(videoId: String) {
        val uri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube.tv")
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: ActivityNotFoundException) {
                showError(getString(R.string.channel_error, current?.name ?: ""))
                return
            }
        }
        finish()
    }

    private fun step(delta: Int) {
        if (channels.isEmpty()) return
        val idx = channels.indexOfFirst { it.id == current?.id }
        val next = if (idx < 0) 0 else ((idx + delta) % channels.size + channels.size) % channels.size
        tune(channels[next])
    }

    // ---- Keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (error.visibility == View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { current?.let { tune(it) }; return true }
                KeyEvent.KEYCODE_BACK -> { showList(); return true }
            }
        }
        if (listVisible) {
            when (code) {
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (player?.isPlaying == true) { hideList(); return true } }
            }
            return super.dispatchKeyEvent(event)
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { step(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { showList(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
