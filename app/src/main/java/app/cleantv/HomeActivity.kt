package app.cleantv

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.cleantv.data.Apps
import app.cleantv.data.Inputs
import app.cleantv.data.Memory
import app.cleantv.data.Storage
import app.cleantv.data.TvApp
import app.cleantv.saver.SaverActivity
import app.cleantv.saver.SaverChooser
import app.cleantv.sendtotv.QrView
import app.cleantv.sendtotv.RemotePlayerActivity
import app.cleantv.sendtotv.SendToTv
import app.cleantv.sendtotv.SendToTvDialog
import android.graphics.drawable.GradientDrawable
import app.cleantv.ui.ActionRowView
import app.cleantv.ui.AppTileView
import app.cleantv.ui.FlowLayout
import app.cleantv.ui.InputRowView
import app.cleantv.ui.PillView
import app.cleantv.ui.WifiView
import android.net.wifi.WifiManager
import android.widget.ImageView
import app.cleantv.ui.dp
import kotlin.math.abs

class HomeActivity : Activity() {

    companion object {
        const val ACTION_SHOW = "app.cleantv.SHOW"
        const val ACTION_TOGGLE = "app.cleantv.TOGGLE"
        private const val BOOT_WINDOW_MS = 10 * 60_000L
        private const val SWEEP_DELAY_MS = 30_000L
    }

