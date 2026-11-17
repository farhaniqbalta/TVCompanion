package id.poncol.tvcompanion.airplay.runtime.ambient

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.Choreographer

/** Full-screen GL layer above the video SurfaceView. Call begin()/end() on main. */
class AmbientView(
    context: Context,
    look: String,
    subscription: () -> AmbientSubscription?,
    onStatus: (AmbientStatus) -> Unit,
    onSlow: (String) -> Unit,
    onFailure: (Throwable) -> Unit,
) : GLSurfaceView(context) {
    private var vsync = 0
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (++vsync % 2 == 0) requestRender()   // 30 fps: every 2nd vsync
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val renderer = AmbientRenderer(context, look, subscription, onStatus, onSlow, onFailure)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        holder.setFormat(PixelFormat.RGBX_8888)
        setZOrderMediaOverlay(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setLook(name: String) = queueEvent { renderer.useLook(name) }

    fun begin() {
        onResume()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun end() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        queueEvent { renderer.releaseGl() }   // runs before onPause tears the context down
        onPause()
    }
}
