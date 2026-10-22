package app.cleantv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

data class Volume(val label: String, val path: File?, val freeBytes: Long, val totalBytes: Long) {
    val freeText: String get() = "${gb(freeBytes)} free"
    private fun gb(b: Long): String = if (b >= 1_000_000_000L) String.format("%.1f GB", b / 1e9) else String.format("%d MB", b / 1_000_000L)
}

object Storage {
    fun removable(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .map { v ->
                val path = pathOf(v)
                var free = 0L; var total = 0L
                if (path != null && path.exists()) {
                    try { val st = StatFs(path.absolutePath); free = st.availableBytes; total = st.totalBytes } catch (e: IllegalArgumentException) { }
                }
                val label = v.getDescription(ctx)?.trim().orEmpty().ifEmpty { "USB drive" }
                Volume(label, path, free, total)
            }
    }

    /** StorageVolume.getPath is hidden on Android 9 but still reachable through reflection. */
    private fun pathOf(v: StorageVolume): File? = try {
        val m = StorageVolume::class.java.getMethod("getPath")
        (m.invoke(v) as? String)?.let { File(it) }
    } catch (e: Exception) {
        null
    }

    /** Opens the file browser already on the TV. Own browser can replace this later. */
    fun open(ctx: Context, volume: Volume?) {
        val explorer = Intent("duokan.mediaexplorer.intent.action.BROWSE_DEVICE_LIST")
            .setClassName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(explorer)
            return
        } catch (e: ActivityNotFoundException) { } catch (e: SecurityException) { }
        val fallback = ctx.packageManager.getLeanbackLaunchIntentForPackage("com.xiaomi.mitv.mediaexplorer")
            ?: ctx.packageManager.getLaunchIntentForPackage("com.xiaomi.mitv.mediaexplorer")
        fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { try { ctx.startActivity(it) } catch (e: ActivityNotFoundException) { } }
    }
}
