package id.poncol.tvcompanion.airplay.runtime.ambient

import android.content.Context
import android.content.pm.ApplicationInfo
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GL thread only. Every GL id is recreated in onSurfaceCreated (context is not preserved). */
internal class AmbientRenderer(
    private val context: Context,
    private val subscription: () -> AmbientSubscription?,
    private val onStatus: (AmbientStatus) -> Unit,   // main thread, at most once per second
    private val onFailure: (Throwable) -> Unit,
) : GLSurfaceView.Renderer {
    private val main = Handler(Looper.getMainLooper())
    private val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    private val motion = AmbientMotion(reduced)
    private val frame = FloatArray(AmbientFrame.SIZE)
    private val loc = IntArray(AmbientShaders.UNIFORMS.size)
    private var program = 0
    private var vbo = 0
    private var width = 1f
    private var height = 1f
    @Volatile private var failed = false
    private var startNs = 0L
    private var lastNs = 0L
    private var statusNs = 0L
    private var loggedInfo = false
    private var statStartNs = 0L
    private var statFrames = 0
    private var sumIntervalNs = 0L
    private var maxIntervalNs = 0L
    private var sumDrawNs = 0L
    private var maxDrawNs = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = guard {
        val bits = IntArray(1).also { glGetIntegerv(GL_RED_BITS, it, 0) }[0]
        if (bits < 8) throw IllegalStateException("GL_RED_BITS=$bits")
        val look = AmbientShaders.assemble(AmbientShaders.ACTIVE_LOOK) { name ->
            runCatching { context.assets.open("ambient/$name").bufferedReader().use { it.readText() } }.getOrNull()
        }
        program = try { link(look) } catch (e: RuntimeException) {
            Log.w(TAG, "look ${look.look} failed, using builtin", e)
            link(AmbientShaders.assemble("__builtin__") { null })
        }
        AmbientShaders.UNIFORMS.forEachIndexed { i, name -> loc[i] = glGetUniformLocation(program, name) }
        val verts = ByteBuffer.allocateDirect(VERTEX_COUNT * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0..AmbientShaders.COLUMNS) {
            val x = -1f + 2f * i / AmbientShaders.COLUMNS
            verts.put(x).put(-1f).put(x).put(1f)
        }
        verts.position(0)
        vbo = IntArray(1).also { glGenBuffers(1, it, 0) }[0]
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, VERTEX_COUNT * 2 * 4, verts, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)
        glUseProgram(program)
        glUniform3fv(loc[U_BASE], 1, BASE, 0)
        glUniform3fv(loc[U_PALETTE], 4, PALETTE, 0)
        glUniform1f(loc[U_REDUCED], if (reduced) 1f else 0f)
        startNs = System.nanoTime(); lastNs = startNs; statStartNs = startNs; statusNs = startNs
        if (debug && !loggedInfo) { loggedInfo = true; Log.d(TAG, "GL_RED_BITS=$bits look=${look.look}") }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) = guard {
        glViewport(0, 0, w, h)
        width = w.toFloat(); height = h.toFloat()
        glUniform2f(loc[U_RES], width, height)
        glUniform1f(loc[U_ASPECT], width / height)
    }

    override fun onDrawFrame(gl: GL10?) = guard {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        val status = subscription()?.read(frame) ?: AmbientStatus.CLOSED
        motion.update(frame, status, dt, (now - startNs) / 1e9f)
        if (now - statusNs >= 1_000_000_000L) { statusNs = now; main.post { onStatus(status) } }
        glUniform4fv(loc[U_PHASE], 1, motion.phase, 0)
        glUniform1f(loc[U_LEVEL], motion.level)
        glUniform1f(loc[U_BASS], motion.bass)
        glUniform1f(loc[U_MID], motion.mid)
        glUniform1f(loc[U_HIGH], motion.high)
        glUniform1f(loc[U_PULSE], motion.pulse)
        glUniform4fv(loc[U_BEAT_AGES], 1, motion.beatAges, 0)
        glUniform1f(loc[U_BEAT_COUNT], motion.beatCount)
        glUniform1f(loc[U_DITHER], motion.ditherSeed)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        if (debug) stats(now, System.nanoTime() - now)
        lastNs = now
    }

    private fun stats(now: Long, drawNs: Long) {
        val interval = now - lastNs
        statFrames++; sumIntervalNs += interval; sumDrawNs += drawNs
        if (interval > maxIntervalNs) maxIntervalNs = interval
        if (drawNs > maxDrawNs) maxDrawNs = drawNs
        if (now - statStartNs < 10_000_000_000L) return
        Log.d(TAG, "frames=$statFrames interval mean=${sumIntervalNs / statFrames / 1000}us max=${maxIntervalNs / 1000}us " +
            "draw mean=${sumDrawNs / statFrames / 1000}us max=${maxDrawNs / 1000}us")
        statStartNs = now; statFrames = 0; sumIntervalNs = 0; maxIntervalNs = 0; sumDrawNs = 0; maxDrawNs = 0
    }

    private inline fun guard(block: () -> Unit) {
        if (failed) return
        try { block() } catch (t: Throwable) {
            failed = true
            Log.e(TAG, "ambient renderer failed", t)
            main.post { onFailure(t) }
        }
    }

    private fun link(src: AmbientShaders.Sources): Int {
        val vs = compile(GL_VERTEX_SHADER, src.vertex)
        val fs = try { compile(GL_FRAGMENT_SHADER, src.fragment) } catch (e: RuntimeException) { glDeleteShader(vs); throw e }
        val p = glCreateProgram()
        glAttachShader(p, vs); glAttachShader(p, fs)
        glBindAttribLocation(p, 0, "aPos")
        glLinkProgram(p)
        glDeleteShader(vs); glDeleteShader(fs)
        val ok = IntArray(1).also { glGetProgramiv(p, GL_LINK_STATUS, it, 0) }[0]
        if (ok == 0) { val log = glGetProgramInfoLog(p); glDeleteProgram(p); throw IllegalStateException("link ${src.look}: $log") }
        return p
    }

    private fun compile(type: Int, code: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, code); glCompileShader(s)
        val ok = IntArray(1).also { glGetShaderiv(s, GL_COMPILE_STATUS, it, 0) }[0]
        if (ok == 0) { val log = glGetShaderInfoLog(s); glDeleteShader(s); throw IllegalStateException("compile: $log") }
        return s
    }

    private companion object {
        const val TAG = "TVCAmbient"
        val VERTEX_COUNT = 2 * (AmbientShaders.COLUMNS + 1)
        val U_RES = u("uRes")
        val U_ASPECT = u("uAspect")
        val U_PHASE = u("uPhase")
        val U_LEVEL = u("uLevel")
        val U_BASS = u("uBass")
        val U_MID = u("uMid")
        val U_HIGH = u("uHigh")
        val U_PULSE = u("uPulse")
        val U_REDUCED = u("uReduced")
        val U_BEAT_AGES = u("uBeatAges")
        val U_BEAT_COUNT = u("uBeatCount")
        val U_BASE = u("uBase")
        val U_PALETTE = u("uPalette")
        val U_DITHER = u("uDitherSeed")
        val BASE = squared(0x101B21)
        val PALETTE = squared(0xA2E3D0, 0xEFD29A, 0x1F6F78, 0xF2F5F4)   // mint, gold, teal, pale

        fun u(name: String) = AmbientShaders.UNIFORMS.indexOf(name).also { check(it >= 0) { name } }
        fun squared(vararg rgb: Int) = FloatArray(rgb.size * 3).also { out ->
            rgb.forEachIndexed { i, c ->
                for (k in 0..2) { val v = ((c shr (16 - 8 * k)) and 0xFF) / 255f; out[i * 3 + k] = v * v }
            }
        }
    }
}
