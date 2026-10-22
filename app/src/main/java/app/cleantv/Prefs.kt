package app.cleantv

import android.content.Context
import android.content.SharedPreferences

/** Single small preferences store shared by every module. Keep keys here. */
object Prefs {
    private const val NAME = "leantv"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // Apps grid
    fun appOrder(c: Context): List<String> =
        get(c).getString("app_order", "")!!.split('|').filter { it.isNotEmpty() }
    fun setAppOrder(c: Context, pkgs: List<String>) =
        get(c).edit().putString("app_order", pkgs.joinToString("|")).apply()
    fun hiddenApps(c: Context): Set<String> = get(c).getStringSet("hidden_apps", emptySet())!!
    fun setHiddenApps(c: Context, pkgs: Set<String>) =
        get(c).edit().putStringSet("hidden_apps", pkgs).apply()

    // Inputs
    fun defaultInputId(c: Context): String? = get(c).getString("default_input", null)
    fun setDefaultInputId(c: Context, id: String?) = get(c).edit().putString("default_input", id).apply()
    fun lastBootHandled(c: Context): Long = get(c).getLong("last_boot", 0L)
    fun setLastBootHandled(c: Context, bootTimeMillis: Long) = get(c).edit().putLong("last_boot", bootTimeMillis).apply()

    // Screensaver: "mystify" | "starfield" | "pipes" | "off"
    fun saverMode(c: Context): String = get(c).getString("saver_mode", "matrix")!!
    fun setSaverMode(c: Context, mode: String) = get(c).edit().putString("saver_mode", mode).apply()
    /** Minutes without a key press on the launcher before the screensaver starts. */
    fun saverDelayMin(c: Context): Int = get(c).getInt("saver_delay_min", 1)
    fun setSaverDelayMin(c: Context, min: Int) = get(c).edit().putInt("saver_delay_min", min).apply()

    // Channels
    fun lastChannelId(c: Context): String? = get(c).getString("last_channel", null)
    fun setLastChannelId(c: Context, id: String?) = get(c).edit().putString("last_channel", id).apply()

    // Send to TV
    fun sessionKey(c: Context): String? = get(c).getString("session_key", null)
    fun setSessionKey(c: Context, key: String?) = get(c).edit().putString("session_key", key).apply()
}
