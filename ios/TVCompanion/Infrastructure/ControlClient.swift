import Foundation
import CryptoKit
import TVCompanionKit

/// What the Controls tab needs from the TV; the preview uses a fake.
protocol ControlService: AnyObject {
    func isPaired(_ tv: TVProfile) -> Bool
    func pair(_ tv: TVProfile, code: String) async throws
    func send(_ request: ControlRequest, to tv: TVProfile) async throws -> ControlResponse
    func forget(_ tv: TVProfile)
}

/// One mutual-TLS connection per request (contracts/controls-v1.md). Pins live in Keychain only.
final class ControlClient: ControlService {
    private let credentials: CredentialStore
    private let identities = RemoteIdentityStore()
    init(credentials: CredentialStore) { self.credentials = credentials }

    private func account(_ tv: TVProfile) -> String { "controls-pin-" + tv.id.uuidString }
    private func pin(_ tv: TVProfile) -> Data? { (try? credentials.data(account(tv))).flatMap { $0.isEmpty ? nil : $0 } }

    func isPaired(_ tv: TVProfile) -> Bool { pin(tv) != nil }

    func pair(_ tv: TVProfile, code: String) async throws {
        _ = try ControlWire.pairingProof(code: code, clientRSA: Data(), serverRSA: Data()) // Reject a bad code before any network use.
        let identity = try identities.loadOrCreate()
        let channel = try TLSChannel(host: tv.host, port: ControlWire.port, identity: identity.identity, expectedServerKey: nil)
        defer { channel.close() }
        try await channel.connect()
        guard let server = channel.serverRSA else { throw CompanionError.disconnected }
        let proof = try ControlWire.pairingProof(code: code, clientRSA: identity.publicRSA, serverRSA: server)
        let response = try await exchange(.pair(proof: proof), on: channel)
        switch response.outcome {
        case .ok: try credentials.save(Data(SHA256.hash(data: server)), account: account(tv))
        case .denied: throw CompanionError.invalidCode
        default: throw CompanionError.remoteRejected
        }
    }

    func send(_ request: ControlRequest, to tv: TVProfile) async throws -> ControlResponse {
        guard let pin = pin(tv) else { throw CompanionError.notPaired }
        let identity = try identities.loadOrCreate()
        let channel = try TLSChannel(host: tv.host, port: ControlWire.port, identity: identity.identity, expectedServerKey: pin)
        defer { channel.close() }
        try await channel.connect()
        let response = try await exchange(request, on: channel)
        if response.outcome == .denied { throw CompanionError.remoteRejected }
        return response
    }

    // shortcut: CredentialStore has no delete, so an empty pin means unpaired; add delete if more secrets need removal.
    func forget(_ tv: TVProfile) { try? credentials.save(Data(), account: account(tv)) }

    private func exchange(_ request: ControlRequest, on channel: TLSChannel) async throws -> ControlResponse {
        try await channel.write(try request.encoded())
        return try ControlResponse.decode(try await channel.readFrame(timeout: 10))
    }
}
