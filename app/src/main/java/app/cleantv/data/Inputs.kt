package app.cleantv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.widget.Toast
import app.cleantv.R

/** One physical input: an HDMI port or the composite jack. */
data class TvInput(
    val id: String,
    val label: String,
    val number: String,
    val state: Int,
    val type: Int
) {
    val isHdmi: Boolean get() = type == TvInputInfo.TYPE_HDMI
    /** On this hardware an unplugged port reports standby, so only CONNECTED counts as something plugged in. */
    val connected: Boolean get() = state == TvInputManager.INPUT_STATE_CONNECTED
}

class Inputs(private val ctx: Context) {
    private val tim: TvInputManager? = ctx.getSystemService(TvInputManager::class.java)

    fun list(): List<TvInput> {
        val m = tim ?: return emptyList()
        return m.tvInputList
            .filter { it.isPassthroughInput && it.id.contains("/HW") && PASSTHROUGH_TYPES.contains(it.type) }
            .map { info ->
                val label = prettyLabel(info.loadLabel(ctx)?.toString()?.trim().orEmpty().ifEmpty { info.id.substringAfterLast('/') })
                TvInput(
                    id = info.id,
                    label = label,
                    number = numberFor(label, info.type),
                    state = try { m.getInputState(info.id) } catch (e: IllegalArgumentException) { TvInputManager.INPUT_STATE_DISCONNECTED },
                    type = info.type
                )
            }
            .sortedWith(compareBy({ if (it.isHdmi) 0 else 1 }, { it.number.toIntOrNull() ?: 99 }, { it.label }))
    }

    fun register(cb: TvInputManager.TvInputCallback, handler: Handler) {
        tim?.registerCallback(cb, handler)
    }

    fun unregister(cb: TvInputManager.TvInputCallback) {
        try { tim?.unregisterCallback(cb) } catch (e: IllegalArgumentException) { }
    }

    /** Puts a space between letters and digits so "HDMI1" reads "HDMI 1". */
    private fun prettyLabel(raw: String): String = raw.replace(Regex("([A-Za-z])(\\d)"), "$1 $2")

    private fun numberFor(label: String, type: Int): String {
        val digits = label.filter { it.isDigit() }
        if (type == TvInputInfo.TYPE_HDMI && digits.isNotEmpty()) return digits
        return when (type) {
            TvInputInfo.TYPE_COMPOSITE -> "AV"
            TvInputInfo.TYPE_COMPONENT -> "YPbPr"
            TvInputInfo.TYPE_VGA -> "VGA"
            else -> digits.ifEmpty { label.take(3).uppercase() }
        }
    }

    companion object {
        private val PASSTHROUGH_TYPES = setOf(
            TvInputInfo.TYPE_HDMI, TvInputInfo.TYPE_COMPOSITE,
            TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_VGA, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_DVI
        )

        /** Switches the TV to the input through the Live TV passthrough path, verified on this set. */
        fun tune(ctx: Context, inputId: String): Boolean {
            val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(ctx, R.string.no_live_tv, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }
}
