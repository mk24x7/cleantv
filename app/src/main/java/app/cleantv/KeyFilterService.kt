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
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return handleVolume(event)
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

    // With a key filter installed the system no longer synthesises key repeats, so a held volume key
    // would only step once. Drive the volume here instead and repeat while the key stays down.
    private val volumeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var volumeDirection = 0
    private val volumeRepeat = object : Runnable {
        override fun run() {
            if (volumeDirection == 0) return
            adjustVolume(volumeDirection)
            volumeHandler.postDelayed(this, VOLUME_REPEAT_MS)
        }
    }

    private fun handleVolume(event: KeyEvent): Boolean {
        val dir = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                volumeDirection = dir
                adjustVolume(dir)
                volumeHandler.removeCallbacks(volumeRepeat)
                volumeHandler.postDelayed(volumeRepeat, VOLUME_FIRST_REPEAT_MS)
            }
            KeyEvent.ACTION_UP -> {
                volumeDirection = 0
                volumeHandler.removeCallbacks(volumeRepeat)
            }
        }
        return true
    }

    private fun adjustVolume(dir: Int) {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        try {
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (dir > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        } catch (e: SecurityException) { }
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
        private const val VOLUME_FIRST_REPEAT_MS = 350L
        private const val VOLUME_REPEAT_MS = 110L
    }
}
