import XCTest
@testable import TVCompanionAudio

final class VoicePCMEncoderTests: XCTestCase {
    func testSyntheticToneResamplesAtCommonInputRates() throws {
        for rate in [44_100.0, 48_000.0] {
            let encoder = try VoicePCMEncoder(sampleRate: rate)
            var packets: [Data] = []
            let count = Int(rate)
            for start in stride(from: 0, to: count, by: 4_096) {
                let values = (start..<min(start + 4_096, count)).map {
                    Float(sin(2 * Double.pi * 440 * Double($0) / rate) * 0.5)
                }
                packets += try encoder.append(values)
            }
            if let last = encoder.finish() { packets.append(last) }
            XCTAssertFalse(packets.isEmpty)
            XCTAssertTrue(packets.allSatisfy { $0.count >= 8_192 && $0.count <= 20_480 && $0.count.isMultiple(of: 2) })
            let samples = packets.flatMap { packet in
                let bytes = Array(packet)
                return stride(from: 0, to: bytes.count, by: 2).map {
                    Int16(bitPattern: UInt16(bytes[$0]) | UInt16(bytes[$0 + 1]) << 8)
                }
            }
            XCTAssertGreaterThan(samples.map { abs(Int($0)) }.max() ?? 0, 5_000)
            XCTAssertLessThan(abs(samples.count - 8_000), 4_200) // Final packet can add at most 4,096 silent samples.
        }
    }
    func testShortTailPadsAfterConversion() throws {
        let encoder = try VoicePCMEncoder(sampleRate: 48_000)
        XCTAssertTrue(try encoder.append(Array(repeating: 0.25, count: 4_096)).isEmpty)
        XCTAssertEqual(encoder.finish()?.count, 8_192)
        XCTAssertNil(encoder.finish())
    }
}
