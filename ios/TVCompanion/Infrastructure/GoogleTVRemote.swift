import Foundation
import CryptoKit
import TVCompanionKit

/// Separate endpoint from notification HTTP. A paired TV's public-key pin is required for control.
actor GoogleTVRemote {
    private let secrets: CredentialStore
    private let identities = RemoteIdentityStore()
    private var pairing: TLSChannel?
    private var remote: TLSChannel?
    private var identity: RemoteIdentity?
    private var profile: TVProfile?
    private var handshake = RemoteHandshake()
    private var receiveTask: Task<Void, Never>?
    private var generation = UUID()
    init(secrets: CredentialStore) { self.secrets = secrets }
    private func pinAccount(_ profile: TVProfile) -> String { "remote-pin-" + profile.id.uuidString }
    func isPaired(_ profile: TVProfile) -> Bool { (try? secrets.data(pinAccount(profile))) != nil }
    func beginPairing(_ profile: TVProfile) async throws {
        disconnect()
        let token = generation
        let local = try identities.loadOrCreate()
        let channel = try TLSChannel(host: profile.host, port: profile.pairingPort, identity: local.identity, expectedServerKey: nil)
        pairing = channel; identity = local; self.profile = profile
        do {
            try await channel.connect(); try await channel.write(PairingWire.request())
            for _ in 0..<8 {
                let step = try PairingWire.response(to: await channel.readFrame(timeout: 10))
                guard generation == token else { throw CancellationError() }
                if step.needsCode { return }
                if let reply = step.reply { try await channel.write(reply) }
            }
            throw CompanionError.unexpectedMessage
        } catch { channel.close(); if pairing === channel { pairing = nil }; throw error }
    }
    func finishPairing(code: String) async throws {
        guard let channel = pairing, let local = identity, let tv = profile, let server = channel.serverRSA else { throw CompanionError.disconnected }
        let token = generation
        // Bad input leaves the pairing socket available so the displayed code can be corrected.
        let secret = try PairingWire.secret(code: code, clientRSA: local.publicRSA, serverRSA: server)
        do {
            try await channel.write(secret); try PairingWire.confirm(await channel.readFrame(timeout: 10))
            guard generation == token else { throw CancellationError() }
            try secrets.save(Data(SHA256.hash(data: server)), account: pinAccount(tv))
            channel.close(); pairing = nil
        } catch { channel.close(); if pairing === channel { pairing = nil }; throw error }
    }
    func connect(_ profile: TVProfile, changed: @escaping @Sendable (Bool) -> Void) async throws {
        disconnect()
        guard let pin = try secrets.data(pinAccount(profile)) else { throw CompanionError.notPaired }
        let local = try identities.loadOrCreate()
        let channel = try TLSChannel(host: profile.host, port: profile.remotePort, identity: local.identity, expectedServerKey: pin)
        let token = generation
        remote = channel; self.profile = profile; handshake = RemoteHandshake()
        do {
            try await channel.connect()
            for _ in 0..<16 {
                let payload = try await channel.readFrame(timeout: 10)
                guard generation == token else { throw CancellationError() }
                if let reply = try handshake.reply(to: payload) { try await channel.write(reply) }
                if handshake.ready {
                    changed(true)
                    receiveTask = Task { [weak self] in await self?.receive(channel, token: token, changed: changed) }
                    return
                }
            }
            throw CompanionError.unexpectedMessage
        } catch { channel.close(); if generation == token { remote = nil }; throw error }
    }
    private func receive(_ channel: TLSChannel, token: UUID, changed: @escaping @Sendable (Bool) -> Void) async {
        do {
            while !Task.isCancelled && generation == token {
                let payload = try await channel.readFrame()
                guard generation == token else { return }
                if let reply = try handshake.reply(to: payload) { try await channel.write(reply) }
            }
        } catch {
            if generation == token { channel.close(); remote = nil; changed(false) }
        }
    }
    func send(_ key: RemoteKey) async throws {
        guard let channel = remote else { throw CompanionError.disconnected }
        try await channel.write(handshake.key(key))
    }
    func disconnect() {
        generation = UUID(); receiveTask?.cancel(); receiveTask = nil
        pairing?.close(); remote?.close(); pairing = nil; remote = nil; identity = nil
        handshake = RemoteHandshake()
    }
}
