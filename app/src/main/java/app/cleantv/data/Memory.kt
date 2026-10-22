package app.cleantv.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent

object Memory {
    /** Processes that must stay: casting, Play Services, the input stack and ourselves. */
    private val KEEP = setOf(
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.apps.mediashell",
        "com.android.tv", "com.droidlogic.tvinput", "com.droidlogic", "mitv.service", "xiaomi.tvservice",
        "com.android.systemui", "com.android.tv.settings", "com.google.android.tv.remote.service",
        "com.google.android.inputmethod.latin"
    )

    data class Info(val freeMb: Long, val totalMb: Long, val low: Boolean)

    fun info(ctx: Context): Info {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Info(mi.availMem / 1_048_576L, mi.totalMem / 1_048_576L, mi.lowMemory)
    }

    /** Opens the TV maker's privileged memory boost screen, which can see every process. */
    fun openBoost(ctx: Context) {
        val i = Intent().setClassName("com.xiaomi.mitv.tvmanager", "com.xiaomi.mitv.tvmanager.boost.ProcessManagerActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { }
    }

    /** Asks Android to drop every cached or background process of apps we can see. Returns the count asked. */
    fun freeUp(ctx: Context): Int {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val pkgs = pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.toMutableSet()
        pkgs += pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }
        pkgs -= ctx.packageName
        pkgs -= KEEP
        var n = 0
        for (p in pkgs) {
            try { am.killBackgroundProcesses(p); n++ } catch (e: SecurityException) { }
        }
        return n
    }
}
