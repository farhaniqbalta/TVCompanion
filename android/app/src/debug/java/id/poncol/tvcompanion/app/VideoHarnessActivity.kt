package id.poncol.tvcompanion.app

import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import java.io.File
import java.io.FileInputStream

/**
 * EXPERIMENT (debug builds on exp/ambilight-capture-research only): loops a local clip through
 * SurfaceView (decoder output straight to a layer) or TextureView (decoder output drawn by the app's GPU).
 * adb shell am start -n id.poncol.tvcompanion/.VideoHarnessActivity --es view texture --es file bbb_av1_1080.mp4
 */
class VideoHarnessActivity : Activity(), SurfaceHolder.Callback, TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("view") == "texture") {
            setContentView(TextureView(this).also { it.surfaceTextureListener = this })
        } else {
            setContentView(SurfaceView(this).also { it.holder.addCallback(this) })
        }
    }

    private fun play(surface: Surface) {
        release()
        val file = File(filesDir, intent.getStringExtra("file") ?: "clip.mp4")
        player = MediaPlayer().apply {
            FileInputStream(file).use { setDataSource(it.fd) }
            setSurface(surface)
            isLooping = true
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    private fun release() {
        player?.release()
        player = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) = play(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = release()
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = play(Surface(texture))
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
