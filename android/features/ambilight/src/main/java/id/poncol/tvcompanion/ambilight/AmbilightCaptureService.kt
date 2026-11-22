package id.poncol.tvcompanion.ambilight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AmbilightCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var epoch = 0L
    private var issue: Issue? = null
    private var ledCountMismatch: LedCountMismatch? = null
    private var config = AmbilightConfig()
    private var control: ScheduledExecutorService? = null
    private var probe: ScheduledFuture<*>? = null
    private var thread: HandlerThread? = null
    private var capture: Handler? = null
    private var frameSource: FrameSource? = null
    private var transport: LedTransport? = null
    private var output: CaptureOutput? = null
    private var client: WledClient? = null
    private var testing = false
    private var stopping = false
    private val keepAlive = object : Runnable {
        override fun run() {
            val delayMs = output?.keepAlive(System.nanoTime()) ?: 1000
            capture?.postDelayed(this, delayMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra(EXTRA_EPOCH, 0) ?: 0
        if (intent?.action == ACTION_STOP) {
            if (epoch != 0L && requested == epoch) ambilightSession.stop()
            else if (epoch == 0L) stopSelf()
            return START_NOT_STICKY
        }
        if (epoch != 0L) return START_NOT_STICKY
        val action = intent?.action
        if (action != ACTION_START && action != ACTION_TEST) {
            stopSelf()
            return START_NOT_STICKY
        }
        testing = action == ACTION_TEST
        try {
            // Android requires promotion even if the user stopped before this command arrived.
            if (!testing) {
                val notification = notification(requested)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else startForeground(NOTIFICATION_ID, notification)
            }
            if (!ambilightSession.attach(requested, ::release)) {
                if (!testing) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            epoch = requested
            config = AmbilightPreferences(this).load()
            ambilightSession.look = Look(config.profile, config.brightness)
            checkNotNull(intent)
            val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CONSENT)
            if (!testing) checkNotNull(consent)
            val address = config.controllerAddress()
            val wled = WledClient(address)
            client = wled
            val executor = Executors.newSingleThreadScheduledExecutor { job -> Thread(job, "ambilight-control") }
            control = executor
            executor.execute {
                val info = wled.probe()
                main.post {
                    if (!starting()) return@post
                    when {
                        info == null -> end(Issue.CONTROLLER)
                        info.ledCount < config.layout.total -> {
                            ledCountMismatch = LedCountMismatch(config.layout.total, info.ledCount)
                            end(Issue.CONTROLLER)
                        }
                        else -> openCapture(consent, address, info.name)
                    }
                }
            }
        } catch (_: RuntimeException) {
            if (epoch == 0L) {
                ambilightSession.cancel(requested, Issue.CAPTURE)
                stopSelf()
            } else end(Issue.CAPTURE)
        }
        return START_NOT_STICKY
    }

    private fun starting() = ambilightSession.state.epoch == epoch && ambilightSession.state.phase == Phase.STARTING

    private fun openCapture(consent: Intent?, address: java.net.InetAddress, name: String) {
        val captureThread = HandlerThread("ambilight-capture").also { it.start() }
        thread = captureThread
        val handler = Handler(captureThread.looper)
        capture = handler
        handler.post {
            try {
                val udp = UdpDdpTransport(address, config.layout.total)
                transport = udp
                if (consent == null) {
                    main.post {
                        if (starting()) {
                            ambilightSession.activate(epoch, name)
                        }
                    }
                    handler.postDelayed({
                        var sent = false
                        ambilightSession.send(epoch) {
                            testAttempted = true
                            sent = udp.send(testColors(config))
                        }
                        handler.postDelayed({ main.post { end(if (sent) Issue.TEST_COMPLETE else Issue.CONTROLLER) } }, 1500)
                    }, 200)
                } else {
                    val sink = CaptureOutput(config, ambilightSession, epoch, udp) { blank, failed ->
                        main.post { ambilightSession.health(epoch, blank, failed) }
                    }
                    output = sink
                    val source = MediaProjectionFrameSource(this, consent, handler)
                    frameSource = source
                    source.start(sink::frame) { failure -> main.post { end(failure) } }
                    handler.postDelayed(keepAlive, 1000)
                    main.post {
                        if (starting()) {
                            ambilightSession.activate(epoch, name)
                            probe = control?.scheduleWithFixedDelay({
                                val unreachable = client?.probe() == null
                                main.post { ambilightSession.health(epoch, unreachable = unreachable) }
                            }, 30, 30, TimeUnit.SECONDS)
                        }
                    }
                }
            } catch (_: Exception) {
                main.post { end(Issue.CAPTURE) }
            }
        }
    }

    private var testAttempted = false

    private fun end(reason: Issue) {
        if (stopping || ambilightSession.state.epoch != epoch) return
        issue = reason
        ambilightSession.stop()
    }

    private fun release() {
        if (stopping) return
        stopping = true
        probe?.cancel(false)
        val handler = capture
        val captureThread = thread
        if (handler == null || captureThread == null) {
            finish(false)
            return
        }
        handler.postAtFrontOfQueue {
            handler.removeCallbacksAndMessages(null)
            frameSource?.close()
            runCatching { transport?.close() }
            val sent = output?.attempted == true || testAttempted
            output?.let { Log.i("Ambilight", it.summary()) }
            frameSource = null
            transport = null
            output = null
            captureThread.quitSafely()
            main.post {
                capture = null
                thread = null
                finish(sent, captureThread)
            }
        }
    }

    private fun finish(sent: Boolean, captureThread: HandlerThread? = null) {
        val executor = control
        if (executor == null) {
            complete(issue)
            return
        }
        val reason = issue
        executor.execute {
            captureThread?.join()
            val result = if (sent && client?.exit(!testing && AmbilightPreferences(this).load().turnOff) != true) Issue.STOP_OUTPUT else reason
            executor.shutdown()
            main.post { complete(result) }
        }
    }

    private fun complete(reason: Issue?) {
        if (!testing) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ambilightSession.finish(epoch, reason, ledCountMismatch)
    }

    override fun onDestroy() {
        if (epoch != 0L && ambilightSession.state.epoch == epoch && !stopping) end(Issue.CAPTURE)
        super.onDestroy()
    }

    private fun notification(requested: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ambilight_title), NotificationManager.IMPORTANCE_LOW))
        val stop = Intent(this, AmbilightCaptureService::class.java)
            .setAction(ACTION_STOP).putExtra(EXTRA_EPOCH, requested)
        val pending = PendingIntent.getService(this, requested.toInt(), stop, PendingIntent.FLAG_IMMUTABLE)
        val click = PendingIntent.getActivity(this, 0, Intent(this, AmbilightSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(id.poncol.tvcompanion.designsystem.R.drawable.ic_bulb)
            .setContentTitle(getString(R.string.ambilight_title))
            .setContentText(getString(R.string.ambilight_notification))
            .setContentIntent(click)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.ambilight_stop), pending).build())
            .build()
    }

    internal companion object {
        const val ACTION_START = "ambilight.START"
        const val ACTION_STOP = "ambilight.STOP"
        const val ACTION_TEST = "ambilight.TEST"
        const val EXTRA_EPOCH = "epoch"
        const val EXTRA_CONSENT = "consent"
        private const val NOTIFICATION_ID = 4048
        private const val CHANNEL = "ambilight_capture"
    }
}
