package app.cleantv.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import app.cleantv.Prefs

data class TvApp(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val banner: Drawable?,
    /** True when the drawable is a 16:9 banner rather than a square icon. */
    val isBanner: Boolean
)

object Apps {
    /** Never shown as tiles: the input player, the old launchers and the key remapper. */
    private val EXCLUDED = setOf(
        "com.android.tv",
        "com.mitv.tvhome.atv",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "flar2.homebutton",
        "com.xiaomi.mitv.tvmanager"
    )

    /** Order used until the user moves a tile: the apps actually watched first. */
    private val DEFAULT_ORDER = listOf(
        "com.google.android.youtube.tv",
        "in.startv.hotstar",
        "com.amazon.amazonvideo.livingroom",
        "com.sonyliv",
        "com.google.android.youtube.tvmusic",
        "com.mitv.tvhome.mitvplus"
    )

    fun list(ctx: Context, includeHidden: Boolean = false): List<TvApp> {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val hidden = Prefs.hiddenApps(ctx)
        val all = pm.queryIntentActivities(probe, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != ctx.packageName }
            .filter { it.activityInfo.packageName !in EXCLUDED }
            .filter { includeHidden || it.activityInfo.packageName !in hidden }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                var isBanner = true
                val drawable = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) ?: run { isBanner = false; ri.loadIcon(pm) }
                TvApp(ai.packageName, ComponentName(ai.packageName, ai.name), ri.loadLabel(pm).toString(), drawable, isBanner)
            }
            .toList()
        val order = Prefs.appOrder(ctx).ifEmpty { DEFAULT_ORDER }
        val rank = order.withIndex().associate { it.value to it.index }
        return all.sortedWith(compareBy({ rank[it.pkg] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
    }

    fun launch(ctx: Context, app: TvApp): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg)
            ?: pm.getLaunchIntentForPackage(app.pkg)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(app.component)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun openAppInfo(ctx: Context, pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (e: ActivityNotFoundException) { }
    }

    fun hide(ctx: Context, pkg: String) = Prefs.setHiddenApps(ctx, Prefs.hiddenApps(ctx) + pkg)
    fun unhide(ctx: Context, pkg: String) = Prefs.setHiddenApps(ctx, Prefs.hiddenApps(ctx) - pkg)
}
