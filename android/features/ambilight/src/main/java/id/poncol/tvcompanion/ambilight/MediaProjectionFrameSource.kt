package id.poncol.tvcompanion.ambilight

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import id.poncol.tvcompanion.ambilight.engine.PixelAccess
import java.io.Closeable
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
    }

    override fun close() {
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
    }
}
