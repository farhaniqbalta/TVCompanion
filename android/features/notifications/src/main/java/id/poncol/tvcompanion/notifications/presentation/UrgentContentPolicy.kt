package id.poncol.tvcompanion.notifications.presentation

/** Preserve the global privacy/compact/full contract even for a prominent urgent window. */
internal data class UrgentContentPolicy(val titleVisible: Boolean, val bodyVisible: Boolean) {
    companion object {
        fun from(mode: String) = UrgentContentPolicy(mode == "compact" || mode == "expanded", mode == "expanded")
    }
}
