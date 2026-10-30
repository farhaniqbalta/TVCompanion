import Foundation
import Network
import Security
import CryptoKit
import TVCompanionKit

private final class ChannelTimer: @unchecked Sendable {
    let work: DispatchWorkItem
    init(_ action: @escaping @Sendable () -> Void) { work = DispatchWorkItem(block: action) }
    func cancel() { work.cancel() }
}
private final class OneShot<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?
    init(_ continuation: CheckedContinuation<T, Error>) { self.continuation = continuation }
    func finish(_ result: Result<T, Error>) {
        lock.lock(); let callback = continuation; continuation = nil; lock.unlock()
        callback?.resume(with: result)
    }
}
private final class PeerEvidence: @unchecked Sendable {
    private let lock = NSLock()
    private var key: Data?
    private var rejected = false
    func set(_ data: Data?, rejected: Bool) { lock.lock(); key = data; self.rejected = rejected; lock.unlock() }
    func read() -> (Data?, Bool) { lock.lock(); defer { lock.unlock() }; return (key, rejected) }
}

/// One task reads framed messages; writes are independent. Cancellation closes the owned connection.
final class TLSChannel: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "TVCompanion.RemoteTLS")
    private let evidence = PeerEvidence()
    private var decoder = FrameDecoder()
    init(host: String, port: UInt16, identity: SecIdentity, expectedServerKey: Data?) throws {
        let tls = NWProtocolTLS.Options()
        guard let local = sec_identity_create(identity) else { throw CompanionError.notPaired }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, local)
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        let evidence = self.evidence
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let trust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let certificates = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let certificate = certificates.first, let key = SecCertificateCopyKey(certificate),
                  let raw = SecKeyCopyExternalRepresentation(key, nil) as Data?, (try? DER.rsaNumbers(raw)) != nil else {
                evidence.set(nil, rejected: true); complete(false); return
            }
            let pin = Data(SHA256.hash(data: raw))
            let matches = expectedServerKey.map { expected in
                expected.count == pin.count && zip(expected, pin).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
            } ?? true // Only an explicitly initiated pairing connection is allowed to learn a key.
            evidence.set(raw, rejected: !matches); complete(matches)
        }, queue)
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
    }
    var serverRSA: Data? { evidence.read().0 }
    func connect() async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let once = OneShot(continuation)
                let timer = ChannelTimer { once.finish(.failure(CompanionError.timedOut)); self.connection.cancel() }
                queue.asyncAfter(deadline: .now() + 8, execute: timer.work)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready: timer.cancel(); self.connection.stateUpdateHandler = nil; once.finish(.success(()))
                    case .failed, .cancelled:
                        timer.cancel(); self.connection.stateUpdateHandler = nil; once.finish(.failure(self.evidence.read().1 ? CompanionError.certificateChanged : CompanionError.disconnected))
                    default: break
                    }
                }
                connection.start(queue: queue)
            }
        } onCancel: { self.close() }
    }
    func write(_ payload: Data) async throws {
        let frame = try FrameDecoder.encode(payload)
        try Task.checkCancellation()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let once = OneShot(continuation)
                let timer = ChannelTimer { once.finish(.failure(CompanionError.timedOut)); self.connection.cancel() }
                queue.asyncAfter(deadline: .now() + 5, execute: timer.work)
                connection.send(content: frame, completion: .contentProcessed { error in
                    timer.cancel(); once.finish(error == nil ? .success(()) : .failure(CompanionError.disconnected))
                })
            }
        } onCancel: { self.close() }
    }
    func readFrame(timeout: TimeInterval = 45) async throws -> Data {
        let deadline = DispatchTime.now().uptimeNanoseconds + UInt64(timeout * 1_000_000_000)
        while true {
            try Task.checkCancellation()
            if let frame = try decoder.next() { return frame }
            let now = DispatchTime.now().uptimeNanoseconds
            guard deadline > now else { close(); throw CompanionError.timedOut }
            let received: (Data, Bool) = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    let once = OneShot(continuation)
                    let timer = ChannelTimer { once.finish(.failure(CompanionError.timedOut)); self.connection.cancel() }
                    queue.asyncAfter(deadline: DispatchTime(uptimeNanoseconds: deadline), execute: timer.work)
                    connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, complete, error in
                        timer.cancel()
                        if error != nil { once.finish(.failure(CompanionError.disconnected)) }
                        else { once.finish(.success((data ?? Data(), complete))) }
                    }
                }
            } onCancel: { self.close() }
            if received.0.isEmpty && received.1 { throw CompanionError.disconnected }
            try decoder.append(received.0)
        }
    }
    func close() { connection.cancel() }
}
