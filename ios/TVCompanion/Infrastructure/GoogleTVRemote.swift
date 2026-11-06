import Foundation
import CryptoKit
import os
import TVCompanionKit

private actor RemoteWriter {
    private var tail: Task<Void, Error>?
    func write(_ payload: Data, to channel: TLSChannel) async throws {
        let previous = tail
        let next = Task {
            _ = try? await previous?.value
            try await channel.write(payload)
        }
        tail = next
        try await next.value
    }
}

/// Separate endpoint from notification HTTP. A paired TV's public-key pin is required for control.
actor GoogleTVRemote {
    #if DEBUG
    private let logger = Logger(subsystem: "id.poncol.tvcompanion.client", category: "RemoteV2")
    #endif
    private let secrets: CredentialStore
    private let identities = RemoteIdentityStore()
    private var pairing: TLSChannel?
    private var remote: TLSChannel?
    private var identity: RemoteIdentity?
    private var profile: TVProfile?
    private var handshake = RemoteHandshake()
    private var receiveTask: Task<Void, Never>?
    private var generation = UUID()
    private var writer = RemoteWriter()
    private var voiceRequested = false
    private var pendingVoiceID: UInt64?
    private var voiceSessionID: UInt64?
    init(secrets: CredentialStore) { self.secrets = secrets }
    private func trace(_ stage: String, tv: TVProfile? = nil, local: RemoteIdentity? = nil, failure: Error? = nil) {
        #if DEBUG
        let defaults = UserDefaults.standard
        let installation = defaults.string(forKey: "remote-diagnostic-installation-id") ?? UUID().uuidString
        if defaults.string(forKey: "remote-diagnostic-installation-id") == nil {
            defaults.set(installation, forKey: "remote-diagnostic-installation-id")
        }
        let profileID = tv?.id.uuidString ?? "none"
        let host = tv?.host ?? "none"
        let fingerprint = local?.fingerprint ?? "none"
        let reused = local?.reused == true ? "reused" : "generated-or-unavailable"
        let category = failure.map {
            ($0 as? CompanionError).map { String(describing: $0) } ?? String(describing: type(of: $0))
        } ?? "none"
        logger.debug("install=\(installation, privacy: .public) stage=\(stage, privacy: .public) profile=\(profileID, privacy: .public) host=\(host, privacy: .private) identity=\(fingerprint, privacy: .public) identityState=\(reused, privacy: .public) failure=\(category, privacy: .public)")
        #endif
    }
    private func pinAccount(_ profile: TVProfile) -> String { "remote-pin-" + profile.id.uuidString }
    func isPaired(_ profile: TVProfile) -> Bool { (try? secrets.data(pinAccount(profile))) != nil }
    func beginPairing(_ profile: TVProfile) async throws {
        disconnect()
        let token = generation
        let local = try identities.loadOrCreate()
        trace("pairing-connect", tv: profile, local: local)
        let channel = try TLSChannel(host: profile.host, port: profile.pairingPort, identity: local.identity, expectedServerKey: nil)
        pairing = channel; identity = local; self.profile = profile
        do {
            try await channel.connect(); trace("pairing-tls", tv: profile, local: local)
            try await channel.write(PairingWire.request())
            for _ in 0..<8 {
                let step = try PairingWire.response(to: await channel.readFrame(timeout: 10))
                guard generation == token else { throw CancellationError() }
                if step.needsCode { trace("pairing-code", tv: profile, local: local); return }
                if let reply = step.reply { try await channel.write(reply) }
            }
            throw CompanionError.unexpectedMessage
        } catch { trace("pairing-failed", tv: profile, local: local, failure: error); channel.close(); if pairing === channel { pairing = nil }; throw error }
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
            trace("pairing-saved", tv: tv, local: local)
            channel.close(); pairing = nil
        } catch { channel.close(); if pairing === channel { pairing = nil }; throw error }
    }
    func connect(_ profile: TVProfile, changed: @escaping @Sendable (Bool) -> Void) async throws {
        disconnect()
        guard let pin = try secrets.data(pinAccount(profile)) else { throw CompanionError.notPaired }
        let local = try identities.loadOrCreate()
        trace("pin-present-connect", tv: profile, local: local)
        let channel = try TLSChannel(host: profile.host, port: profile.remotePort, identity: local.identity, expectedServerKey: pin)
        let token = generation
        remote = channel; self.profile = profile; identity = local; handshake = RemoteHandshake()
        do {
            try await channel.connect(); trace("tls-authenticated", tv: profile, local: local)
            for _ in 0..<16 {
                let payload = try await channel.readFrame(timeout: 10)
                guard generation == token else { throw CancellationError() }
                if let reply = try handshake.reply(to: payload) { try await writer.write(reply, to: channel) }
                if handshake.ready {
                    trace("remote-ready", tv: profile, local: local)
                    changed(true)
                    receiveTask = Task { [weak self] in await self?.receive(channel, token: token, changed: changed) }
                    return
                }
            }
            throw CompanionError.unexpectedMessage
        } catch { trace("remote-failed", tv: profile, local: local, failure: error); channel.close(); if generation == token { remote = nil }; throw error }
    }
    private func receive(_ channel: TLSChannel, token: UUID, changed: @escaping @Sendable (Bool) -> Void) async {
        do {
            while !Task.isCancelled && generation == token {
                let payload = try await channel.readFrame()
                guard generation == token else { return }
                if voiceRequested, let id = try RemoteVoiceWire.beginID(payload) { pendingVoiceID = id }
                if let reply = try handshake.reply(to: payload) { try await writer.write(reply, to: channel) }
            }
        } catch {
            if generation == token {
                trace("remote-disconnected", tv: profile, local: identity, failure: error)
                channel.close(); remote = nil; changed(false)
            }
        }
    }
    func send(_ key: RemoteKey) async throws {
        guard let channel = remote else { throw CompanionError.disconnected }
        try await writer.write(handshake.key(key), to: channel)
    }
    func sendText(_ value: String) async throws {
        guard let channel = remote else { throw CompanionError.disconnected }
        try await writer.write(handshake.text(value), to: channel)
    }
    func isVoiceAvailable() -> Bool { remote != nil && handshake.voiceEnabled }
    func startVoice() async throws -> UInt64 {
        guard let channel = remote else { throw CompanionError.disconnected }
        guard handshake.voiceEnabled else { throw CompanionError.unsupported }
        guard !voiceRequested && voiceSessionID == nil else { throw CompanionError.unsupported }
        let token = generation
        voiceRequested = true; pendingVoiceID = nil
        do {
            try await writer.write(handshake.key(.search), to: channel)
            for _ in 0..<80 {
                try Task.checkCancellation()
                guard generation == token && remote === channel else { throw CompanionError.disconnected }
                if let id = pendingVoiceID {
                    try await writer.write(RemoteVoiceWire.begin(id), to: channel)
                    guard generation == token else { throw CompanionError.disconnected }
                    voiceSessionID = id; voiceRequested = false; pendingVoiceID = nil
                    return id
                }
                try await Task.sleep(for: .milliseconds(25))
            }
            throw CompanionError.timedOut
        } catch {
            if generation == token { voiceRequested = false; pendingVoiceID = nil }
            throw error
        }
    }
    func sendVoice(_ samples: Data, sessionID: UInt64) async throws {
        guard let channel = remote, voiceSessionID == sessionID else { throw CompanionError.disconnected }
        for chunk in RemoteVoiceWire.chunks(samples) {
            try await writer.write(RemoteVoiceWire.payload(chunk, id: sessionID), to: channel)
        }
    }
    func endVoice(sessionID: UInt64) async {
        guard voiceSessionID == sessionID else { return }
        voiceSessionID = nil
        guard let channel = remote else { return }
        try? await writer.write(RemoteVoiceWire.end(sessionID), to: channel)
    }
    func disconnect() {
        generation = UUID(); receiveTask?.cancel(); receiveTask = nil
        pairing?.close(); remote?.close(); pairing = nil; remote = nil; identity = nil
        writer = RemoteWriter(); voiceRequested = false; pendingVoiceID = nil; voiceSessionID = nil
        handshake = RemoteHandshake()
    }
}
