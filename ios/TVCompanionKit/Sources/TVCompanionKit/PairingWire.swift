import Foundation
import CryptoKit

public enum PairingWire {
    private static func outer(_ field: Int, _ message: ProtoMessage) -> Data {
        var result = ProtoMessage(); result.set(1, integer: 2); result.set(2, integer: 200); result.set(field, message: message)
        return result.encoded
    }
    public static func request() -> Data {
        var message = ProtoMessage(); message.set(1, string: "atvremote"); message.set(2, string: "TVCompanion")
        return outer(10, message)
    }
    public static func response(to raw: Data) throws -> (reply: Data?, needsCode: Bool) {
        let message = try ProtoMessage(raw)
        guard message.integer(2) == 200 else { throw CompanionError.remoteRejected }
        var encoding = ProtoMessage(); encoding.set(1, integer: 3); encoding.set(2, integer: 6)
        if message.fields[11] != nil {
            var options = ProtoMessage(); options.set(1, message: encoding); options.set(3, integer: 1)
            return (outer(20, options), false)
        }
        if message.fields[20] != nil {
            var config = ProtoMessage(); config.set(1, message: encoding); config.set(2, integer: 1)
            return (outer(30, config), false)
        }
        if message.fields[31] != nil { return (nil, true) }
        throw CompanionError.unexpectedMessage
    }
    public static func secret(code: String, clientRSA: Data, serverRSA: Data) throws -> Data {
        let code = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard code.count == 6 && code.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else { throw CompanionError.invalidCode }
        let client = try DER.rsaNumbers(clientRSA), server = try DER.rsaNumbers(serverRSA)
        var components = client.0 + client.1 + server.0 + server.1
        let bytes = Array(code.utf8)
        func hex(_ offset: Int) -> UInt8 { UInt8(String(bytes: bytes[offset..<offset+2], encoding: .utf8)!, radix: 16)! }
        components.append(hex(2)); components.append(hex(4))
        let digest = Data(SHA256.hash(data: components))
        guard digest.first == hex(0) else { throw CompanionError.invalidCode }
        var secret = ProtoMessage(); secret.set(1, bytes: digest); return outer(40, secret)
    }
    public static func confirm(_ raw: Data) throws {
        let message = try ProtoMessage(raw)
        guard message.integer(2) == 200, message.fields[41] != nil else { throw CompanionError.remoteRejected }
    }
}

/// Bounded DER primitives for our certificate and RSA public-key fields; not a general ASN.1 framework.
public enum DER {
    public static func value(_ tag: UInt8, _ data: Data) -> Data {
        let length: Data
        if data.count < 128 { length = Data([UInt8(data.count)]) }
        else {
            var number = data.count; var bytes = [UInt8]()
            while number > 0 { bytes.insert(UInt8(number & 255), at: 0); number >>= 8 }
            length = Data([0x80 | UInt8(bytes.count)]) + Data(bytes)
        }
        return Data([tag]) + length + data
    }
    public static func sequence(_ data: Data) -> Data { value(0x30, data) }
    public static func integer(_ bytes: Data) -> Data {
        var number = bytes
        while number.count > 1 && number.first == 0 { number.removeFirst() }
        if (number.first ?? 0) & 128 != 0 { number.insert(0, at: 0) }
        return value(2, number.isEmpty ? Data([0]) : number)
    }
    public static func rsaNumbers(_ data: Data) throws -> (Data, Data) {
        guard data.count <= 8192 else { throw CompanionError.oversizedMessage }
        let bytes = Array(data); var offset = 0
        func item(_ tag: UInt8) throws -> Data {
            guard offset + 2 <= bytes.count, bytes[offset] == tag else { throw CompanionError.malformedMessage }
            offset += 1; var count = Int(bytes[offset]); offset += 1
            if count & 128 != 0 {
                let width = count & 127
                guard width > 0 && width <= 4 && offset + width <= bytes.count else { throw CompanionError.malformedMessage }
                count = 0
                for _ in 0..<width { count = count * 256 + Int(bytes[offset]); offset += 1 }
            }
            guard count <= bytes.count - offset else { throw CompanionError.malformedMessage }
            let result = Data(bytes[offset..<offset + count]); offset += count; return result
        }
        let sequence = try item(0x30)
        guard offset == bytes.count else { throw CompanionError.malformedMessage }
        let nested = Array(sequence); var cursor = 0
        func number() throws -> Data {
            guard cursor + 2 <= nested.count, nested[cursor] == 2 else { throw CompanionError.malformedMessage }
            cursor += 1; var length = Int(nested[cursor]); cursor += 1
            if length & 128 != 0 {
                let width = length & 127; guard width > 0 && width <= 2 && cursor + width <= nested.count else { throw CompanionError.malformedMessage }
                length = 0
                for _ in 0..<width { length = length * 256 + Int(nested[cursor]); cursor += 1 }
            }
            guard length > 0 && length <= nested.count - cursor, nested[cursor] & 128 == 0 else { throw CompanionError.malformedMessage }
            var result = Data(nested[cursor..<cursor + length]); cursor += length
            while result.count > 1 && result.first == 0 { result.removeFirst() }
            return result
        }
        let modulus = try number(), exponent = try number()
        guard cursor == nested.count else { throw CompanionError.malformedMessage }
        return (modulus, exponent)
    }
}
