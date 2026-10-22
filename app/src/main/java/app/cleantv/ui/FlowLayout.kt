package app.cleantv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Wraps fixed-size children into rows. Exposes the column count so move mode can step vertically. */
class FlowLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ViewGroup(ctx, attrs) {
    var hGap = (16 * resources.displayMetrics.density).toInt()
    var vGap = hGap
    var columns = 1
        private set

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0; var cols = 0; var firstRow = true
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val w = c.measuredWidth; val h = c.measuredHeight
            if (x > 0 && x + w > width) { x = 0; y += rowH + vGap; rowH = 0; firstRow = false }
            if (firstRow) cols++
            x += w + hGap
            rowH = max(rowH, h)
        }
        columns = max(1, cols)
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val w = c.measuredWidth; val h = c.measuredHeight
            if (x > 0 && x + w > width) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + w, paddingTop + y + h)
            x += w + hGap
            rowH = max(rowH, h)
        }
    }

    /** Moves the child at [from] to [to], keeping it focused. Returns the new index. */
    fun moveChild(from: Int, to: Int): Int {
        if (from == to || from !in 0 until childCount || to !in 0 until childCount) return from
        val v = getChildAt(from)
        removeViewAt(from)
        addView(v, to)
        v.requestFocus()
        return to
    }
}
