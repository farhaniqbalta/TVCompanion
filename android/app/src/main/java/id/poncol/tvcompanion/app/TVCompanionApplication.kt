package id.poncol.tvcompanion.app
import android.app.Application
import android.content.Context
import id.poncol.tvcompanion.domain.AutoStartSettings
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngine
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngineProvider
import id.poncol.tvcompanion.uxplay.UxPlayReceiverEngine
class TVCompanionApplication : Application(), ReceiverEngineProvider, AutoStartSettings {
    private val boot by lazy { getSharedPreferences("startup", Context.MODE_PRIVATE) }
    override var notificationsOnBoot: Boolean
        get() = boot.getBoolean("notifications", false)
        set(value) { boot.edit().putBoolean("notifications", value).apply() }
    override var airplayOnBoot: Boolean
        get() = boot.getBoolean("airplay", false)
        set(value) { boot.edit().putBoolean("airplay", value).apply() }
    override fun receiverCapabilities() = UxPlayReceiverEngine.capabilities()
    override fun createReceiverEngine(): ReceiverEngine = UxPlayReceiverEngine(this)
}
