package id.poncol.tvcompanion.ambilight

import id.poncol.tvcompanion.ambilight.engine.FrameCadence
import id.poncol.tvcompanion.ambilight.engine.LedPipeline
import id.poncol.tvcompanion.ambilight.engine.PixelAccess

/** The capture looper owns all fields and calls. */
internal class CaptureOutput(
    private val config: AmbilightConfig,
    private val session: SessionController,
    private val epoch: Long,
    private val transport: LedTransport,
    private val health: (Boolean, Boolean) -> Unit,
) {
    private val rgb = ByteArray(config.layout.total * 3)
    private val scaled = ByteArray(rgb.size)
    private val cadence = FrameCadence(30)
    private var useSourceTime = true
    private var lastSourceTime = 0L
    private var pipeline: LedPipeline? = null
    private var width = 0
    private var height = 0
    private var blankSince = 0L
    private var lastSample = 0L
    private var lastOutput = 0L
    private var firstFrame = 0L
    private var lastFrame = 0L
    private var blank = false
    private var sendError = false
    private var received = 0
    private var processed = 0
    private var droppedOutput = 0
    private val timings = IntArray(101)
    private val intervals = IntArray(101)
    var attempted = false
        private set

    fun frame(pixels: PixelAccess, now: Long, sourceTime: Long) {
        if (received++ == 0) firstFrame = now
        else intervals[((now - lastFrame) / 1_000_000).coerceIn(0, 100).toInt()]++
        lastFrame = now
        if (useSourceTime && sourceTime <= lastSourceTime) {
            useSourceTime = false
            cadence.reset()
        }
        lastSourceTime = sourceTime
        if (!cadence.accept(if (useSourceTime) sourceTime else now)) return
        val began = System.nanoTime()
        if (pixels.width != width || pixels.height != height) {
            width = pixels.width
            height = pixels.height
            // Brightness is applied at send time so keepalive resends pick up changes on a paused picture.
            pipeline = LedPipeline(width, height, config.layout, session.look.profile, 100)
        }
        val current = checkNotNull(pipeline)
        current.profile = session.look.profile
        val dark = current.process(pixels, now, rgb)
        if (!dark || now - lastSample > 500_000_000L) blankSince = 0
        if (dark && blankSince == 0L) blankSince = now
        lastSample = now
        processed++
        val newBlank = dark && now - blankSince >= 5_000_000_000L
        if (newBlank != blank) {
            blank = newBlank
            health(blank, sendError)
        }
        output(now)
        timings[((System.nanoTime() - began) / 100_000).coerceIn(0, 100).toInt()]++
    }

    fun keepAlive(now: Long): Long {
        if (processed == 0) return 1000
        val remainingNs = 1_000_000_000L - (now - lastOutput)
        if (remainingNs > 0) return (remainingNs + 999_999L) / 1_000_000L
        output(now)
        return 1000
    }

    private fun output(now: Long) = session.send(epoch) {
        attempted = true
        lastOutput = now
        val brightness = session.look.brightness
        for (i in rgb.indices) scaled[i] = (((rgb[i].toInt() and 255) * brightness + 50) / 100).toByte()
        val failed = !transport.send(scaled)
        if (failed) droppedOutput++
        if (failed != sendError) {
            sendError = failed
            health(blank, sendError)
        }
    }

    fun summary(): String =
        "received=$received processed=$processed outputErrors=$droppedOutput " +
            "frameSpanMs=${(lastFrame - firstFrame) / 1_000_000} " +
            "pipelineP50_100us=${percentile(timings, processed, 50)} pipelineP95_100us=${percentile(timings, processed, 95)} " +
            "acquiredIntervalP50_ms=${percentile(intervals, received - 1, 50)} acquiredIntervalP95_ms=${percentile(intervals, received - 1, 95)}"

    private fun percentile(buckets: IntArray, count: Int, percent: Int): Int {
        if (count <= 0) return -1
        var seen = 0
        for (index in buckets.indices) {
            seen += buckets[index]
            if (seen * 100L >= count * percent.toLong()) return index
        }
        return buckets.lastIndex
    }
}
