package app.cleantv.sendtotv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Plays a stream URL (video mode) or shows photos from [PhotoStore] (slides mode).
 * Controlled by the remote and by the Send to TV page through [DropServer].
 * Manifest: launchMode singleTask, theme Theme.LeanTV, screenOrientation landscape.
 */
class RemotePlayerActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var video: VideoView
    private lateinit var slideA: ImageView
    private lateinit var slideB: ImageView
    private lateinit var bar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var audio: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var title: String = ""
    private var prepared = false
    private var slideFront = true
    private var currentSlide = -1
    var isSlides = false
        private set

    private val ticker = object : Runnable {
        override fun run() {
            SendToTv.current()?.broadcast(stateJson())
            handler.postDelayed(this, 1000)
        }
    }
    private val hideBar = Runnable { bar.visibility = View.GONE }
    private val autoAdvance = object : Runnable {
        override fun run() {
            if (isSlides && PhotoStore.autoOn && PhotoStore.count() > 0) {
                showSlide((currentSlide + 1) % PhotoStore.count())
                handler.postDelayed(this, PhotoStore.autoSecs * 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        buildUi()
        ref = WeakReference(this)
        handleIntent()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        slideA = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        slideB = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        root.addView(slideA, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(slideB, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            val pad = dp(14)
            setPadding(dp(24), pad, dp(24), pad)
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleView = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 1 }
        timeView = TextView(this).apply { setTextColor(0xFF8C96A3.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        row.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(timeView)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressDrawable.setTint(0xFF34C759.toInt())
        }
        bar.addView(row)
        bar.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(8) })
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        video.setOnPreparedListener { mp ->
            prepared = true
            mp.start()
            showBar()
            handler.removeCallbacks(ticker); handler.post(ticker)
        }
        video.setOnCompletionListener { SendToTv.current()?.broadcast(stateJson()); finish() }
        video.setOnErrorListener { _, what, extra ->
            SendToTv.current()?.broadcast(JSONObject().put("t", "error").put("msg", "Playback failed ($what/$extra)"))
            Toast.makeText(this, getString(app.cleantv.R.string.stt_play_failed), Toast.LENGTH_LONG).show()
            handler.postDelayed({ finish() }, 1500)
            true
        }
    }

    private fun handleIntent() {
        val mode = intent.getStringExtra(EXTRA_MODE)
        handler.removeCallbacks(autoAdvance)
        if (mode == MODE_SLIDES) {
            isSlides = true
            prepared = false
            video.stopPlayback(); video.visibility = View.GONE
            title = getString(app.cleantv.R.string.stt_slideshow)
            showSlide(intent.getIntExtra(EXTRA_INDEX, PhotoStore.index))
            applySlideshow()
            handler.removeCallbacks(ticker); handler.post(ticker)
        } else {
            isSlides = false
            slideA.visibility = View.GONE; slideB.visibility = View.GONE
            slideA.setImageDrawable(null); slideB.setImageDrawable(null)
            val uri = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
            title = intent.getStringExtra(EXTRA_TITLE) ?: uri
            titleView.text = title
            prepared = false
            video.visibility = View.VISIBLE
            video.stopPlayback()
            video.setVideoURI(Uri.parse(uri))
            video.requestFocus()
        }
    }

    // ------------------------------------------------------------ controls

    fun pause() { if (!isSlides && prepared && video.isPlaying) { video.pause(); showBar() } }
    fun resume() { if (!isSlides && prepared && !video.isPlaying) { video.start(); showBar() } }
    fun seekTo(ms: Long) { if (!isSlides && prepared) { video.seekTo(ms.toInt()); showBar() } }
    fun setVolume(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).roundToInt(), 0)
    }
    fun stopAndFinish() { if (!isSlides) video.stopPlayback(); finish() }

    fun applySlideshow() {
        handler.removeCallbacks(autoAdvance)
        if (PhotoStore.autoOn) handler.postDelayed(autoAdvance, PhotoStore.autoSecs * 1000L)
    }

    fun showSlide(i: Int) {
        val count = PhotoStore.count()
        if (count == 0) { titleView.text = getString(app.cleantv.R.string.stt_no_photos); showBar(); return }
        val idx = ((i % count) + count) % count
        val photo = PhotoStore.get(idx) ?: return
        val bmp = decode(photo.bytes) ?: return
        currentSlide = idx
        PhotoStore.index = idx
        val incoming = if (slideFront) slideB else slideA
        val outgoing = if (slideFront) slideA else slideB
        slideFront = !slideFront
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        incoming.visibility = View.VISIBLE
        incoming.animate().alpha(1f).setDuration(300).start()
        outgoing.animate().alpha(0f).setDuration(300).withEndAction {
            outgoing.visibility = View.GONE
            outgoing.setImageDrawable(null)
        }.start()
        titleView.text = getString(app.cleantv.R.string.stt_photo_of, idx + 1, count)
        showBar()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1280 * 2 || bounds.outHeight / sample > 720 * 2) sample *= 2
        if (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 720) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    fun stateJson(): JSONObject {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        val j = JSONObject().put("t", "state").put("vol", vol).put("title", title).put("slides", isSlides)
        if (isSlides) {
            j.put("playing", PhotoStore.autoOn).put("pos", currentSlide).put("dur", PhotoStore.count())
        } else {
            val dur = if (prepared) video.duration.coerceAtLeast(0) else 0
            val pos = if (prepared) video.currentPosition.coerceAtLeast(0) else 0
            j.put("playing", prepared && video.isPlaying).put("pos", pos).put("dur", dur)
            if (prepared) {
                progress.progress = if (dur > 0) (pos * 1000L / dur).toInt() else 0
                timeView.text = "${fmt(pos)} / ${fmt(dur)}"
            }
        }
        return j
    }

    private fun showBar() {
        bar.visibility = View.VISIBLE
        handler.removeCallbacks(hideBar)
        handler.postDelayed(hideBar, 3000)
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isSlides) { PhotoStore.autoOn = !PhotoStore.autoOn; applySlideshow(); showBar() }
                else if (prepared) { if (video.isPlaying) pause() else resume() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (isSlides) { PhotoStore.autoOn = true; applySlideshow() } else resume(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (isSlides) { PhotoStore.autoOn = false; applySlideshow() } else pause(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (isSlides) { showSlide(currentSlide - 1); applySlideshow() } else if (prepared) seekTo((video.currentPosition - 10_000).coerceAtLeast(0).toLong())
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (isSlides) { showSlide(currentSlide + 1); applySlideshow() } else if (prepared) seekTo((video.currentPosition + 10_000).toLong())
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showBar(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_BACK -> { stopAndFinish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() { stopAndFinish() }

    override fun onPause() {
        super.onPause()
        if (!isSlides && prepared && video.isPlaying) video.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!isSlides) video.stopPlayback()
        if (ref?.get() === this) ref = null
        SendToTv.current()?.broadcast(JSONObject().put("t", "state").put("playing", false).put("pos", 0).put("dur", 0).put("title", "").put("ended", true))
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MODE = "mode"
        const val EXTRA_INDEX = "index"
        const val MODE_SLIDES = "slides"

        @Volatile private var ref: WeakReference<RemotePlayerActivity>? = null
        fun current(): RemotePlayerActivity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }
}