    private lateinit var inputs: Inputs
    private lateinit var leftPanel: LinearLayout
    private lateinit var inputsContainer: LinearLayout
    private lateinit var rightScroll: ScrollView
    private lateinit var appsGrid: FlowLayout
    private lateinit var toolsHeader: TextView
    private lateinit var toolsRow: FlowLayout
    private lateinit var storageHeader: TextView
    private lateinit var storageRow: LinearLayout
    private lateinit var systemRow: LinearLayout
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())
    /** Kills apps left in the background 30 s after the launcher was shown. A quick return to the app cancels it. */
    private val sweepHandler = Handler(Looper.getMainLooper())
    private val sweep = Runnable { Memory.freeUp(this) }
    /** Launcher-only screensaver: starts after a minute without a key press while this screen is showing. */
    private val idle = Runnable {
        val mode = Prefs.saverMode(this)
        if (visible && mode != "off") SaverActivity.start(this, mode)
    }

    private var movingTile: AppTileView? = null
    private var orderBeforeMove: List<String> = emptyList()
    private var bootTuneAttempts = 0
    private var memoryPill: PillView? = null
    private var visible = false
    private lateinit var qrBlock: View
    private lateinit var qr: QrView
    private lateinit var indicators: LinearLayout
    private var wifiView: WifiView? = null
    private var usbMark: ImageView? = null

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = renderInputs()
        override fun onInputAdded(inputId: String) = renderInputs()
        override fun onInputRemoved(inputId: String) = renderInputs()
        override fun onInputUpdated(inputId: String) = renderInputs()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = renderApps()
    }
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = renderStorage()
    }
    private val memoryTick = object : Runnable {
        override fun run() {
            updateMemoryPill()
            updateIndicators()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        inputs = Inputs(this)
        leftPanel = findViewById(R.id.leftPanel)
        inputsContainer = findViewById(R.id.inputsContainer)
        rightScroll = findViewById(R.id.rightScroll)
        appsGrid = findViewById(R.id.appsGrid)
        toolsHeader = findViewById(R.id.toolsHeader)
        toolsRow = findViewById(R.id.toolsRow)
        storageHeader = findViewById(R.id.storageHeader)
        storageRow = findViewById(R.id.storageRow)
        systemRow = findViewById(R.id.systemRow)
        hint = findViewById(R.id.hint)
        qrBlock = findViewById(R.id.qrBlock)
        qr = findViewById(R.id.qr)
        indicators = findViewById(R.id.indicators)
        val qrFrame: View = findViewById(R.id.qrFrame)
        qrFrame.background = GradientDrawable().apply { setColor(getColor(R.color.text)); cornerRadius = qrFrame.dp(6f).toFloat() }
        qrBlock.setOnFocusChangeListener { _, f ->
            val sc = if (f) 1.06f else 1f
            qrFrame.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
            qrFrame.foreground = if (f) GradientDrawable().apply { setColor(0); setStroke(qrFrame.dp(4f), getColor(R.color.green)); cornerRadius = qrFrame.dp(6f).toFloat() } else null
        }
        qrBlock.setOnClickListener { SendToTvDialog.show(this) }
        buildIndicators()
        renderAll()
        handleHomeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_TOGGLE && visible) {
            // Mi key pressed while the launcher is showing: go back to what was playing.
            handler.post { moveTaskToBack(true) }
            return
        }
        handleHomeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        sweepHandler.removeCallbacks(sweep)
        sweepHandler.postDelayed(sweep, SWEEP_DELAY_MS)
        armIdle()
    }

    private fun armIdle() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, Prefs.saverDelayMin(this) * 60_000L)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        handler.removeCallbacks(idle)
    }

    override fun onStart() {
        super.onStart()
        inputs.register(inputCallback, handler)
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerReceiver(mediaReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        })
        renderAll()
        startSendToTv()
        ensureKeyFilterEnabled()
        handler.post(memoryTick)
        if (currentFocus == null) inputsContainer.getChildAt(0)?.requestFocus()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(memoryTick)
        handler.removeCallbacksAndMessages(null)
        inputs.unregister(inputCallback)
        try { unregisterReceiver(packageReceiver) } catch (e: IllegalArgumentException) { }
        try { unregisterReceiver(mediaReceiver) } catch (e: IllegalArgumentException) { }
        if (movingTile != null) exitMoveMode(cancel = true)
        if (RemotePlayerActivity.current() == null) SendToTv.stop()
    }

    override fun onBackPressed() {
        if (movingTile != null) { exitMoveMode(cancel = true); return }
        moveTaskToBack(true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) armIdle()
        val tile = movingTile
        if (tile != null && event.action == KeyEvent.ACTION_DOWN) {
            val i = appsGrid.indexOfChild(tile)
            val cols = appsGrid.columns
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> appsGrid.moveChild(i, i - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> appsGrid.moveChild(i, i + 1)
                KeyEvent.KEYCODE_DPAD_UP -> appsGrid.moveChild(i, i - cols)
                KeyEvent.KEYCODE_DPAD_DOWN -> appsGrid.moveChild(i, i + cols)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> exitMoveMode(cancel = false)
                KeyEvent.KEYCODE_BACK -> exitMoveMode(cancel = true)
            }
            return true
        }
        if (tile != null) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- boot to the default input ----

    private fun handleHomeIntent(intent: Intent?) {
        if (intent == null) return
        val isHome = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        if (isHome && isFreshBoot()) {
            Prefs.setLastBootHandled(this, bootTime())
            bootTuneAttempts = 0
            handler.postDelayed(::tryBootTune, 1500)
        }
    }

    private fun bootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun isFreshBoot(): Boolean =
        abs(bootTime() - Prefs.lastBootHandled(this)) > 10_000 && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS

    private fun tryBootTune() {
        val list = inputs.list()
        val wanted = Prefs.defaultInputId(this)
        val target = list.firstOrNull { it.id == wanted }
            ?: list.firstOrNull { it.isHdmi && it.connected }
            ?: list.firstOrNull { it.isHdmi }
        if (target == null) {
            if (++bootTuneAttempts < 15) handler.postDelayed(::tryBootTune, 2000)
            return
        }
        Inputs.tune(this, target.id)
    }

    // ---- rendering ----

    private fun renderAll() {
        renderInputs()
        renderApps()
        renderStorage()
        renderSystem()
    }

    private fun renderInputs() {
        val focusedId = (currentFocus as? InputRowView)?.input?.id
        inputsContainer.removeAllViews()
        val defaultId = Prefs.defaultInputId(this)
        for (input in inputs.list()) {
            val row = InputRowView(this)
            row.bind(input, expand = input.connected || input.id == defaultId)
            row.setOnClickListener { Inputs.tune(this, input.id) }
            row.setOnLongClickListener {
                Prefs.setDefaultInputId(this, input.id)
                Toast.makeText(this, getString(R.string.default_input_set, input.label), Toast.LENGTH_SHORT).show()
                renderInputs()
                true
            }
            inputsContainer.addView(row)
            if (input.id == focusedId) row.requestFocus()
        }
        val channels = ActionRowView(this)
        channels.bind(R.drawable.ic_channels, getString(R.string.channels_row), getString(R.string.channels_row_sub))
        channels.setOnClickListener { openChannels() }
        inputsContainer.addView(channels)
    }

    private fun renderApps() {
        val focusedPkg = (currentFocus as? AppTileView)?.app?.pkg
        appsGrid.removeAllViews()
        toolsRow.removeAllViews()
        val w = appsGrid.dp(160f); val h = appsGrid.dp(90f)
        val all = Apps.list(this)
        val tools = all.filter { Apps.isTool(it.pkg) }
        toolsHeader.visibility = if (tools.isEmpty()) View.GONE else View.VISIBLE
        toolsRow.visibility = toolsHeader.visibility
        for (app in tools) {
            val pill = PillView(this)
            pill.bind(app.icon, app.label, null)
            pill.layoutParams = ViewGroup.LayoutParams(toolsRow.dp(164f), toolsRow.dp(52f))
            pill.setOnClickListener { if (!Apps.launch(this, app)) Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show() }
            pill.setOnLongClickListener { showToolMenu(pill, app); true }
            toolsRow.addView(pill)
        }
        for (app in all.filter { !Apps.isTool(it.pkg) }) {
            val tile = AppTileView(this)
            tile.bind(app)
            tile.layoutParams = ViewGroup.LayoutParams(w, h)
            tile.setOnClickListener { if (!Apps.launch(this, app)) Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show() }
            tile.setOnLongClickListener { showAppMenu(tile, app); true }
            appsGrid.addView(tile)
            if (app.pkg == focusedPkg) tile.requestFocus()
        }
    }

    private fun renderStorage() {
        val vols = Storage.removable(this)
        storageRow.removeAllViews()
        val show = vols.isNotEmpty()
        storageHeader.visibility = if (show) View.VISIBLE else View.GONE
        storageRow.visibility = if (show) View.VISIBLE else View.GONE
        for (v in vols) {
            val pill = PillView(this)
            pill.bind(R.drawable.ic_usb, v.label, v.freeText)
            pill.layoutParams = pillParams()
            pill.setOnClickListener { Storage.open(this, v) }
            storageRow.addView(pill)
        }
    }

    private fun renderSystem() {
        systemRow.removeAllViews()
        val settings = PillView(this).apply {
            bind(R.drawable.ic_settings, getString(R.string.settings), getString(R.string.settings_sub))
            setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        val saver = PillView(this).apply {
            bind(R.drawable.ic_saver, getString(R.string.screensaver), getString(R.string.screensaver_sub))
            setOnClickListener {
                SaverChooser.show(this@HomeActivity) { mode ->
                    Prefs.setSaverMode(this@HomeActivity, mode)
                    if (mode != "off") SaverActivity.start(this@HomeActivity, mode)
                }
            }
        }
        val memory = PillView(this).apply {
            bind(R.drawable.ic_memory, getString(R.string.memory), "")
            setOnClickListener {
                val n = Memory.freeUp(this@HomeActivity)
                handler.postDelayed({ updateMemoryPill(); Toast.makeText(this@HomeActivity, getString(R.string.memory_freed, n), Toast.LENGTH_SHORT).show() }, 600)
            }
            setOnLongClickListener { Memory.openBoost(this@HomeActivity); true }
        }
        memoryPill = memory
        updateMemoryPill()
        for (p in listOf(settings, saver, memory)) { p.layoutParams = pillParams(); systemRow.addView(p) }
        if (Prefs.hiddenApps(this).isNotEmpty()) {
            val hiddenPill = PillView(this).apply {
                bind(R.drawable.ic_eye, getString(R.string.hidden_apps), getString(R.string.hidden_apps_sub))
                setOnClickListener { showHiddenApps() }
                layoutParams = pillParams()
            }
            systemRow.addView(hiddenPill)
        }
    }

    private fun buildIndicators() {
        indicators.removeAllViews()
        val usb = ImageView(this).apply { setImageResource(R.drawable.ic_usb); visibility = View.GONE }
        indicators.addView(usb, LinearLayout.LayoutParams(indicators.dp(20f), indicators.dp(20f)).apply { marginEnd = indicators.dp(14f) })
        val wifi = WifiView(this)
        indicators.addView(wifi, LinearLayout.LayoutParams(indicators.dp(22f), indicators.dp(18f)))
        usbMark = usb
        wifiView = wifi
        updateIndicators()
    }

    private fun updateIndicators() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val info = try { wm?.connectionInfo } catch (e: SecurityException) { null }
        val level = if (wm == null || info == null || info.networkId == -1 || info.rssi <= -127) 0
            else 1 + WifiManager.calculateSignalLevel(info.rssi, 3)
        wifiView?.level = level
        usbMark?.visibility = if (Storage.removable(this).isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateMemoryPill() {
        val pill = memoryPill ?: return
        val mi = Memory.info(this)
        pill.bind(R.drawable.ic_memory, getString(R.string.memory), getString(R.string.memory_sub, mi.freeMb, mi.totalMb))
    }

    private fun pillParams() = LinearLayout.LayoutParams(systemRow.dp(164f), systemRow.dp(52f)).apply { marginEnd = systemRow.dp(12f) }

    // ---- app tile menu, move and hide ----

    private fun showAppMenu(tile: AppTileView, app: TvApp) {
        val menu = PopupMenu(this, tile)
        menu.menu.add(0, 1, 0, R.string.move)
        menu.menu.add(0, 2, 1, R.string.hide)
        menu.menu.add(0, 3, 2, R.string.app_info)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> enterMoveMode(tile)
                2 -> { Apps.hide(this, app.pkg); renderApps(); renderSystem() }
                3 -> Apps.openAppInfo(this, app.pkg)
            }
            true
        }
        menu.show()
    }

    private fun showToolMenu(anchor: View, app: TvApp) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 2, 0, R.string.hide)
        menu.menu.add(0, 3, 1, R.string.app_info)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> { Apps.hide(this, app.pkg); renderApps(); renderSystem() }
                3 -> Apps.openAppInfo(this, app.pkg)
            }
            true
        }
        menu.show()
    }

    private fun enterMoveMode(tile: AppTileView) {
        orderBeforeMove = currentOrder()
        movingTile = tile
        tile.moving = true
        tile.requestFocus()
        hint.text = getString(R.string.move_hint)
        hint.visibility = View.VISIBLE
    }

    private fun exitMoveMode(cancel: Boolean) {
        val tile = movingTile ?: return
        movingTile = null
        tile.moving = false
        hint.visibility = View.INVISIBLE
        if (cancel) {
            Prefs.setAppOrder(this, orderBeforeMove)
            renderApps()
            appsGrid.findViewWithTag<View>(tile.app?.pkg)?.requestFocus()
        } else {
            Prefs.setAppOrder(this, currentOrder())
            tile.requestFocus()
        }
    }

    private fun currentOrder(): List<String> =
        (0 until appsGrid.childCount).mapNotNull { (appsGrid.getChildAt(it) as? AppTileView)?.app?.pkg }

    private fun showHiddenApps() {
        val hidden = Apps.list(this, includeHidden = true).filter { it.pkg in Prefs.hiddenApps(this) }
        if (hidden.isEmpty()) { renderSystem(); return }
        AlertDialog.Builder(this, R.style.Theme_LeanTV_Dialog)
            .setTitle(R.string.hidden_apps)
            .setItems(hidden.map { it.label }.toTypedArray()) { _, which ->
                Apps.unhide(this, hidden[which].pkg)
                renderApps(); renderSystem()
            }
            .show()
    }

    /** Updates unbind the key filter; when it is not live, rewrite the setting so the system rebinds it. */
    private fun ensureKeyFilterEnabled() {
        try {
            val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return
            val live = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
            if (live) return
            val cr = contentResolver
            val me = "$packageName/${KeyFilterService::class.java.name}"
            val others = Settings.Secure.getString(cr, "enabled_accessibility_services").orEmpty()
                .split(':').filter { it.isNotEmpty() && it != me }
            Settings.Secure.putString(cr, "enabled_accessibility_services", others.joinToString(":"))
            Settings.Secure.putString(cr, "enabled_accessibility_services", (others + me).joinToString(":"))
            Settings.Secure.putInt(cr, "accessibility_enabled", 1)
        } catch (e: SecurityException) { }
    }

    private fun startSendToTv() {
        try { SendToTv.start(this) } catch (e: Exception) { }
        val url = SendToTv.url(this)
        if (url != null) {
            qr.text = url
            qrBlock.visibility = View.VISIBLE
        } else {
            qrBlock.visibility = View.GONE
            handler.postDelayed({ if (!isFinishing) startSendToTv() }, 5000)
        }
    }

    // ---- integrations filled in by the other modules ----

    private fun openChannels() {
        app.cleantv.channels.ChannelsActivity.start(this)
    }
}
