package id.poncol.tvcompanion.airplay.runtime.ambient

import android.content.Context
import android.content.Intent
import id.poncol.tvcompanion.airplay.runtime.AirPlayPlaybackActivity
import id.poncol.tvcompanion.domain.airplay.AirPlaySnapshot

sealed class AmbientRequest {
    data class Open(val look: String) : AmbientRequest()
    object Close : AmbientRequest()
}

/** Opens or closes Ambient in the playback screen without the TV user navigating there. */
object AmbientShortcut {
    const val EXTRA_AMBIENT = "id.poncol.tvcompanion.ambient.REQUEST"
    const val EXTRA_LOOK = "id.poncol.tvcompanion.ambient.LOOK"

    fun parse(action: String?, look: String?): AmbientRequest? = when (action) {
        "open" -> look?.takeIf { it in AmbientShaders.LOOKS }?.let { AmbientRequest.Open(it) }
        "close" -> AmbientRequest.Close
        else -> null
    }
    fun canOpen(snapshot: AirPlaySnapshot, look: String): Boolean = AmbientPolicy.canOpen(snapshot) && look in AmbientShaders.LOOKS
    fun canClose(snapshot: AirPlaySnapshot): Boolean = snapshot.ambientShowing

    /** False when nothing was launched. */
    fun open(context: Context, snapshot: AirPlaySnapshot, look: String): Boolean =
        canOpen(snapshot, look) && launch(context, Intent().putExtra(EXTRA_AMBIENT, "open").putExtra(EXTRA_LOOK, look))
    fun close(context: Context, snapshot: AirPlaySnapshot): Boolean =
        canClose(snapshot) && launch(context, Intent().putExtra(EXTRA_AMBIENT, "close"))

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.setClass(context, AirPlayPlaybackActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        true
    } catch (_: RuntimeException) { false }
}
