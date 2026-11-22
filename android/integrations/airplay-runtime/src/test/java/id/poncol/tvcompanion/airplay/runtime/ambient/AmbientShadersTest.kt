package id.poncol.tvcompanion.airplay.runtime.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientShadersTest {
    private val dir = java.io.File("src/main/assets/ambient")
    private val read: (String) -> String? = { java.io.File(dir, it).takeIf { f -> f.exists() }?.readText() }
    @Test fun shippedLooksCarryContractHeader() {
        val files = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("aurora.vert", "aurora.frag", "dusk.frag", "strings.vert", "strings.frag",
            "chromatic_flow.frag", "edge_orbit.frag", "bounce.frag"), files)
        dir.listFiles()!!.forEach { assertEquals(it.name, AmbientShaders.CONTRACT, it.readLines().first()) }
    }
    @Test fun stringsKeepsBeatMathPerVertexAndHighp() {
        read("strings.vert")!!.let {
            assertTrue(it.contains("highp float age")); assertTrue(it.contains("hash12(vec2(")); assertTrue(it.contains("cos("))
        }
        read("strings.frag")!!.let {
            // Regression guard: per-pixel beat math was too much ALU for Mali-G31 at 720p (measured;
            // 2 strings x 4 branched beats = 15 fps). The pixel stage must stay hash-, trig- and branch-free for beats.
            assertFalse(it.contains("hash12(")); assertFalse(it.contains("cos(")); assertFalse(it.contains("if("))
            assertFalse(it.contains("uBeatCount"))
        }
    }
    @Test fun stringsUsesItsOwnVertexStage() {
        assertTrue(AmbientShaders.assemble("strings", read).vertex.contains("flat out highp vec4 vPid"))
    }
    @Test fun auroraUsesItsOwnVertexStage() {
        val s = AmbientShaders.assemble("aurora", read)
        assertEquals("aurora", s.look); assertTrue(s.vertex.contains("out highp vec3 vY"))
        assertTrue(s.vertex.startsWith("#version 300 es")); assertTrue(s.fragment.startsWith("#version 300 es"))
        AmbientShaders.UNIFORMS.forEach { assertTrue(it, s.vertex.contains(it) && s.fragment.contains(it)) }
    }
    @Test fun fragmentOnlyLookGetsDefaultVertex() {
        listOf("dusk", "chromatic_flow", "edge_orbit").forEach { look ->
            val sources = AmbientShaders.assemble(look, read)
            assertEquals(look, sources.look)
            assertTrue(sources.vertex.contains("vP = vec2(aPos.x"))
            listOf(sources.vertex, sources.fragment).forEach { source ->
                assertEquals(listOf("#version 300 es"), source.lines().filter { it.startsWith("#version") })
            }
        }
    }
    @Test fun brokenLookFallsBack() {
        assertEquals("builtin", AmbientShaders.assemble("missing", read).look)
        assertEquals("builtin", AmbientShaders.assemble("x") { "void main(){}" }.look)   // no header
    }
    @Test fun noNonIntegerPhaseMultipliers() {
        val phase = """uPhase\.[xyzw]"""
        val fraction = """\d*\.\d*[1-9]\d*"""
        // Also catch "uPhase.x / TWO_PI * 0.3": a fractional cycle count jumps when the clock wraps.
        val bad = Regex("""$phase\s*\*\s*$fraction|$fraction\s*\*\s*$phase|$phase\s*/\s*TWO_PI\s*\*\s*$fraction""")
        dir.listFiles()!!.forEach { assertNull(it.name, bad.find(it.readText())) }
    }
    @Test fun cycleWrapsBothWays() {
        val order = listOf("aurora", "strings", "dusk", "chromatic_flow", "edge_orbit", "bounce")
        assertEquals(order, AmbientShaders.LOOKS)
        order.zipWithNext().plus("bounce" to "aurora").forEach { (from, to) ->
            assertEquals(to, AmbientShaders.cycle(from, 1))
            assertEquals(from, AmbientShaders.cycle(to, -1))
        }
        order.forEach { assertEquals(it, AmbientShaders.resolve(it)) }
    }
    @Test fun unknownLookFallsBackToDefault() {
        assertEquals("aurora", AmbientShaders.resolve(null))
        assertEquals("aurora", AmbientShaders.resolve("nope"))
        assertEquals("dusk", AmbientShaders.resolve("dusk"))
        assertEquals("strings", AmbientShaders.cycle("nope", 1))
    }
    @Test fun everyListedLookHasAnAsset() {
        AmbientShaders.LOOKS.forEach { assertTrue(it, java.io.File(dir, "$it.frag").exists()) }
    }
    private fun allSources() = (AmbientShaders.LOOKS + "missing").map { AmbientShaders.assemble(it, read) }
        .flatMap { listOf(it.look to ("vert" to it.vertex), it.look to ("frag" to it.fragment)) }

    @Test fun precisionPrecedesFirstFloatDeclaration() {
        allSources().forEach { (look, st) ->
            assertTrue("$look ${st.first}", st.second.lines()[1].startsWith("precision "))
        }
    }

    @Test fun assembledShadersCompileWithGlslc() {
        val glslc = System.getenv("AMBIENT_GLSLC")
        org.junit.Assume.assumeTrue(glslc != null)
        allSources().forEach { (look, st) ->
            val (stage, src) = st
            val f = java.io.File.createTempFile("ambient-$look-", ".$stage")
            val out = java.io.File.createTempFile("ambient-$look-", ".spv")
            f.writeText(src.replaceFirst("#version 300 es", "#version 310 es"))
            val p = ProcessBuilder(glslc, "-x", "glsl", "--target-env=opengl", "-std=310es",
                "-fshader-stage=${if (stage == "vert") "vert" else "frag"}", "-fauto-map-locations",
                "-c", f.path, "-o", out.path).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            assertEquals("$look $stage: $log", 0, p.waitFor())
            f.delete(); out.delete()
        }
    }
}
