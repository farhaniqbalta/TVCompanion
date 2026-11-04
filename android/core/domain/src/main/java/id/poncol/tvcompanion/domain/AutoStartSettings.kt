package id.poncol.tvcompanion.domain

/** User choices for restoring independently owned TV receivers after device boot. */
interface AutoStartSettings {
    var notificationsOnBoot: Boolean
    var airplayOnBoot: Boolean
}
