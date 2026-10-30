import Foundation

public enum CompanionError: Error, Equatable {
    case invalidAddress, invalidCode, malformedMessage, oversizedMessage, unexpectedMessage
    case disconnected, timedOut, notPaired, certificateChanged, remoteRejected, unsupported
    case invalidNotification, unauthorized, filtered, rateLimited, receiverUnavailable, unconfirmed
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
    case volumeUp = 24, volumeDown = 25, playPause = 85, next = 87, previous = 88, mute = 164
}
public struct RemoteHandshake {
    public private(set) var ready = false
    public private(set) var features: UInt64 = 1 | 2 | 32 | 64
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
        } else if input.fields[3] != nil { throw CompanionError.remoteRejected
        } else if input.fields[40] != nil { ready = true; return nil
        } else { return nil }
        return output.encoded
    }
    public func key(_ key: RemoteKey) throws -> Data {
        guard ready else { throw CompanionError.disconnected }
        if [.volumeUp, .volumeDown, .mute].contains(key) && features & 64 == 0 { throw CompanionError.unsupported }
        var command = ProtoMessage(); command.set(1, integer: key.rawValue); command.set(2, integer: 3) // SHORT: no held key
        var outer = ProtoMessage(); outer.set(10, message: command); return outer.encoded
    }
}
