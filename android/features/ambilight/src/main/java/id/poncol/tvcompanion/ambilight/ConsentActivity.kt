package id.poncol.tvcompanion.ambilight

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.ACTION_START
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.EXTRA_CONSENT
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.EXTRA_EPOCH

/** Invisible host for the screen-capture approval, so Ambilight can start without its settings screen. */
class ConsentActivity : Activity() {
    private var epoch = 0L
    private var settled = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { epoch = savedInstanceState.getLong(KEY_EPOCH); return } // Result still pending.
        epoch = intent.getLongExtra(EXTRA_EPOCH, 0L)
        val state = ambilightSession.state
        if (epoch == 0L || state.epoch != epoch || state.phase != Phase.STARTING) { settled = true; finish(); return }
        Ambilight.shownEpoch = epoch
        try {
            val projection = getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    projection.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                }.getOrElse { projection.createScreenCaptureIntent() }
            } else projection.createScreenCaptureIntent()
            startActivityForResult(request, SCREEN_CAPTURE)
        } catch (_: RuntimeException) {
            settled = true
            ambilightSession.cancel(epoch, Issue.CONSENT)
            finish()
        }
    }

    @Deprecated("The platform result callback supports API 26 without an additional dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCREEN_CAPTURE) return
        settled = true
        val state = ambilightSession.state
        if (state.epoch == epoch && state.phase == Phase.STARTING) {
            if (resultCode != RESULT_OK || data == null) ambilightSession.cancel(epoch, Issue.CONSENT)
            else try {
                startForegroundService(Intent(this, AmbilightCaptureService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_EPOCH, epoch).putExtra(EXTRA_CONSENT, data))
            } catch (_: RuntimeException) {
                ambilightSession.cancel(epoch, Issue.CAPTURE)
            }
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_EPOCH, epoch)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !settled) ambilightSession.cancel(epoch, Issue.CONSENT)
        super.onDestroy()
    }

    private companion object {
        const val SCREEN_CAPTURE = 4048
        const val KEY_EPOCH = "consent_epoch"
    }
}
