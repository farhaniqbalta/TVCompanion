import Foundation

public enum CompanionError: Error, Equatable {
    case invalidAddress, invalidCode, malformedMessage, oversizedMessage, unexpectedMessage
    case disconnected, timedOut, notPaired, certificateChanged, remoteRejected, unsupported
    case invalidNotification, invalidText, unauthorized, filtered, rateLimited, receiverUnavailable, unconfirmed
}

/// A deliberately narrow protobuf wire reader/writer for Remote v2. Unknown fields are skipped.
/// All lengths, varints and field counts are bounded before allocation or indexing.
public struct ProtoMessage: Equatable {
    public enum Value: Equatable { case integer(UInt64), bytes(Data) }
    public private(set) var fields: [Int: Value] = [:]
    public init() {}
    public init(_ data: Data) throws {
        guard data.count <= 65_536 else { throw CompanionError.oversizedMessage }
        let bytes = Array(data); var offset = 0; var count = 0
        func varint() throws -> UInt64 {
            var value: UInt64 = 0
            for index in 0..<10 {
                guard offset < bytes.count else { throw CompanionError.malformedMessage }
                let byte = bytes[offset]; offset += 1
                if index == 9 && byte > 1 { throw CompanionError.malformedMessage }
                value |= UInt64(byte & 127) << (index * 7)
                if byte & 128 == 0 { return value }
            }
            throw CompanionError.malformedMessage
        }
        while offset < bytes.count {
            count += 1; guard count <= 256 else { throw CompanionError.oversizedMessage }
            let key = try varint(); let field = Int(key >> 3)
            guard field > 0 && field <= 536_870_911 else { throw CompanionError.malformedMessage }
            switch key & 7 {
            case 0: fields[field] = .integer(try varint())
            case 2:
                let length = try varint()
                guard length <= 65_536 && length <= UInt64(bytes.count - offset) else { throw CompanionError.malformedMessage }
                fields[field] = .bytes(Data(bytes[offset..<offset + Int(length)])); offset += Int(length)
            case 1, 5:
                let size = key & 7 == 1 ? 8 : 4
                guard size <= bytes.count - offset else { throw CompanionError.malformedMessage }; offset += size
            default: throw CompanionError.malformedMessage
            }
        }
    }
    public func integer(_ field: Int) -> UInt64? { if case .integer(let value) = fields[field] { return value }; return nil }
    public func bytes(_ field: Int) -> Data? { if case .bytes(let value) = fields[field] { return value }; return nil }
    public func message(_ field: Int) throws -> ProtoMessage? { try bytes(field).map { try ProtoMessage($0) } }
    public mutating func set(_ field: Int, integer: UInt64) { fields[field] = .integer(integer) }
    public mutating func set(_ field: Int, bytes: Data) { fields[field] = .bytes(bytes) }
    public mutating func set(_ field: Int, string: String) { set(field, bytes: Data(string.utf8)) }
    public mutating func set(_ field: Int, message: ProtoMessage) { set(field, bytes: message.encoded) }
    public var encoded: Data {
        var result = Data()
        for field in fields.keys.sorted() {
            switch fields[field]! {
            case .integer(let value): result += Self.varint(UInt64(field) << 3); result += Self.varint(value)
            case .bytes(let value): result += Self.varint(UInt64(field) << 3 | 2); result += Self.varint(UInt64(value.count)); result += value
            }
        }
        return result
    }
    public static func varint(_ value: UInt64) -> Data {
        var result = Data(); var remaining = value
        repeat {
            let byte = UInt8(remaining & 127); remaining >>= 7
            result.append(byte | (remaining > 0 ? 128 : 0))
        } while remaining > 0
        return result
    }
}

public struct FrameDecoder {
    private var buffer = Data()
    public init() {}
    public mutating func append(_ data: Data) throws {
        guard buffer.count + data.count <= 131_082 else { throw CompanionError.oversizedMessage }
        buffer.append(data)
    }
    public mutating func next() throws -> Data? {
        let bytes = Array(buffer); var length: UInt64 = 0
        for i in 0..<min(bytes.count, 5) {
            length |= UInt64(bytes[i] & 127) << (i * 7)
            if length > 65_536 { throw CompanionError.oversizedMessage }
            if bytes[i] & 128 == 0 {
                let header = i + 1
                guard bytes.count >= header + Int(length) else { return nil }
                let value = Data(bytes[header..<header + Int(length)])
                buffer.removeFirst(header + Int(length)); return value
            }
            if i == 4 { throw CompanionError.malformedMessage }
        }
        return nil
    }
    public static func encode(_ payload: Data) throws -> Data {
        guard payload.count <= 65_536 else { throw CompanionError.oversizedMessage }
        return ProtoMessage.varint(UInt64(payload.count)) + payload
    }
}

public enum RemoteKey: UInt64, CaseIterable {
    case home = 3, back = 4, up = 19, down = 20, left = 21, right = 22, select = 23
    case volumeUp = 24, volumeDown = 25, power = 26, enter = 66, delete = 67
    case search = 84, playPause = 85, next = 87, previous = 88, mute = 164
}
public enum RemoteCommand: Equatable {
    case key(RemoteKey), text(String)
}

/// A small ordered queue for in-flight remote input. Callers clear it on disconnect.
public struct RemoteCommandBuffer {
    public static let capacity = 16
    private var commands: [RemoteCommand] = []
    public init() {}
    public var count: Int { commands.count }
    @discardableResult public mutating func push(_ command: RemoteCommand) -> Bool {
        guard commands.count < Self.capacity else { return false }
        commands.append(command)
        return true
    }
    public mutating func pop() -> RemoteCommand? {
        guard !commands.isEmpty else { return nil }
        return commands.removeFirst()
    }
    public mutating func clear() { commands.removeAll() }
}

