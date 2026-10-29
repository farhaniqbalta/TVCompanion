package id.poncol.tvcompanion.app
import android.app.Application
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngine
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngineProvider
import id.poncol.tvcompanion.uxplay.UxPlayReceiverEngine
class TVCompanionApplication : Application(), ReceiverEngineProvider {
    override fun receiverCapabilities() = UxPlayReceiverEngine.capabilities()
    override fun createReceiverEngine(): ReceiverEngine = UxPlayReceiverEngine(this)
}
