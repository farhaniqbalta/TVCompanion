package id.poncol.tvcompanion.notifications.presentation

import android.content.Context
import id.poncol.tvcompanion.domain.*
import id.poncol.tvcompanion.notifications.R
import java.text.DateFormat
import java.util.Date

/** Human-readable copy is an Android presentation concern, not lifecycle state. */
internal object ReceiverStatusText {
    fun status(context: Context, value: ReceiverSnapshot): String {
        value.issue?.let { issue -> return context.getString(when (issue) {
            ReceiverIssue.OVERLAY_PERMISSION -> R.string.receiver_status_permission
            ReceiverIssue.OVERLAY_FAILED -> R.string.receiver_status_overlay_failed
            ReceiverIssue.LISTENER_FAILED -> R.string.receiver_status_listener_failed
            ReceiverIssue.START_FAILED -> R.string.receiver_status_start_failed
        }) }
        return when (value.phase) {
            ReceiverPhase.PAUSED -> context.getString(R.string.receiver_status_paused)
            ReceiverPhase.STARTING -> context.getString(R.string.receiver_status_starting)
            ReceiverPhase.STOPPING -> context.getString(R.string.receiver_status_stopping)
            ReceiverPhase.LISTENING -> context.getString(R.string.receiver_status_listening, value.port ?: 8766)
            ReceiverPhase.FAILED -> context.getString(R.string.receiver_status_start_failed)
        }
    }
    fun receipt(context: Context, value: ReceiverSnapshot) = value.lastAcceptedAt?.let {
        context.getString(R.string.receiver_status_receipt, DateFormat.getTimeInstance().format(Date(it)))
    } ?: context.getString(R.string.receiver_status_no_receipt)
}
