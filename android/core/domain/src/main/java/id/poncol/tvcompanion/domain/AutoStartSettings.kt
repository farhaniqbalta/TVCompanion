package id.poncol.tvcompanion.domain

/** User choices for restoring independently owned TV receivers after device boot. */
interface AutoStartSettings {
    var notificationsOnBoot: Boolean
    var airplayOnBoot: Boolean
    /** Restore the iPhone control listener after boot when an iPhone is paired. */
    var controlsOnBoot: Boolean
}
