package id.poncol.tvcompanion.airplay.runtime.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientShadersTest {
    private val dir = java.io.File("src/main/assets/ambient")
    private val read: (String) -> String? = { java.io.File(dir, it).takeIf { f -> f.exists() }?.readText() }
    @Test fun shippedLooksCarryContractHeader() {
        val files = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("aurora.vert", "aurora.frag", "ripples.frag", "dusk.frag"), files)
        dir.listFiles()!!.forEach { assertTrue(it.name, it.readText().startsWith(AmbientShaders.CONTRACT)) }
    }
    @Test fun auroraUsesItsOwnVertexStage() {
        val s = AmbientShaders.assemble("aurora", read)
        assertEquals("aurora", s.look); assertTrue(s.vertex.contains("out highp vec3 vY"))
        assertTrue(s.vertex.startsWith("#version 300 es")); assertTrue(s.fragment.startsWith("#version 300 es"))
        AmbientShaders.UNIFORMS.forEach { assertTrue(it, s.vertex.contains(it) && s.fragment.contains(it)) }
    }
    @Test fun fragmentOnlyLookGetsDefaultVertex() {
        assertTrue(AmbientShaders.assemble("dusk", read).vertex.contains("vP = vec2(aPos.x"))
    }
    @Test fun brokenLookFallsBack() {
        assertEquals("builtin", AmbientShaders.assemble("missing", read).look)
        assertEquals("builtin", AmbientShaders.assemble("x") { "void main(){}" }.look)   // no header
    }
    @Test fun noNonIntegerPhaseMultipliers() {
        val bad = Regex("""uPhase\.[xyzw]\s*\*\s*\d*\.\d*[1-9]""")
        dir.listFiles()!!.forEach { assertNull(it.name, bad.find(it.readText())) }
    }
}
