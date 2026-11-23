package id.poncol.tvcompanion.airplay.runtime.ambient

/** Assembles GLSL ES 3.00 sources: shared prelude + a look body loaded from assets/ambient. */
object AmbientShaders {
    const val CONTRACT = "// ambient-contract: 1"
    const val DEFAULT_LOOK = "aurora"
    val LOOKS: List<String> = listOf("aurora", "strings", "dusk", "chromatic_flow", "edge_orbit", "bounce", "mirror_bars")
    const val COLUMNS = 128

    data class Sources(val vertex: String, val fragment: String, val look: String)

    val UNIFORMS: List<String> = listOf(
        "uRes", "uAspect", "uPhase", "uLevel", "uBass", "uMid", "uHigh", "uPulse", "uReduced",
        "uBeatAges", "uBeatCount", "uBase", "uPalette", "uDitherSeed", "uBands",
    )

    private val SHARED = """uniform highp vec2 uRes;
uniform highp float uAspect;
uniform highp vec4 uPhase;
uniform highp float uLevel;
uniform highp float uBass;
uniform highp float uMid;
uniform highp float uHigh;
uniform highp float uPulse;
uniform highp float uReduced;
uniform highp vec4 uBeatAges;
uniform highp float uBeatCount;
uniform highp vec3 uBase;
uniform highp vec3 uPalette[4];
uniform highp float uDitherSeed;
uniform highp vec4 uBands[4];   // 16 smoothed log bands, 40 Hz..16 kHz, 0..1
float sq(float x){ return x*x; }
highp float hash12(highp vec2 p){ highp vec3 p3=fract(vec3(p.xyx)*0.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }
"""

    private val VERTEX_HEAD = "#version 300 es\nprecision highp float;\n" + SHARED + """in vec2 aPos;
out highp vec2 vP;
"""

    private const val DEFAULT_VERTEX_BODY =
        "void main(){ vP = vec2(aPos.x * 0.5 * uAspect, aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0); }\n"

    private val FRAGMENT_HEAD = "#version 300 es\nprecision mediump float;\n" + SHARED + """in highp vec2 vP;
out vec4 fragColor;
vec3 finish(vec3 col, highp vec2 p){
  col *= 1.0 + 0.08*uPulse;
  col *= 1.0 - 0.35*dot(p,p);
  col  = col/(1.0+0.4*col);
  col  = sqrt(max(col,0.0));
  col += (hash12(gl_FragCoord.xy + uDitherSeed) - 0.5)/255.0;
  return col;
}
"""

    private val BUILTIN = CONTRACT + """

void main(){
  float g = clamp(vP.y + 0.5, 0.0, 1.0);
  vec3 col = mix(uBase, vec3(0.05, 0.30, 0.32), g) * (0.6 + 0.8 * uLevel);
  fragColor = vec4(finish(col, vP), 1.0);
}
"""

    fun resolve(saved: String?): String = saved?.takeIf { it in LOOKS } ?: DEFAULT_LOOK

    fun cycle(current: String, step: Int): String =
        LOOKS[Math.floorMod(LOOKS.indexOf(resolve(current)) + step, LOOKS.size)]

    fun assemble(look: String, read: (String) -> String?): Sources {
        val frag = read("$look.frag")?.takeIf { it.startsWith(CONTRACT) }
            ?: return Sources(VERTEX_HEAD + DEFAULT_VERTEX_BODY, FRAGMENT_HEAD + BUILTIN, "builtin")
        val vert = read("$look.vert")?.takeIf { it.startsWith(CONTRACT) }
        return Sources(VERTEX_HEAD + (vert ?: DEFAULT_VERTEX_BODY), FRAGMENT_HEAD + frag, look)
    }
}
