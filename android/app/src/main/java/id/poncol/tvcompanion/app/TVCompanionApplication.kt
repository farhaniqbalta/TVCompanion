package id.poncol.tvcompanion.app
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import id.poncol.tvcompanion.application.LightController
import id.poncol.tvcompanion.tuya.TuyaLightGateway
import id.poncol.tvcompanion.domain.AutoStartSettings
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngine
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngineProvider
import id.poncol.tvcompanion.uxplay.UxPlayReceiverEngine
class TVCompanionApplication : Application(), ReceiverEngineProvider, AutoStartSettings {
    private val boot by lazy { getSharedPreferences("startup", Context.MODE_PRIVATE) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    /** One light owner per process so the TV screen and remote callers share serialization. */
    val light: LightController by lazy { LightController(TuyaLightGateway(this), { task -> mainHandler.post(task); Unit }) }
    override var notificationsOnBoot: Boolean
        get() = boot.getBoolean("notifications", false)
        set(value) { boot.edit().putBoolean("notifications", value).apply() }
    override var airplayOnBoot: Boolean
        get() = boot.getBoolean("airplay", false)
        set(value) { boot.edit().putBoolean("airplay", value).apply() }
    override var controlsOnBoot: Boolean
        get() = boot.getBoolean("controls", true)
        set(value) { boot.edit().putBoolean("controls", value).apply() }
    override fun receiverCapabilities() = UxPlayReceiverEngine.capabilities()
    override fun createReceiverEngine(): ReceiverEngine = UxPlayReceiverEngine(this)
}
