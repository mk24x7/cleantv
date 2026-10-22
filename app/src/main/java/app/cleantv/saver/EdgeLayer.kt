package app.cleantv.saver

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.sin
import java.util.Random

class EdgeLayer(private val density: Float, private val firstVisitMs: Long, private val rnd: Random) {

    private enum class State { WAIT, ENTER, WAVE, SIT, WALK, EXIT }

    private var state = State.WAIT
    private var stateUntil = 0L
    private var nextVisitAt = SystemClock.elapsedRealtime() + firstVisitMs
    private var x = 0f
    private var targetX = 0f
    private var facing = 1
    private var actsLeft = 0
    private var w = 0f
    private var h = 0f
    private val speed = 70f * density
    private var lastTick = 0L
    private var captionAlpha = 0f

    private val fillA = Paint().apply { color = 0xFFDA7756.toInt(); style = Paint.Style.FILL }
    private val darkPaint = Paint().apply { color = 0xFF2B1A14.toInt(); style = Paint.Style.FILL }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C96A3.toInt(); textAlign = Paint.Align.RIGHT }
    private val rect = RectF()
    private val caption = String(android.util.Base64.decode("TWFkZSB3aXRoIOKdpO+4jyBieSBtazI0eDcgdXNpbmcgQ2xhdWRl", android.util.Base64.DEFAULT), Charsets.UTF_8)

    fun onSize(width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        captionPaint.textSize = 13f * density
    }

    val visible: Boolean get() = state != State.WAIT

    fun step() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTick == 0L) 0f else ((now - lastTick).coerceAtMost(100L)) / 1000f
        lastTick = now
        when (state) {
            State.WAIT -> if (now >= nextVisitAt) {
                facing = 1; x = -40f * density; targetX = w * (0.2f + rnd.nextFloat() * 0.6f)
                actsLeft = 2 + rnd.nextInt(3); state = State.ENTER
            }
            State.ENTER, State.WALK -> {
                val dir = if (targetX > x) 1 else -1
                facing = dir
                x += dir * speed * dt
                if ((dir == 1 && x >= targetX) || (dir == -1 && x <= targetX)) { x = targetX; pickAct(now) }
            }
            State.WAVE, State.SIT -> if (now >= stateUntil) pickAct(now)
            State.EXIT -> {
                x += facing * speed * dt
                if (x < -60f * density || x > w + 60f * density) {
                    state = State.WAIT
                    nextVisitAt = now + (4 + rnd.nextInt(6)) * 60_000L
                }
            }
        }
        val targetAlpha = if (visible) 1f else 0f
        captionAlpha += (targetAlpha - captionAlpha) * 0.08f
    }

    private fun pickAct(now: Long) {
        if (actsLeft <= 0) {
            state = State.EXIT
            facing = if (x < w / 2f) -1 else 1
            return
        }
        actsLeft--
        when (rnd.nextInt(3)) {
            0 -> { state = State.WAVE; stateUntil = now + 2500 }
            1 -> { state = State.SIT; stateUntil = now + 4000 + rnd.nextInt(3000) }
            else -> { state = State.WALK; targetX = w * (0.15f + rnd.nextFloat() * 0.7f) }
        }
    }

    fun draw(c: Canvas) {
        if (captionAlpha > 0.02f) {
            captionPaint.alpha = (captionAlpha * 255).toInt()
            c.drawText(caption, w - 16f * density, h - 10f * density, captionPaint)
        }
        if (!visible) return
        val t = SystemClock.elapsedRealtime() / 1000f
        val px = 4f * density
        val sitting = state == State.SIT
        val walking = state == State.ENTER || state == State.WALK || state == State.EXIT
        val ground = h - 26f * density
        val gait = if (walking) ((t * 7f).toInt() % 2) else 0
        val legH = if (sitting) 1 else 3
        val bodyBottom = ground - legH * px
        val bodyTop = bodyBottom - 8 * px
        val left = x - 6 * px

        fun block(col: Float, row: Float, cw: Float, rh: Float, p: Paint) {
            rect.set(left + col * px, bodyTop + row * px, left + (col + cw) * px, bodyTop + (row + rh) * px)
            c.drawRect(rect, p)
        }
        block(1f, 0f, 10f, 8f, fillA)
        block(0f, 1f, 12f, 6f, fillA)
        val waveUp = if (state == State.WAVE && sin(t * 10f) > 0f) 2f else 0f
        block(-1.5f, 3f - waveUp, 1.5f, 2f, fillA)
        block(12f, 3f, 1.5f, 2f, fillA)
        val blink = (t % 3.7f) < 0.12f
        block(3f, 2f, 1.2f, if (blink) 0.4f else 2f, darkPaint)
        block(7.8f, 2f, 1.2f, if (blink) 0.4f else 2f, darkPaint)
        val legs = floatArrayOf(1f, 4f, 7f, 10f)
        for ((i, lx) in legs.withIndex()) {
            val lift = if (walking && (i % 2 == gait)) 1f else 0f
            block(lx, 8f, 1.3f, legH - lift, fillA)
        }
    }
}
