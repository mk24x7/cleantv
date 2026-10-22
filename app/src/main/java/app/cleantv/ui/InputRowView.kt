package app.cleantv.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.cleantv.R
import app.cleantv.data.TvInput

/**
 * One row of the inputs panel on a strict grid: bar | numeral column | name and status | dot.
 * Expanded when something is plugged in, collapsed otherwise.
 */
open class PanelRow(ctx: Context) : LinearLayout(ctx) {
    protected val bar = View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.text)); visibility = View.INVISIBLE }
    protected val numeralCol = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }
    protected val numeral = TextView(ctx).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); includeFontPadding = false }
    protected val icon = ImageView(ctx).apply { visibility = View.GONE }
    protected val name = TextView(ctx).apply { setTextColor(ctx.getColor(R.color.text)); maxLines = 1 }
    protected val status = TextView(ctx).apply { setTextColor(ctx.getColor(R.color.muted)); textSize = 12f; maxLines = 1 }
    protected val dot = View(ctx)
    private val dotShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        addView(bar, LayoutParams(dp(4f), LayoutParams.MATCH_PARENT))
        numeralCol.addView(numeral)
        numeralCol.addView(icon, LayoutParams(dp(28f), dp(28f)))
        addView(numeralCol, LayoutParams(dp(82f), LayoutParams.MATCH_PARENT).apply { marginStart = dp(20f) })
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(name); col.addView(status)
        addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) })
        addView(dot, LayoutParams(dp(10f), dp(10f)).apply { marginEnd = dp(36f) })
        dot.background = dotShape
        onFocusChangeListener = OnFocusChangeListener { _, f -> applyFocus(f) }
        applyFocus(false)
    }

    protected open fun applyFocus(focused: Boolean) {
        setBackgroundColor(if (focused) context.getColor(R.color.tile) else 0)
        bar.visibility = if (focused) View.VISIBLE else View.INVISIBLE
    }

    protected fun setDot(on: Boolean, sizeDp: Float) {
        dotShape.setColor(context.getColor(if (on) R.color.green else R.color.led_off))
        dot.layoutParams = (dot.layoutParams as LayoutParams).apply { width = dp(sizeDp); height = dp(sizeDp) }
        dot.visibility = View.VISIBLE
    }
}

class InputRowView(ctx: Context) : PanelRow(ctx) {
    var input: TvInput? = null
        private set
    private var expanded = true

    fun bind(i: TvInput, expand: Boolean) {
        input = i
        tag = i.id
        expanded = expand
        numeral.text = i.number
        name.text = i.label
        contentDescription = i.label
        val h = if (expand) 84f else 48f
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(h))
        if (expand) {
            numeral.textSize = if (i.number.length > 1) 44f else 66f
            numeral.setTextColor(context.getColor(R.color.text))
            name.textSize = 20f
            name.setTypeface(name.typeface, Typeface.BOLD)
            status.visibility = View.VISIBLE
            status.text = context.getString(if (i.connected) R.string.connected else R.string.nothing_connected)
            status.setTextColor(context.getColor(if (i.connected) R.color.green else R.color.muted))
            setDot(i.connected, 10f)
        } else {
            numeral.textSize = if (i.number.length > 1) 20f else 27f
            numeral.setTextColor(context.getColor(R.color.numeral_dim))
            name.textSize = 15f
            name.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            status.visibility = View.GONE
            setDot(false, 7f)
        }
        applyFocus(isFocused)
    }

    override fun applyFocus(focused: Boolean) {
        super.applyFocus(focused)
        if (!expanded) {
            name.setTextColor(context.getColor(if (focused) R.color.text else R.color.text_dim))
            numeral.setTextColor(context.getColor(if (focused) R.color.text else R.color.numeral_dim))
        } else {
            name.setTextColor(context.getColor(R.color.text))
        }
    }
}

/** Left-panel entry with an icon instead of a numeral, such as Channels. */
class ActionRowView(ctx: Context) : PanelRow(ctx) {
    fun bind(iconRes: Int, title: String, sub: String) {
        numeral.visibility = View.GONE
        icon.visibility = View.VISIBLE
        icon.setImageResource(iconRes)
        (icon.layoutParams as LayoutParams).marginStart = dp(4f)
        name.text = title
        name.textSize = 17f
        name.setTypeface(name.typeface, Typeface.BOLD)
        status.text = sub
        status.visibility = View.VISIBLE
        dot.visibility = View.INVISIBLE
        contentDescription = title
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(64f)).apply { topMargin = dp(14f) }
    }
}
