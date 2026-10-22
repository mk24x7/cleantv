package app.cleantv.sendtotv

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.cleantv.R
import app.cleantv.ui.dp

/** Large code for phones plus the typed address and network name for laptops. Any key closes it. */
object SendToTvDialog {
    fun show(activity: Activity) {
        val url = SendToTv.url(activity) ?: return
        val d = Dialog(activity, R.style.Theme_LeanTV_Dialog)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36f), dp(28f), dp(36f), dp(24f))
            minimumWidth = dp(470f)
        }
        root.addView(TextView(activity).apply {
            text = context.getString(R.string.send_to_tv)
            setTextColor(context.getColor(R.color.text)); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(activity).apply {
            text = context.getString(R.string.send_to_tv_dialog_sub)
            setTextColor(context.getColor(R.color.text_dim)); textSize = 16f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = root.dp(4f) })
        val qr = QrView(activity).apply { text = url; quietModules = 2 }
        root.addView(qr, LinearLayout.LayoutParams(root.dp(230f), root.dp(230f)).apply { topMargin = root.dp(18f) })
        root.addView(TextView(activity).apply {
            text = context.getString(R.string.send_to_tv_manual)
            setTextColor(context.getColor(R.color.muted)); textSize = 15f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = root.dp(20f) })
        root.addView(TextView(activity).apply {
            text = url
            setTextColor(context.getColor(R.color.text)); textSize = 20f; typeface = Typeface.MONOSPACE; maxLines = 1
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = root.dp(4f) })
        val ssid = wifiName(activity)
        root.addView(TextView(activity).apply {
            text = if (ssid != null) context.getString(R.string.send_to_tv_network, ssid) else context.getString(R.string.send_to_tv_same_network)
            setTextColor(context.getColor(R.color.text_dim)); textSize = 16f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = root.dp(6f) })
        d.setContentView(root)
        d.setCanceledOnTouchOutside(true)
        d.show()
    }

    private fun wifiName(activity: Activity): String? = try {
        val wm = activity.applicationContext.getSystemService(WifiManager::class.java)
        val ssid = wm?.connectionInfo?.ssid?.trim('"')
        if (ssid.isNullOrEmpty() || ssid.contains("unknown", ignoreCase = true) || ssid == "0x") null else ssid
    } catch (e: Exception) { null }
}
