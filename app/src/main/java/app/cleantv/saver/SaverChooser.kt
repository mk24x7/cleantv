package app.cleantv.saver

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.cleantv.Prefs
import app.cleantv.R

/** D-pad screensaver picker and system screensaver registration. */
object SaverChooser {

    private val modes = listOf(
        Triple("mystify", R.string.saver_mystify, R.string.saver_mystify_sub),
        Triple("starfield", R.string.saver_starfield, R.string.saver_starfield_sub),
        Triple("pipes", R.string.saver_pipes, R.string.saver_pipes_sub),
        Triple("matrix", R.string.saver_matrix, R.string.saver_matrix_sub),
        Triple("off", R.string.saver_off, R.string.saver_off_sub)
    )

    fun show(activity: Activity, onPicked: (String) -> Unit) {
        val dialog = Dialog(activity, R.style.Theme_LeanTV_Dialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val d = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * d + 0.5f).toInt()

        val text = activity.getColor(R.color.text)
        val muted = activity.getColor(R.color.muted)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(20f), dp(22f), dp(16f))
            layoutParams = ViewGroup.LayoutParams(dp(420f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(TextView(activity).apply {
            setText(R.string.saver_title)
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        root.addView(TextView(activity).apply {
            setText(R.string.saver_subtitle)
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f); bottomMargin = dp(14f) }
        })

        var first: View? = null
        for ((mode, nameRes, subRes) in modes) {
            val row = buildRow(activity, dp(56f), dp(12f), nameRes, subRes, text, muted, d)
            row.setOnClickListener {
                dialog.dismiss()
                onPicked(mode)
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    row.performClick()
                    true
                } else false
            }
            root.addView(row)
            if (first == null) first = row
        }

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(dp(420f), ViewGroup.LayoutParams.WRAP_CONTENT)
        // Delay row: OK cycles through the choices and saves at once.
        val delays = intArrayOf(1, 2, 5, 10, 15)
        val delayRow = buildRow(activity, dp(56f), dp(12f), R.string.saver_delay, R.string.saver_delay_sub, text, muted, d)
        val delaySub = (delayRow as ViewGroup).getChildAt(1) as TextView
        fun renderDelay() { delaySub.text = activity.getString(R.string.saver_delay_value, Prefs.saverDelayMin(activity)) }
        renderDelay()
        val cycle = {
            val cur = Prefs.saverDelayMin(activity)
            val next = delays[(delays.indexOf(cur).coerceAtLeast(0) + 1) % delays.size]
            Prefs.setSaverDelayMin(activity, next)
            renderDelay()
        }
        delayRow.setOnClickListener { cycle() }
        delayRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) { cycle(); true } else false
        }
        (delayRow.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10f)
        root.addView(delayRow)
        dialog.show()
        first?.requestFocus()
    }

    private fun buildRow(
        ctx: Context, height: Int, padH: Int, nameRes: Int, subRes: Int,
        text: Int, muted: Int, d: Float
    ): View {
        val row = FrameLayout(ctx)
        row.isFocusable = true
        row.isFocusableInTouchMode = false
        row.isClickable = true
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        row.setPadding(padH, 0, padH, 0)

        val focusedBg = LayerDrawable(arrayOf(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * d
                setColor(ctx.getColor(R.color.tile_focus))
            },
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
            }
        )).apply {
            // second layer becomes a 4dp bar on the left
            setLayerInset(1, 0, 0, 0, 0)
            setLayerWidth(1, (4f * d).toInt())
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
        }

        val name = TextView(ctx).apply {
            setText(nameRes)
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        val sub = TextView(ctx).apply {
            setText(subRes)
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        }
        row.addView(name)
        row.addView(sub)
        row.setOnFocusChangeListener { _, hasFocus ->
            row.background = if (hasFocus) focusedBg else null
            sub.setTextColor(if (hasFocus) text else muted)
        }
        return row
    }

    /**
     * Registers (or disables) this app's dream as the system screensaver.
     * Needs WRITE_SECURE_SETTINGS, granted over adb; returns false if missing.
     */
    fun applySystemSaver(ctx: Context, mode: String): Boolean {
        return try {
            val cr = ctx.contentResolver
            if (mode == "off") {
                Settings.Secure.putInt(cr, "screensaver_enabled", 0)
            } else {
                val component = ComponentName(ctx, LeanDreamService::class.java).flattenToString()
                Settings.Secure.putString(cr, "screensaver_components", component)
                Settings.Secure.putInt(cr, "screensaver_enabled", 1)
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