public struct RemoteHandshake {
    public private(set) var ready = false
    public private(set) var features: UInt64 = 1 | 2 | 4 | 8 | 32 | 64
    public var voiceEnabled: Bool { ready && features & 8 != 0 }
    public var powerEnabled: Bool { ready && features & 32 != 0 }
    private var imeCounter: UInt64 = 0
    private var imeFieldCounter: UInt64 = 0
    public init() {}
    public mutating func reply(to data: Data) throws -> Data? {
        let input = try ProtoMessage(data); var output = ProtoMessage()
        if let configure = try input.message(1) {
            features &= configure.integer(1) ?? 0
            guard features & 2 != 0 else { throw CompanionError.unsupported }
            var device = ProtoMessage(); device.set(3, integer: 1); device.set(4, string: "1")
            device.set(5, string: "TVCompanion"); device.set(6, string: "0.1.0")
            var config = ProtoMessage(); config.set(1, integer: features); config.set(2, message: device)
            output.set(1, message: config)
        } else if input.fields[2] != nil {
            var active = ProtoMessage(); active.set(1, integer: features); output.set(2, message: active)
        } else if let ping = try input.message(8) {
            var pong = ProtoMessage(); pong.set(1, integer: ping.integer(1) ?? 0); output.set(9, message: pong)
        } else if let edit = try input.message(21) {
            imeCounter = edit.integer(1) ?? 0
            imeFieldCounter = edit.integer(2) ?? 0
            return nil
        } else if input.fields[3] != nil { throw CompanionError.remoteRejected
        } else if input.fields[40] != nil { ready = true; return nil
        } else { return nil }
        return output.encoded
    }
    public func key(_ key: RemoteKey) throws -> Data {
        guard ready else { throw CompanionError.disconnected }
        if key == .search && !voiceEnabled { throw CompanionError.unsupported }
        if key == .power && !powerEnabled { throw CompanionError.unsupported }
        if [.volumeUp, .volumeDown, .mute].contains(key) && features & 64 == 0 { throw CompanionError.unsupported }
        var command = ProtoMessage(); command.set(1, integer: key.rawValue); command.set(2, integer: 3) // SHORT: no held key
        var outer = ProtoMessage(); outer.set(10, message: command); return outer.encoded
    }
    public func text(_ value: String) throws -> Data {
        guard ready else { throw CompanionError.disconnected }
        guard features & 4 != 0 else { throw CompanionError.unsupported }
        guard !value.isEmpty, value.utf16.count <= 512 else { throw CompanionError.invalidText }
        // Android IME selection offsets count UTF-16 code units.
        let offset = UInt64(value.utf16.count - 1)
        var object = ProtoMessage(); object.set(1, integer: offset); object.set(2, integer: offset); object.set(3, string: value)
        var editInfo = ProtoMessage(); editInfo.set(1, integer: 1); editInfo.set(2, message: object)
        var batch = ProtoMessage(); batch.set(1, integer: imeCounter); batch.set(2, integer: imeFieldCounter)
        batch.set(3, message: editInfo)
        var outer = ProtoMessage(); outer.set(21, message: batch); return outer.encoded
    }
}

/// Voice fields from Remote v2. PCM bytes are never decoded or logged here.
public enum RemoteVoiceWire {
    public static let minimumChunk = 8 * 1024
    public static let maximumChunk = 20 * 1024
    public static func beginID(_ data: Data) throws -> UInt64? {
        let message = try ProtoMessage(data)
        guard let begin = try message.message(30) else { return nil }
        guard let id = begin.integer(1), id > 0, id <= UInt64(Int32.max) else {
            throw CompanionError.malformedMessage
        }
        return id
    }
    public static func begin(_ id: UInt64) throws -> Data { try envelope(30, id: id) }
    public static func end(_ id: UInt64) throws -> Data { try envelope(32, id: id) }
    public static func payload(_ samples: Data, id: UInt64) throws -> Data {
        guard samples.count >= minimumChunk, samples.count <= maximumChunk,
              samples.count.isMultiple(of: 2) else { throw CompanionError.invalidText }
        var inner = try session(id)
        inner.set(2, bytes: samples)
        var outer = ProtoMessage(); outer.set(31, message: inner)
        return outer.encoded
    }
    public static func chunks(_ samples: Data) -> [Data] {
        guard !samples.isEmpty else { return [] }
        var result: [Data] = []
        for start in stride(from: 0, to: samples.count, by: maximumChunk) {
            let lower = samples.index(samples.startIndex, offsetBy: start)
            let upper = samples.index(lower, offsetBy: min(maximumChunk, samples.count - start))
            var part = Data(samples[lower..<upper])
            if part.count < minimumChunk { part.append(Data(count: minimumChunk - part.count)) }
            result.append(part)
        }
        return result
    }
    private static func session(_ id: UInt64) throws -> ProtoMessage {
        guard id > 0, id <= UInt64(Int32.max) else { throw CompanionError.malformedMessage }
        var inner = ProtoMessage(); inner.set(1, integer: id)
        return inner
    }
    private static func envelope(_ field: Int, id: UInt64) throws -> Data {
        var outer = ProtoMessage(); outer.set(field, message: try session(id))
        return outer.encoded
    }
}
