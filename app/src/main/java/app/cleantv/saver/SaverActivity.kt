package app.cleantv.saver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import app.cleantv.Prefs

/** Runs a screensaver on demand. Any key press exits. */
class SaverActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"

        fun start(ctx: Context, mode: String) {
            val i = Intent(ctx, SaverActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: Prefs.saverMode(this)
        if (mode == "off") {
            finish()
            return
        }
        window.setBackgroundDrawable(null)
        window.decorView.setBackgroundColor(Color.BLACK)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        setContentView(SaverView(this, mode))
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        finish()
    }
}
