package id.poncol.tvcompanion.notifications.infrastructure

import id.poncol.tvcompanion.domain.ReceiverSnapshot

/** One immutable snapshot for this feature's process. Only its service session publishes. */
internal object ReceiverStatusStore {
    @Volatile var snapshot = ReceiverSnapshot()
        private set
    fun publish(value: ReceiverSnapshot) { snapshot = value }
}
