package id.poncol.tvcompanion.app.control

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class ControlWireTest {
    @Test fun proofMatchesSharedVector() = assertEquals("bafb335964d3f985b7448791c0934cfdf7d7f481cdface0b7b5094214488cbf0",
        ControlWire.proof("123456", byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)))

    @Test fun pkcs1IsSequenceOfModulusAndExponent() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val der = ControlWire.pkcs1(key)
        assertEquals(0x30, der[0].toInt() and 0xff)
        // X.509 SubjectPublicKeyInfo ends with the PKCS#1 bytes inside its BIT STRING.
        assertArrayEquals(der, key.encoded.copyOfRange(key.encoded.size - der.size, key.encoded.size))
    }

    @Test fun framesRoundTripAndRejectOversizeBeforeReading() {
        val out = ByteArrayOutputStream(); ControlWire.writeFrame(out, ByteArray(300) { 7 })
        assertEquals(300, ControlWire.readFrame(ByteArrayInputStream(out.toByteArray())).size)
        val big = ByteArrayOutputStream(); ControlWire.writeFrame(big, ByteArray(5000))
        assertThrows(IOException::class.java) { ControlWire.readFrame(ByteArrayInputStream(big.toByteArray())) }
        assertThrows(IOException::class.java) { ControlWire.readFrame(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1, -1, -1))) }
        assertThrows(IOException::class.java) { ControlWire.readFrame(ByteArrayInputStream(byteArrayOf(5, 1, 2))) }
    }

    @Test fun parsesContractRequestsAndRejectsBadOnes() {
        fun p(s: String) = ControlWire.parse(s.toByteArray())
        assertEquals(ControlCommand.Status, p("""{"v":1,"cmd":"status"}"""))
        assertEquals(ControlCommand.Ambient(true, "aurora"), p("""{"cmd":"ambient.set","look":"aurora","open":true,"v":1}"""))
        assertEquals(ControlCommand.Ambient(false, null), p("""{"cmd":"ambient.set","open":false,"v":1}"""))
        assertEquals(ControlCommand.AirPlay(false, true), p("""{"available":false,"cmd":"airplay.set","confirm":true,"v":1}"""))
        assertEquals(ControlCommand.AirPlay(true, false), p("""{"available":true,"cmd":"airplay.set","v":1}"""))
        assertEquals(ControlCommand.LightPreset("blue"), p("""{"cmd":"light.preset","name":"blue","v":1}"""))
        assertEquals(ControlCommand.AmbilightLook("soft", null), p("""{"cmd":"ambilight.look","style":"soft","v":1}"""))
        assertEquals(ControlCommand.AmbilightLook(null, 50), p("""{"cmd":"ambilight.look","contrast":50,"v":1}"""))
        assertNull(p("""{"cmd":"ambilight.look","contrast":"dramatic","v":1}"""))
        assertNull(p("""{"cmd":"ambilight.look","v":1}"""))
        assertNull(p("""{"cmd":"ambilight.look","style":1,"v":1}"""))
        assertEquals(ControlCommand.Pair("ab".repeat(32)), p("""{"cmd":"pair","proof":"${"ab".repeat(32)}","v":1}"""))
        for (bad in listOf("""{"v":2,"cmd":"status"}""", """{"v":1,"cmd":"toggle"}""", """{"v":1,"cmd":"light.set","on":"yes"}""", "nope", """{"v":1,"cmd":"pair"}"""))
            assertNull(bad, p(bad))
    }

    @Test fun responseCarriesOutcomeReasonAndStatus() {
        val json = JSONObject(String(ControlWire.response("invalid", JSONObject().put("x", 1), "would_end_playback")))
        assertEquals(1, json.getInt("v")); assertEquals("invalid", json.getString("outcome"))
        assertEquals("would_end_playback", json.getString("reason")); assertEquals(1, json.getJSONObject("status").getInt("x"))
        assertFalse(JSONObject(String(ControlWire.response("denied", null))).has("status"))
    }

    @Test fun pairingWindowExpiresAndLocksAfterThreeFailures() {
        var t = 0L; val c = byteArrayOf(1); val s = byteArrayOf(2)
        val w = PairingWindow({ t }); assertTrue(w.code.matches(Regex("\\d{6}")))
        repeat(3) { assertFalse(w.attempt("0".repeat(64), c, s)) }
        assertFalse(w.open); assertFalse(w.attempt(ControlWire.proof(w.code, c, s), c, s))
        val late = PairingWindow({ t }); t = 120_001
        assertFalse(late.attempt(ControlWire.proof(late.code, c, s), c, s))
        t = 0; val ok = PairingWindow({ t })
        assertTrue(ok.attempt(ControlWire.proof(ok.code, c, s), c, s)); assertFalse(ok.open)
    }
}
