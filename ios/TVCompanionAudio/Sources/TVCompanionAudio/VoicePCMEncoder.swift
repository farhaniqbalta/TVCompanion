import AVFoundation
import Foundation

public enum VoiceAudioError: Error { case invalidFormat, conversionFailed }

/// Converts copied mono microphone samples outside the audio render callback.
/// Complete 8 KiB chunks avoid sending small speech packets padded with long silence.
public final class VoicePCMEncoder {
    public static let minimumChunk = 8 * 1024
    private let converter: AVAudioConverter
    private let inputFormat: AVAudioFormat
    private let outputFormat: AVAudioFormat
    private var pending = Data()

    public init(sampleRate: Double) throws {
        guard sampleRate.isFinite, sampleRate > 0,
              let input = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate,
                                        channels: 1, interleaved: false),
              let output = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 8_000,
                                         channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: input, to: output)
        else { throw VoiceAudioError.invalidFormat }
        inputFormat = input; outputFormat = output; self.converter = converter
    }

    public func append(_ samples: [Float]) throws -> [Data] {
        guard !samples.isEmpty else { return [] }
        let input = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: AVAudioFrameCount(samples.count))!
        input.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { source in
            input.floatChannelData![0].update(from: source.baseAddress!, count: source.count)
        }
        let capacity = AVAudioFrameCount(ceil(Double(samples.count) * 8_000 / inputFormat.sampleRate) + 128)
        let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity)!
        var supplied = false
        var conversionError: NSError?
        _ = converter.convert(to: output, error: &conversionError) { _, status in
            if supplied { status.pointee = .noDataNow; return nil }
            supplied = true; status.pointee = .haveData; return input
        }
        if conversionError != nil { throw VoiceAudioError.conversionFailed }
        if output.frameLength > 0 {
            pending.append(Data(bytes: output.int16ChannelData![0], count: Int(output.frameLength) * 2))
        }
        var chunks: [Data] = []
        while pending.count >= Self.minimumChunk {
            chunks.append(Data(pending.prefix(Self.minimumChunk)))
            pending.removeFirst(Self.minimumChunk)
        }
        return chunks
    }

    /// Pads only the final short piece, after all available microphone samples.
    public func finish() -> Data? {
        guard !pending.isEmpty else { return nil }
        var last = pending
        if last.count < Self.minimumChunk { last.append(Data(count: Self.minimumChunk - last.count)) }
        pending.removeAll()
        return last
    }
}
