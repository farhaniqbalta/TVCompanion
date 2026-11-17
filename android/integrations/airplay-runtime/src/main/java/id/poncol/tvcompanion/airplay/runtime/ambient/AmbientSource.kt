package id.poncol.tvcompanion.airplay.runtime.ambient

enum class AmbientStatus { OK, STALE, CLOSED }
interface AmbientSubscription : AutoCloseable { fun read(out: FloatArray): AmbientStatus; override fun close() }
fun interface AmbientCallback { fun onOpened(subscription: AmbientSubscription?) }   // always delivered on main
interface AmbientSource { fun openAmbient(callback: AmbientCallback) }
object AmbientFrame {   // float layout filled by native read
    const val SIZE = 25; const val GENERATION = 0; const val HAS_RECENT = 1; const val RMS = 2; const val PEAK = 3
    const val BANDS = 4 /* 16 values */; const val BEAT_COUNT = 20; const val BEAT_AGE_MS = 21 /* 4 values, newest first; 99000 = none; negative = not yet heard */
}
