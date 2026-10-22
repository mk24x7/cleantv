package app.cleantv.saver

import android.service.dreams.DreamService
import app.cleantv.Prefs

/** System screensaver entry point. Renders the mode saved in Prefs. */
class LeanDreamService : DreamService() {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val mode = Prefs.saverMode(this)
        if (mode == "off") {
            finish()
            return
        }
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        setContentView(SaverView(this, mode))
    }
}
