import Foundation
import CryptoKit

/// Quick Controls v1 (contracts/controls-v1.md): varint-framed UTF-8 JSON over mutual TLS.
public enum ControlWire {
    public static let port: UInt16 = 7380
    static let maxRequest = 4096, maxResponse = 16_384

    /// SHA-256( utf8(code) ‖ SHA-256(clientRSA) ‖ SHA-256(serverRSA) ) as lowercase hex.
    public static func pairingProof(code: String, clientRSA: Data, serverRSA: Data) throws -> String {
        let code = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard code.utf8.count == 6, code.utf8.allSatisfy({ (48...57).contains($0) }) else { throw CompanionError.invalidCode }
        let input = Data(code.utf8) + Data(SHA256.hash(data: clientRSA)) + Data(SHA256.hash(data: serverRSA))
        return SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
    }
}

/// Every command sets an explicit state; there are no toggles.
public enum ControlRequest: Equatable {
    case status, pair(proof: String)
    case ambient(open: Bool, look: String?), ambilight(on: Bool), ambilightLook(style: String?, contrast: Int?)
    case light(on: Bool), lightPreset(String), notifications(on: Bool)
    case airplay(available: Bool, confirm: Bool)

    public func encoded() throws -> Data {
        var body: [String: Any] = ["v": 1]
        switch self {
        case .status: body["cmd"] = "status"
        case .pair(let proof): body["cmd"] = "pair"; body["proof"] = proof
        case .ambient(let open, let look): body["cmd"] = "ambient.set"; body["open"] = open; if let look { body["look"] = look }
        case .ambilight(let on): body["cmd"] = "ambilight.set"; body["on"] = on
        case .ambilightLook(let style, let contrast):
            body["cmd"] = "ambilight.look"; if let style { body["style"] = style }; if let contrast { body["contrast"] = contrast }
        case .light(let on): body["cmd"] = "light.set"; body["on"] = on
        case .lightPreset(let name): body["cmd"] = "light.preset"; body["name"] = name
        case .notifications(let on): body["cmd"] = "notifications.set"; body["on"] = on
        case .airplay(let available, let confirm): body["cmd"] = "airplay.set"; body["available"] = available; body["confirm"] = confirm
        }
        let data = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys, .withoutEscapingSlashes])
        guard data.count <= ControlWire.maxRequest else { throw CompanionError.oversizedMessage }
        return data
    }
}

public enum ControlOutcome: String, Decodable {
    case ok, pending, busy, unavailable, unconfirmed, needsSetup = "needs_setup", needsApproval = "needs_approval", invalid, denied, unknown
    public init(from decoder: Decoder) throws {
        self = ControlOutcome(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
    }
}

/// States stay raw strings so a newer TV's values disable one control instead of failing the response.
public struct ControlStatus: Decodable, Equatable {
    public struct Ambient: Decodable, Equatable { public let state: String; public let look: String?; public let looks: [String] }
    public struct Light: Decodable, Equatable { public let state: String; public let preset: String?; public let presets: [String] }
    public struct Simple: Decodable, Equatable { public let state: String }
    /// Look fields are absent on TVs older than the Ambilight look command. `contrast` is 0–100 in steps of 10; 0 is Normal.
    public struct Ambilight: Decodable, Equatable {
        public let state: String
        public let style: String?, styles: [String]?, contrast: Int?
    }
    public struct AirPlay: Decodable, Equatable { public let state: String; public let available: Bool }
    public let ambient: Ambient
    public let ambilight: Ambilight
    public let light: Light
    public let notifications: Simple
    public let airplay: AirPlay
}

public struct ControlResponse: Decodable, Equatable {
    public let outcome: ControlOutcome
    public let reason: String?
    public let status: ControlStatus?

    private struct Envelope: Decodable { let v: Int }
    public static func decode(_ data: Data) throws -> ControlResponse {
        guard data.count <= ControlWire.maxResponse else { throw CompanionError.oversizedMessage }
        let decoder = JSONDecoder()
        guard let envelope = try? decoder.decode(Envelope.self, from: data), envelope.v == 1,
              let response = try? decoder.decode(ControlResponse.self, from: data) else { throw CompanionError.malformedMessage }
        return response
    }
}

public extension ControlStatus.Ambient {
    /// The look to show in a picker: the TV's look while showing; before opening, the user's pending choice if this TV offers it.
    func displayedLook(pending: String?) -> String? {
        if state == "showing" { return look ?? looks.first }
        return pending.flatMap { looks.contains($0) ? $0 : nil } ?? look ?? looks.first
    }
}
