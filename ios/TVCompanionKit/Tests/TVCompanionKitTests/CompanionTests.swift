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
        XCTAssertThrowsError(try remote.key(.volumeUp)); XCTAssertNoThrow(try remote.key(.select))
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
}
