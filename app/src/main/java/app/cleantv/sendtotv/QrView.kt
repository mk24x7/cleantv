package app.cleantv.sendtotv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Draws a QR code for [text] at whole-pixel module sizes so the modules stay crisp. */
class QrView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private var bitmap: Bitmap? = null
    private var modules: Array<BooleanArray>? = null
    var quietModules = 2

    var text: String? = null
        set(value) {
            if (field == value) return
            field = value
            modules = value?.let { runCatching { QrCode.encode(it) }.getOrNull() }
            bitmap = null
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        val m = modules ?: return
        val side = minOf(width, height)
        if (side <= 0) return
        var bmp = bitmap
        if (bmp == null) {
            val n = m.size + 2 * quietModules
            val modulePx = maxOf(1, side / n)
            val size = n * modulePx
            val pixels = IntArray(size * size) { Color.WHITE }
            for (r in m.indices) for (c in m.indices) if (m[r][c]) {
                val y0 = (r + quietModules) * modulePx
                val x0 = (c + quietModules) * modulePx
                for (y in y0 until y0 + modulePx) for (x in x0 until x0 + modulePx) pixels[y * size + x] = Color.BLACK
            }
            bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            bmp.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap = bmp
        }
        val left = (width - bmp.width) / 2f
        val top = (height - bmp.height) / 2f
        canvas.drawBitmap(bmp, left, top, paint)
    }
}
