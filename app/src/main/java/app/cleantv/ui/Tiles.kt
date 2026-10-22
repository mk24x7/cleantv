package app.cleantv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.cleantv.R
import app.cleantv.data.TvApp

fun View.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Rounded focusable tile: scales up and shows a white ring on focus, a green ring while being moved. */
open class FocusTile(ctx: Context) : FrameLayout(ctx) {
    private val radius = dp(8f).toFloat()
    private val ringWhite = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(5f), ctx.getColor(R.color.text)); cornerRadius = radius }
    private val ringGreen = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(6f), ctx.getColor(R.color.green)); cornerRadius = radius }

    var moving = false
        set(v) { field = v; updateChrome() }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        background = GradientDrawable().apply { setColor(ctx.getColor(R.color.tile)); cornerRadius = radius }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radius) }
        }
        clipToOutline = true
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            val s = if (focused) 1.06f else 1f
            animate().scaleX(s).scaleY(s).setDuration(120).start()
            updateChrome()
        }
    }

    private fun updateChrome() {
        foreground = when {
            moving -> ringGreen
            isFocused -> ringWhite
            else -> null
        }
    }
}

/** 16:9 app tile showing the app's own banner, or its icon centred when it has no banner. */
class AppTileView(ctx: Context) : FocusTile(ctx) {
    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val label = TextView(ctx).apply {
        setTextColor(ctx.getColor(R.color.text)); textSize = 15f
        setPadding(dp(12f), 0, dp(12f), dp(8f))
        maxLines = 1
        visibility = View.GONE
    }
    var app: TvApp? = null
        private set

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
    }

    fun bind(a: TvApp) {
        app = a
        tag = a.pkg
        contentDescription = a.label
        val d: Drawable? = a.banner
        if (a.isBanner) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
            label.visibility = View.GONE
        } else {
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setPadding(dp(14f), dp(10f), dp(14f), dp(26f))
            label.text = a.label
            label.visibility = View.VISIBLE
        }
        image.setImageDrawable(d)
    }
}

/** Compact one-line tile: icon, title and a short subtitle. Used for storage and system entries. */
class PillView(ctx: Context) : FocusTile(ctx) {
    private val icon = ImageView(ctx)
    private val title = TextView(ctx).apply { setTextColor(ctx.getColor(R.color.text)); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1 }
    private val sub = TextView(ctx).apply { setTextColor(ctx.getColor(R.color.muted)); textSize = 12f; maxLines = 1 }

    init {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(10f), 0)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title); col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10f) })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(iconRes: Int, titleText: String, subText: String?) {
        icon.setImageResource(iconRes)
        title.text = titleText
        sub.text = subText.orEmpty()
        sub.visibility = if (subText.isNullOrEmpty()) View.GONE else View.VISIBLE
        contentDescription = titleText
    }
}
