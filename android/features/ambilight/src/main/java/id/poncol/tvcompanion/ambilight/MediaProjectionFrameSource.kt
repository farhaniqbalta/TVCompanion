package id.poncol.tvcompanion.ambilight

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.util.Log
import id.poncol.tvcompanion.ambilight.engine.PixelAccess
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

internal interface FrameSource : Closeable {
    fun start(frame: (PixelAccess, Long, Long) -> Unit, ended: (Issue) -> Unit)
}

internal class MediaProjectionFrameSource(
    private val context: Context,
    private val consent: Intent,
    private val handler: Handler,
) : FrameSource {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    private var revoked = false
    private var pixels = PixelAccess(WIDTH, HEIGHT)

    override fun start(frame: (PixelAccess, Long, Long) -> Unit, ended: (Issue) -> Unit) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val token = checkNotNull(manager.getMediaProjection(Activity.RESULT_OK, consent))
        projection = token
        val listener = object : MediaProjection.Callback() {
            override fun onStop() {
                revoked = true
                ended(Issue.REVOKED)
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width <= 0 || height <= 0) return
                // Android centers and scales the content uniformly into the fixed 160×90 surface.
                val scale = minOf(WIDTH.toDouble() / width, HEIGHT.toDouble() / height)
                val fittedWidth = (width * scale).roundToInt().coerceIn(1, WIDTH)
                val fittedHeight = (height * scale).roundToInt().coerceIn(1, HEIGHT)
                if (pixels.width != fittedWidth || pixels.height != fittedHeight) {
                    pixels = PixelAccess(fittedWidth, fittedHeight)
                }
            }
        }
        callback = listener
        token.registerCallback(listener, handler)
        val images = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
        reader = images
        images.setOnImageAvailableListener({ available ->
            try {
                val image = available.acquireLatestImage()
                if (image != null) expTick(image.format)
                if (image != null) try {
                    val captured = pixels
                    check(image.format == PixelFormat.RGBA_8888)
                    val plane = image.planes[0]
                    val offset = (HEIGHT - captured.height) / 2 * plane.rowStride +
                        (WIDTH - captured.width) / 2 * plane.pixelStride
                    captured.bind(plane.buffer, plane.rowStride, plane.pixelStride, offset)
                    frame(captured, System.nanoTime(), image.timestamp)
                } finally {
                    pixels.clear()
                    image.close()
                }
            } catch (_: RuntimeException) {
                ended(Issue.CAPTURE)
            }
        }, handler)
        display = checkNotNull(token.createVirtualDisplay(
            "Ambilight", WIDTH, HEIGHT, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.surface, null, handler,
        ))
        expStart = System.nanoTime()
        handler.postDelayed(expPoll, 500)
    }

    // EXPERIMENT (branch exp/ambilight-capture-research, never merge): the host writes files/capture_exp via
    // `run-as` to switch capture configs inside one projection session. Empty = production path.
    // Specs: "off" (no surface), "rgba W H" / "yuv W H" (acquire+close only, no LED processing).
    private var expSpec = ""
    private var expReader: ImageReader? = null
    private var expStart = 0L
    private var expLast = 0L
    private var expFrames = 0
    private var expFormat = -1
    private var expGapMaxNs = 0L
    private val expPoll = object : Runnable {
        override fun run() {
            val spec = runCatching { File(context.filesDir, "capture_exp").readText().trim() }.getOrDefault("")
            if (spec != expSpec) runCatching { applyExp(spec) }.onFailure { Log.w(EXP, "apply '$spec' failed", it) }
            handler.postDelayed(this, 500)
        }
    }

    private fun expTick(format: Int) {
        val now = System.nanoTime()
        if (expFrames > 0) expGapMaxNs = maxOf(expGapMaxNs, now - expLast)
        expLast = now
        expFrames++
        expFormat = format
    }

    private fun applyExp(spec: String) {
        val spanMs = (System.nanoTime() - expStart) / 1_000_000
        Log.i(EXP, "window spec='$expSpec' spanMs=$spanMs frames=$expFrames fps=${"%.1f".format(expFrames * 1000.0 / maxOf(spanMs, 1))} " +
            "format=0x${Integer.toHexString(expFormat)} gapMaxMs=${expGapMaxNs / 1_000_000}")
        expSpec = spec
        expStart = System.nanoTime()
        expFrames = 0
        expFormat = -1
        expGapMaxNs = 0
        val current = checkNotNull(display)
        val dpi = context.resources.displayMetrics.densityDpi
        val old = expReader
        expReader = null
        val parts = spec.split(' ')
        when {
            spec.isEmpty() -> { current.resize(WIDTH, HEIGHT, dpi); current.surface = reader?.surface }
            spec == "off" -> current.surface = null
            else -> {
                val format = if (parts[0] == "yuv") ImageFormat.YUV_420_888 else PixelFormat.RGBA_8888
                val next = ImageReader.newInstance(parts[1].toInt(), parts[2].toInt(), format, 3)
                next.setOnImageAvailableListener({ r ->
                    // MEASURED: SurfaceFlinger writes RGBA into a YUV reader here, so acquire throws; log instead of crashing.
                    try { r.acquireLatestImage()?.let { expTick(it.format); it.close() } }
                    catch (e: UnsupportedOperationException) { expFormat = -2; Log.w(EXP, "acquire failed: ${e.message}") }
                }, handler)
                expReader = next
                current.resize(parts[1].toInt(), parts[2].toInt(), dpi)
                current.surface = next.surface
            }
        }
        old?.close()
        Log.i(EXP, "apply spec='$spec'")
    }

    override fun close() {
        handler.removeCallbacks(expPoll)
        runCatching { expReader?.close() }
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        callback?.let { listener -> runCatching { projection?.unregisterCallback(listener) } }
        if (!revoked) runCatching { projection?.stop() }
        callback = null
        projection = null
        display = null
        reader = null
        pixels.clear()
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 90
        const val EXP = "AmbilightExp"
    }
}
