import XCTest
import CryptoKit
@testable import TVCompanionKit

final class CompanionTests: XCTestCase {
    func testFragmentedAndCoalescedFramesIncludingLongVarint() throws {
        let payload = Data(repeating: 7, count: 300)
        let frame = try FrameDecoder.encode(payload)
        var decoder = FrameDecoder()
        try decoder.append(frame.prefix(1)); XCTAssertNil(try decoder.next())
        try decoder.append(frame.dropFirst().prefix(100)); XCTAssertNil(try decoder.next())
        try decoder.append(frame.dropFirst(101) + (try FrameDecoder.encode(Data([4,5]))))
        XCTAssertEqual(try decoder.next(), payload)
        XCTAssertEqual(try decoder.next(), Data([4,5])); XCTAssertNil(try decoder.next())
    }
    func testOversizedAndMalformedWireFailsBeforeAllocation() throws {
        var decoder = FrameDecoder()
        try decoder.append(Data([0xff,0xff,0xff,0xff,0xff]))
        XCTAssertThrowsError(try decoder.next())
        XCTAssertThrowsError(try FrameDecoder.encode(Data(count: 65_537)))
        for bytes in [Data([0]), Data([10, 9, 1]), Data([8] + Array(repeating: 255, count: 11))] {
            XCTAssertThrowsError(try ProtoMessage(bytes))
        }
    }
    func testUnknownFieldsAreIgnoredAndLastSingularValueWins() throws {
        let message = try ProtoMessage(Data([8,1,8,2,0x1d,0,0,0,0,18,2,65,66]))
        XCTAssertEqual(message.integer(1), 2); XCTAssertEqual(message.bytes(2), Data([65,66]))
    }
    func testRemoteRequiresHandshakeAndRepliesToPing() throws {
        var remote = RemoteHandshake()
        XCTAssertThrowsError(try remote.key(.up))
        var config = ProtoMessage(); config.set(1, integer: 99)
        var outer = ProtoMessage(); outer.set(1, message: config)
        let reply = try ProtoMessage(XCTUnwrap(remote.reply(to: outer.encoded)))
        XCTAssertEqual(try reply.message(1)?.integer(1), 99)
        outer = ProtoMessage(); outer.set(40, message: ProtoMessage())
        XCTAssertNil(try remote.reply(to: outer.encoded)); XCTAssertTrue(remote.ready)
        let key = try ProtoMessage(remote.key(.up))
        XCTAssertEqual(try key.message(10)?.integer(1), 19); XCTAssertEqual(try key.message(10)?.integer(2), 3)
        var ping = ProtoMessage(); ping.set(1, integer: 1234)
        outer = ProtoMessage(); outer.set(8, message: ping)
        XCTAssertEqual(try ProtoMessage(XCTUnwrap(remote.reply(to: outer.encoded))).message(9)?.integer(1), 1234)
    }
    func testUnsupportedKeysDoNotPretendToWork() throws {
        var remote = RemoteHandshake(); var config = ProtoMessage(); config.set(1, integer: 2)
        var outer = ProtoMessage(); outer.set(1, message: config); _ = try remote.reply(to: outer.encoded)
        outer = ProtoMessage(); outer.set(40, message: ProtoMessage()); _ = try remote.reply(to: outer.encoded)
        XCTAssertThrowsError(try remote.key(.volumeUp))
        XCTAssertThrowsError(try remote.key(.power))
        XCTAssertFalse(remote.powerEnabled)
        XCTAssertNoThrow(try remote.key(.select))
        var powerRemote = RemoteHandshake(); config.set(1, integer: 2 | 32)
        outer = ProtoMessage(); outer.set(1, message: config); _ = try powerRemote.reply(to: outer.encoded)
        outer = ProtoMessage(); outer.set(40, message: ProtoMessage()); _ = try powerRemote.reply(to: outer.encoded)
        XCTAssertTrue(powerRemote.powerEnabled)
        XCTAssertEqual(try ProtoMessage(powerRemote.key(.power)).message(10)?.integer(1), 26)
    }
    func testRemoteTextUsesNegotiatedIMEAndLatestTVFieldCounters() throws {
        var remote = RemoteHandshake()
        XCTAssertThrowsError(try remote.text("hello"))
        var config = ProtoMessage(); config.set(1, integer: 1 | 2 | 4)
        var outer = ProtoMessage(); outer.set(1, message: config)
        XCTAssertEqual(try ProtoMessage(XCTUnwrap(remote.reply(to: outer.encoded))).message(1)?.integer(1), 7)
        outer = ProtoMessage(); outer.set(40, message: ProtoMessage()); _ = try remote.reply(to: outer.encoded)
        var batch = ProtoMessage(); batch.set(1, integer: 12); batch.set(2, integer: 34)
        outer = ProtoMessage(); outer.set(21, message: batch)
        XCTAssertNil(try remote.reply(to: outer.encoded))
        let sent = try ProtoMessage(remote.text("a😀"))
        let edit = try XCTUnwrap(sent.message(21))
        XCTAssertEqual(edit.integer(1), 12); XCTAssertEqual(edit.integer(2), 34)
        let object = try XCTUnwrap(edit.message(3)?.message(2))
        XCTAssertEqual(object.integer(1), 2); XCTAssertEqual(object.integer(2), 2)
        XCTAssertEqual(object.bytes(3), Data("a😀".utf8))
        XCTAssertThrowsError(try remote.text(""))
        XCTAssertThrowsError(try remote.text(String(repeating: "a", count: 513)))
        var keyOnly = RemoteHandshake(); config.set(1, integer: 2)
        outer = ProtoMessage(); outer.set(1, message: config); _ = try keyOnly.reply(to: outer.encoded)
        outer = ProtoMessage(); outer.set(40, message: ProtoMessage()); _ = try keyOnly.reply(to: outer.encoded)
        XCTAssertThrowsError(try keyOnly.text("hello"))
    }
    func testPairingNegotiatesHexInputAndRequiresSuccessfulAck() throws {
        var outer = ProtoMessage(); outer.set(2, integer: 200); outer.set(11, message: ProtoMessage())
        let options = try ProtoMessage(XCTUnwrap(PairingWire.response(to: outer.encoded).reply))
        XCTAssertEqual(try options.message(20)?.message(1)?.integer(1), 3)
        XCTAssertEqual(try options.message(20)?.message(1)?.integer(2), 6)
        outer = ProtoMessage(); outer.set(2, integer: 200); outer.set(31, message: ProtoMessage())
        XCTAssertTrue(try PairingWire.response(to: outer.encoded).needsCode)
        outer = ProtoMessage(); outer.set(2, integer: 402); outer.set(41, message: ProtoMessage())
        XCTAssertThrowsError(try PairingWire.confirm(outer.encoded))
    }
    func testPairingChallengeBindsBothKeysAndValidatesChecksum() throws {
        let client = DER.sequence(DER.integer(Data([1,2,3])) + DER.integer(Data([1,0,1])))
        let server = DER.sequence(DER.integer(Data([4,5,6])) + DER.integer(Data([3])))
        let digest = Data(SHA256.hash(data: Data([1,2,3,1,0,1,4,5,6,3,0xab,0xcd])))
        XCTAssertEqual(digest.map { String(format: "%02x", $0) }.joined(), "1c54891186fd1f95f394e04a580db625756819ea0d4f2bd13eac1c9113e13dbe")
        let code = "1CABCD"
        let secret = try ProtoMessage(PairingWire.secret(code: code, clientRSA: client, serverRSA: server))
        XCTAssertEqual(try secret.message(40)?.bytes(1), digest)
        XCTAssertThrowsError(try PairingWire.secret(code: "ZZABCD", clientRSA: client, serverRSA: server))
        XCTAssertThrowsError(try PairingWire.secret(code: code, clientRSA: server, serverRSA: client))
    }
    func testDERLengthAndLeadingZeroRoundTrip() throws {
        let modulus = Data([0x80] + Array(repeating: 1, count: 255))
        let encoded = DER.sequence(DER.integer(modulus) + DER.integer(Data([1,0,1])))
        let decoded = try DER.rsaNumbers(encoded)
        XCTAssertEqual(decoded.0, modulus); XCTAssertEqual(decoded.1, Data([1,0,1]))
        XCTAssertThrowsError(try DER.rsaNumbers(encoded.dropLast()))
    }
    func testNotificationWireStaysCompatibleAndBounded() throws {
        let message = try NotificationEnvelope(id: "test-alert2", source: .alert2, title: "Urgent", body: "Synthetic message")
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: message.encoded()) as? [String: Any])
        XCTAssertEqual(json["source"] as? String, "alert2"); XCTAssertEqual(json["id"] as? String, "test-alert2")
        XCTAssertEqual(json["durationSeconds"] as? Int, 8); XCTAssertEqual(json["ttlSeconds"] as? Int, 120)
        XCTAssertThrowsError(try NotificationEnvelope(source: .alert, title: "", body: " "))
        XCTAssertThrowsError(try NotificationEnvelope(source: .alert, title: "", body: String(repeating: "😀", count: 257)))
    }
    func testNotificationResultNeverClaimsVisibilityOrSilentlyDowngradesAlert2() throws {
        XCTAssertEqual(try NotificationResponse.parse(code: 202, data: Data("{\"status\":\"accepted\"}".utf8), kind: .alert), .queued)
        XCTAssertEqual(try NotificationResponse.parse(code: 200, data: Data("{\"status\":\"filtered\"}".utf8), kind: .alert2), .filtered)
        XCTAssertThrowsError(try NotificationResponse.parse(code: 400, data: Data("{\"status\":\"invalid_source\"}".utf8), kind: .alert2)) { XCTAssertEqual($0 as? CompanionError, .unsupported) }
        XCTAssertThrowsError(try NotificationResponse.parse(code: 202, data: Data("{\"status\":\"unknown\"}".utf8), kind: .alert))
        XCTAssertThrowsError(try NotificationResponse.parse(code: 401, data: Data(), kind: .alert2))
    }
    func testEndpointHasNoURLInjectionAndUsesNotificationPort() throws {
        let tv = try TVProfile(host: "tv.local", notificationPort: 8766)
        XCTAssertEqual(tv.notificationURL?.absoluteString, "http://tv.local:8766/api/v1/notifications")
        XCTAssertEqual(tv.remotePort, 6466); XCTAssertEqual(tv.pairingPort, 6467)
        XCTAssertThrowsError(try TVProfile(host: "http://tv.local/secret"))
    }
    func testProfileIdentitySurvivesExplicitAddressUpdateOnly() throws {
        let saved = try TVProfile(host: "192.0.2.10")
        let same = try TVProfile(host: "192.0.2.10")
        XCTAssertEqual(same.preservingIdentity(from: saved).id, saved.id)
        let changed = try TVProfile(name: saved.name, host: "192.0.2.11")
        XCTAssertNotEqual(changed.preservingIdentity(from: saved).id, saved.id)
        XCTAssertEqual(changed.preservingIdentity(from: saved, addressUpdate: true).id, saved.id)
    }
    func testRapidCommandsStayOrderedBoundedAndClearOnDisconnect() {
        var queue = RemoteCommandBuffer()
        let commands = (0..<RemoteCommandBuffer.capacity).map { index in
            RemoteCommand.key(index.isMultiple(of: 2) ? .up : .down)
        }
        for command in commands { XCTAssertTrue(queue.push(command)) }
        XCTAssertFalse(queue.push(.key(.select)))
        XCTAssertEqual((0..<commands.count).compactMap { _ in queue.pop() }, commands)
        XCTAssertNil(queue.pop())
        XCTAssertTrue(queue.push(.text("stale")))
        queue.clear()
        XCTAssertNil(queue.pop())
    }
    func testVoiceFeatureIsNegotiatedAndVoiceFramesAreBounded() throws {
        var remote = RemoteHandshake()
        var config = ProtoMessage(); config.set(1, integer: 2 | 8 | 1024)
        var outer = ProtoMessage(); outer.set(1, message: config)
        XCTAssertEqual(try ProtoMessage(XCTUnwrap(remote.reply(to: outer.encoded))).message(1)?.integer(1), 2 | 8)
        outer = ProtoMessage(); outer.set(40, message: ProtoMessage()); _ = try remote.reply(to: outer.encoded)
        XCTAssertTrue(remote.voiceEnabled)
        XCTAssertNoThrow(try remote.key(.search))
        let id: UInt64 = 23
        XCTAssertEqual(try RemoteVoiceWire.beginID(RemoteVoiceWire.begin(id)), id)
        XCTAssertEqual(try ProtoMessage(RemoteVoiceWire.end(id)).message(32)?.integer(1), id)
        let chunks = RemoteVoiceWire.chunks(Data(count: 20_481))
        XCTAssertEqual(chunks.map(\.count), [20_480, 8_192])
        var shifted = Data(count: 7)
        shifted.append(Data(repeating: 1, count: 8_192))
        shifted.removeFirst(7)
        XCTAssertEqual(RemoteVoiceWire.chunks(shifted).first, Data(repeating: 1, count: 8_192))
        XCTAssertEqual(try ProtoMessage(RemoteVoiceWire.payload(chunks[1], id: id)).message(31)?.bytes(2)?.count, 8_192)
        XCTAssertThrowsError(try RemoteVoiceWire.payload(Data(count: 8_191), id: id))
        var bad = ProtoMessage(); bad.set(1, integer: 0)
        outer = ProtoMessage(); outer.set(30, message: bad)
        XCTAssertThrowsError(try RemoteVoiceWire.beginID(outer.encoded))
        var keyOnly = RemoteHandshake(); config.set(1, integer: 2)
        outer = ProtoMessage(); outer.set(1, message: config); _ = try keyOnly.reply(to: outer.encoded)
        outer = ProtoMessage(); outer.set(40, message: ProtoMessage()); _ = try keyOnly.reply(to: outer.encoded)
        XCTAssertFalse(keyOnly.voiceEnabled)
        XCTAssertThrowsError(try keyOnly.key(.search))
    }
    func testRequestsEncodeExactlyPerContract() throws {
        func json(_ r: ControlRequest) throws -> String { String(decoding: try r.encoded(), as: UTF8.self) }
        XCTAssertEqual(try json(.status), #"{"cmd":"status","v":1}"#)
        XCTAssertEqual(try json(.ambient(open: true, look: "aurora")), #"{"cmd":"ambient.set","look":"aurora","open":true,"v":1}"#)
        XCTAssertEqual(try json(.ambient(open: false, look: nil)), #"{"cmd":"ambient.set","open":false,"v":1}"#)
        XCTAssertEqual(try json(.lightPreset("blue")), #"{"cmd":"light.preset","name":"blue","v":1}"#)
        XCTAssertEqual(try json(.airplay(available: false, confirm: true)), #"{"available":false,"cmd":"airplay.set","confirm":true,"v":1}"#)
        XCTAssertEqual(try json(.notifications(on: true)), #"{"cmd":"notifications.set","on":true,"v":1}"#)
        XCTAssertEqual(try json(.ambilightLook(style: "soft", contrast: nil)), #"{"cmd":"ambilight.look","style":"soft","v":1}"#)
        XCTAssertEqual(try json(.ambilightLook(style: nil, contrast: 50)), #"{"cmd":"ambilight.look","contrast":50,"v":1}"#)
    }
    func testPairingProofMatchesContractAndRejectsBadCodes() throws {
        let client = Data([1, 2, 3]), server = Data([4, 5, 6])
        let expected = SHA256.hash(data: Data("123456".utf8) + Data(SHA256.hash(data: client)) + Data(SHA256.hash(data: server)))
            .map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(try ControlWire.pairingProof(code: " 123456 ", clientRSA: client, serverRSA: server), expected)
        for bad in ["12345", "1234567", "12a456", "１２３４５６"] {
            XCTAssertThrowsError(try ControlWire.pairingProof(code: bad, clientRSA: client, serverRSA: server))
        }
    }
    func testResponseDecodesFullStatus() throws {
        let body = #"{"v":1,"outcome":"pending","reason":null,"status":{"ambient":{"state":"available","look":"aurora","looks":["aurora","dusk"]},"ambilight":{"state":"needs_approval"},"light":{"state":"on","preset":"blue","presets":["blue","white"]},"notifications":{"state":"listening"},"airplay":{"state":"playing","available":true}}}"#
        let r = try ControlResponse.decode(Data(body.utf8))
        XCTAssertEqual(r.outcome, .pending); XCTAssertEqual(r.status?.ambilight.state, "needs_approval")
        XCTAssertEqual(r.status?.light.preset, "blue"); XCTAssertEqual(r.status?.airplay.available, true)
        XCTAssertNil(r.status?.ambilight.styles)   // older TV: no look fields, no menu
    }
    func testAmbilightLookFieldsDecode() throws {
        let body = #"{"v":1,"outcome":"ok","status":{"ambient":{"state":"available","looks":[]},"ambilight":{"state":"running","style":"soft","styles":["cinema","responsive","soft"],"contrast":50},"light":{"state":"off","presets":[]},"notifications":{"state":"listening"},"airplay":{"state":"stopped","available":false}}}"#
        let a = try XCTUnwrap(ControlResponse.decode(Data(body.utf8)).status?.ambilight)
        XCTAssertEqual(a.style, "soft"); XCTAssertEqual(a.styles?.count, 3)
        XCTAssertEqual(a.contrast, 50)
    }
    func testUnknownStatesAndOutcomesDecodeWithoutFailing() throws {
        let body = #"{"v":1,"outcome":"teleported","status":{"ambient":{"state":"dancing","looks":[]},"ambilight":{"state":"off"},"light":{"state":"off","presets":[]},"notifications":{"state":"paused"},"airplay":{"state":"stopped","available":false}}}"#
        let r = try ControlResponse.decode(Data(body.utf8))
        XCTAssertEqual(r.outcome, .unknown); XCTAssertEqual(r.status?.ambient.state, "dancing"); XCTAssertNil(r.status?.ambient.look)
    }
    func testResponseRejectsOversizeAndWrongVersion() {
        XCTAssertThrowsError(try ControlResponse.decode(Data(count: 16_385))) { XCTAssertEqual($0 as? CompanionError, .oversizedMessage) }
        XCTAssertThrowsError(try ControlResponse.decode(Data(#"{"v":2,"outcome":"ok"}"#.utf8))) { XCTAssertEqual($0 as? CompanionError, .malformedMessage) }
        XCTAssertThrowsError(try ControlResponse.decode(Data("nope".utf8))) { XCTAssertEqual($0 as? CompanionError, .malformedMessage) }
    }
    func testDisplayedLookFollowsTheTVWhileShowingAndDropsUnknownPendingChoices() throws {
        func ambient(_ state: String, _ look: String?) throws -> ControlStatus.Ambient {
            let body = #"{"state":"\#(state)","look":\#(look.map { "\"\($0)\"" } ?? "null"),"looks":["aurora","dusk"]}"#
            return try JSONDecoder().decode(ControlStatus.Ambient.self, from: Data(body.utf8))
        }
        XCTAssertEqual(try ambient("showing", "aurora").displayedLook(pending: "dusk"), "aurora")   // TV wins while showing
        XCTAssertEqual(try ambient("available", "aurora").displayedLook(pending: "dusk"), "dusk")   // pending choice before opening
        XCTAssertEqual(try ambient("available", "aurora").displayedLook(pending: "neon"), "aurora") // unknown on this TV: dropped
        XCTAssertEqual(try ambient("available", nil).displayedLook(pending: nil), "aurora")
    }
    private func status(ambient: String = "available", ambilight: String = "off", light: String = "on", preset: String? = "blue",
                        notifications: String = "listening", airplay: String = "ready", available: Bool = true) throws -> ControlStatus {
        let p = preset.map { "\"\($0)\"" } ?? "null"
        let body = #"{"ambient":{"state":"\#(ambient)","look":"dusk","looks":["aurora","dusk"]},"ambilight":{"state":"\#(ambilight)"},"light":{"state":"\#(light)","preset":\#(p),"presets":["blue","white"]},"notifications":{"state":"\#(notifications)"},"airplay":{"state":"\#(airplay)","available":\#(available)}}"#
        return try JSONDecoder().decode(ControlStatus.self, from: Data(body.utf8))
    }
    func testTilesDescribeConfirmedStateInPlainWords() throws {
        let s = try status()
        XCTAssertEqual(s.lightTile, ControlTile(isOn: true, enabled: true, detail: "On · Blue"))
        XCTAssertEqual(s.ambientTile, ControlTile(isOn: false, enabled: true, detail: "Off · Dusk"))
        XCTAssertEqual(s.ambilightTile, ControlTile(isOn: false, enabled: true, detail: "Off"))
        XCTAssertEqual(s.notificationsTile, ControlTile(isOn: true, enabled: true, detail: "Listening"))
        XCTAssertEqual(s.airplayTile, ControlTile(isOn: true, enabled: true, detail: "Ready"))
    }
    func testTransitionalStatesShowProgressNotOn() throws {
        XCTAssertEqual(try status(ambilight: "needs_approval").ambilightTile, ControlTile(isOn: true, enabled: true, detail: "Approve on the TV", inProgress: true))
        XCTAssertEqual(try status(ambilight: "starting").ambilightTile, ControlTile(isOn: true, enabled: true, detail: "Starting…", inProgress: true))
        XCTAssertEqual(try status(ambilight: "stopping").ambilightTile, ControlTile(isOn: false, enabled: false, detail: "Stopping…", inProgress: true))
        XCTAssertEqual(try status(notifications: "starting").notificationsTile, ControlTile(isOn: true, enabled: true, detail: "Starting…", inProgress: true))
    }
    func testUnavailableAndUnknownStatesDisableOnlyThatTile() throws {
        let s = try status(ambient: "unavailable", ambilight: "not_set_up", light: "unavailable", preset: nil, notifications: "dancing")
        XCTAssertEqual(s.ambientTile, ControlTile(isOn: false, enabled: false, detail: "Play music over AirPlay"))
        XCTAssertEqual(s.ambilightTile, ControlTile(isOn: false, enabled: false, detail: "Set up on the TV first"))
        XCTAssertEqual(s.lightTile, ControlTile(isOn: false, enabled: false, detail: "Not responding"))
        XCTAssertEqual(s.notificationsTile, ControlTile(isOn: false, enabled: false, detail: "Unknown"))
        XCTAssertTrue(s.airplayTile.enabled)
    }
    func testAirPlayTileFollowsBackgroundPreferenceAndSession() throws {
        XCTAssertEqual(try status(airplay: "playing").airplayTile, ControlTile(isOn: true, enabled: true, detail: "Playing now"))
        XCTAssertEqual(try status(airplay: "stopped", available: false).airplayTile, ControlTile(isOn: false, enabled: true, detail: "Off"))
        XCTAssertEqual(try status(ambient: "showing").ambientTile, ControlTile(isOn: true, enabled: true, detail: "Showing · Dusk"))
        XCTAssertEqual(try status(light: "on", preset: nil).lightTile.detail, "On")
        XCTAssertEqual(try status(light: "off", preset: nil).lightTile, ControlTile(isOn: false, enabled: true, detail: "Off"))
    }
    func testLookIdsBecomeReadableNames() throws {
        XCTAssertEqual(ControlNames.look("chromatic_flow"), "Chromatic Flow")
        XCTAssertEqual(ControlNames.look("aurora"), "Aurora")
        let body = #"{"ambient":{"state":"showing","look":"edge_orbit","looks":["aurora","edge_orbit"]},"ambilight":{"state":"off"},"light":{"state":"off","presets":[]},"notifications":{"state":"paused"},"airplay":{"state":"stopped","available":false}}"#
        let s = try JSONDecoder().decode(ControlStatus.self, from: Data(body.utf8))
        XCTAssertEqual(s.ambientTile.detail, "Showing · Edge Orbit")
    }
}
