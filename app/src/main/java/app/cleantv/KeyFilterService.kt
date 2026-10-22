package app.cleantv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import app.cleantv.saver.SaverActivity

/**
 * Catches the remote's Mi button (KEYCODE_BUTTON_2, a system global key on this set that used to
 * start PatchWall) before the global key handler runs.
 * Short press: toggle between the launcher and what was playing. Long press: start the screensaver.
 * Receives no accessibility events; it only filters keys.
 */
class KeyFilterService : AccessibilityService() {

    private var longFired = false

    override fun onServiceConnected() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = 0
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.notificationTimeout = 100
        serviceInfo = info
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_2) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    longFired = false
                } else if (!longFired && event.eventTime - event.downTime >= LONG_PRESS_MS) {
                    longFired = true
                    startScreensaver()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (!longFired) toggleLauncher()
                longFired = false
            }
        }
        return true
    }

    private fun toggleLauncher() {
        val i = Intent(this, HomeActivity::class.java)
            .setAction(HomeActivity.ACTION_TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { startActivity(i) } catch (e: Exception) { }
    }

    private fun startScreensaver() {
        val mode = Prefs.saverMode(this).let { if (it == "off") "mystify" else it }
        try { SaverActivity.start(this, mode) } catch (e: Exception) { }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    companion object {
        private const val LONG_PRESS_MS = 600L
    }
}
