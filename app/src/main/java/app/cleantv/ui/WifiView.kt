package app.cleantv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import app.cleantv.R

/** Three-arc Wi-Fi glyph. level 0 means no connection, 1 to 3 fill the arcs from the dot outward. */
class WifiView(ctx: Context) : View(ctx) {
    var level = 0
        set(v) { field = v.coerceIn(0, 3); invalidate() }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.text); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.led_off); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val stroke = h * 0.11f
        on.strokeWidth = stroke; off.strokeWidth = stroke
        val cx = w / 2f; val cy = h * 0.92f
        // centre dot
        val dot = if (level >= 1) on else off
        dot.style = Paint.Style.FILL; c.drawCircle(cx, cy - stroke, stroke * 0.9f, dot); dot.style = Paint.Style.STROKE
        for (i in 1..2) {
            val r = h * (0.32f + 0.28f * i)
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, 225f, 90f, false, if (level >= i + 1) on else off)
        }
    }
}
