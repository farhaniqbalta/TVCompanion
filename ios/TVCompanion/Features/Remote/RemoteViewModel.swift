import Foundation
import AVFoundation
import TVCompanionAudio
import TVCompanionKit

enum RemoteConnectionState: Equatable {
    case notConfigured, needsPairing, connecting, connected, reconnecting, offline, error
}

enum RemoteVoiceState: Equatable {
    case unavailable, idle, starting, listening, ending, permissionDenied, error
}

@MainActor final class RemoteViewModel: ObservableObject {
    @Published private(set) var state: RemoteConnectionState = .notConfigured
    @Published private(set) var status = "Choose a TV to get started."
    @Published private(set) var busy = false
    @Published private(set) var needsCode = false
    @Published private(set) var voiceState: RemoteVoiceState = .unavailable
    @Published private(set) var voiceStatus = "Voice is unavailable until the TV advertises support."
    @Published var text = ""
    var connected: Bool { state == .connected }

    private let client: GoogleTVRemote
    private var operationTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var sendTask: Task<Void, Never>?
    private var disconnectTask: Task<Void, Never>?
    private var voiceTask: Task<Void, Never>?
    private var microphone: VoiceCapture?
    private var pending = RemoteCommandBuffer()
    private var foreground = true
    private var retryCount = 0
    private var activeTV: TVProfile?
    private var operation = UUID()

    init(credentials: CredentialStore) { client = GoogleTVRemote(secrets: credentials) }

    func resume(_ tv: TVProfile?) {
        foreground = true
        guard let tv else {
            state = .notConfigured; status = "Choose a TV to get started."
            return
        }
        activeTV = tv
        guard !connected && !busy else { return }
        let token = operation
        operationTask = Task {
            let paired = await client.isPaired(tv)
            guard foreground && operation == token && activeTV?.id == tv.id else { return }
            if paired { retryCount = 0; connectAttempt(tv) }
            else { state = .needsPairing; status = "Pair with this TV to use the remote." }
        }
    }

    func pair(_ tv: TVProfile) {
        guard !busy else { return }
        cancelOperations()
        activeTV = tv; busy = true; state = .connecting; needsCode = false
        status = "Waiting for the code on your TV…"
        let token = operation
        operationTask = Task {
            await disconnectTask?.value
            do {
                try await client.beginPairing(tv)
                guard !Task.isCancelled && operation == token else { return }
                needsCode = true; status = "Enter the code displayed on your TV."
            } catch {
                guard !Task.isCancelled && operation == token else { return }
                state = .error; status = companionMessage(error)
            }
            if operation == token { busy = false }
        }
    }

    func finishPairing(code: String) {
        guard !busy, needsCode, let tv = activeTV else { return }
        busy = true; let token = operation
        operationTask = Task {
            do {
                try await client.finishPairing(code: code)
                guard !Task.isCancelled && operation == token else { return }
                busy = false; needsCode = false; retryCount = 0
                connectAttempt(tv)
            } catch {
                guard !Task.isCancelled && operation == token else { return }
                busy = false; state = .needsPairing; status = companionMessage(error)
            }
        }
    }

    func connect(_ tv: TVProfile) {
        guard !busy else { return }
        activeTV = tv; retryCount = 0
        connectAttempt(tv)
    }

    private func connectAttempt(_ tv: TVProfile) {
        stopVoice()
        cancelOperations()
        busy = true; needsCode = false; state = retryCount == 0 ? .connecting : .reconnecting
        status = retryCount == 0 ? "Connecting…" : "Reconnecting…"
        let token = operation
        let changed: @Sendable (Bool) -> Void = { [weak self] ready in
            Task { @MainActor [weak self] in
                guard let self, self.operation == token else { return }
                self.connectionChanged(ready)
            }
        }
        operationTask = Task {
            await disconnectTask?.value
            do {
                try await client.connect(tv, changed: changed)
                guard !Task.isCancelled && operation == token else { return }
                state = .connected; status = "Connected"; busy = false; retryCount = 0
                let available = await client.isVoiceAvailable()
                if operation == token {
                    voiceState = available ? .idle : .unavailable
                    voiceStatus = available ? "Voice ready" : "Voice is unsupported by this TV."
                }
            } catch {
                guard !Task.isCancelled && operation == token else { return }
                busy = false
                if error as? CompanionError == .notPaired || error as? CompanionError == .remoteRejected {
                    state = .needsPairing; status = companionMessage(error)
                } else if error as? CompanionError == .certificateChanged {
                    state = .error; status = companionMessage(error)
                } else {
                    state = .offline; status = companionMessage(error)
                    scheduleRetry()
                }
            }
        }
    }

    private func connectionChanged(_ ready: Bool) {
        guard foreground else { return }
        if ready { state = .connected; status = "Connected"; retryCount = 0 }
        else {
            stopVoice()
            clearCommands()
            state = .offline; status = "TV connection closed."
            scheduleRetry()
        }
    }

    private func scheduleRetry() {
        guard foreground, retryCount < 4, let tv = activeTV else { return }
        retryTask?.cancel()
        let delay = [1, 2, 4, 8][retryCount]
        retryCount += 1
        let token = operation
        retryTask = Task {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled && foreground && operation == token else { return }
            connectAttempt(tv)
        }
    }

    func send(_ key: RemoteKey) { enqueue(.key(key)) }

    func sendText() {
        guard !text.isEmpty && text.utf16.count <= 512 else {
            status = "Enter a shorter message for the TV text field."
            return
        }
        enqueue(.text(text))
    }

    private func enqueue(_ command: RemoteCommand) {
        guard connected else { status = "Connect to the TV before sending commands."; return }
        guard pending.push(command) else { status = "Remote is busy. Try again in a moment."; return }
        guard sendTask == nil else { return }
        let token = operation
        sendTask = Task {
            while operation == token && connected, let next = pending.pop() {
                do {
                    switch next {
                    case .key(let key): try await client.send(key)
                    case .text(let value):
                        try await client.sendText(value)
                        if text == value { text = "" }
                        status = "Text sent. Check the focused TV field."
                    }
                } catch {
                    guard operation == token else { break }
                    if error as? CompanionError == .unsupported {
                        status = companionMessage(error)
                        continue
                    }
                    pending.clear(); state = .offline; status = companionMessage(error)
                    scheduleRetry(); break
                }
            }
            if operation == token { sendTask = nil }
        }
    }

    private func clearCommands() {
        pending.clear(); sendTask?.cancel(); sendTask = nil
    }

    private func cancelOperations() {
        operation = UUID()
        operationTask?.cancel(); operationTask = nil
        retryTask?.cancel(); retryTask = nil
        clearCommands()
    }

    func suspend() {
        foreground = false; stopVoice(); cancelOperations()
        busy = false; needsCode = false
        voiceState = .unavailable
        state = activeTV == nil ? .notConfigured : .offline
        status = "Remote disconnected while the app is inactive."
        disconnectTask = Task { await client.disconnect() }
    }

    func startVoice() {
        guard connected, voiceState == .idle || voiceState == .error, voiceTask == nil else { return }
        voiceState = .starting; voiceStatus = "Preparing voice…"
        let token = operation
        voiceTask = Task {
            var sessionID: UInt64?
            do {
                guard await VoiceCapture.requestPermission() else {
                    if operation == token {
                        voiceState = .permissionDenied
                        voiceStatus = "Microphone access is needed. Enable it in Settings."
                    }
                    voiceTask = nil
                    return
                }
                try Task.checkCancellation()
                let id = try await client.startVoice()
                sessionID = id
                guard operation == token && connected else { throw CancellationError() }
                let capture = VoiceCapture()
                microphone = capture
                let stream = try capture.start()
                let encoder = try VoicePCMEncoder(sampleRate: capture.sampleRate)
                voiceState = .listening; voiceStatus = "Listening… Tap Stop when finished."
                for await samples in stream {
                    try Task.checkCancellation()
                    guard !capture.overflowed else { throw CompanionError.oversizedMessage }
                    guard operation == token && connected else { throw CompanionError.disconnected }
                    for chunk in try encoder.append(samples.values) {
                        try await client.sendVoice(chunk, sessionID: id)
                    }
                }
                try Task.checkCancellation()
                if let last = encoder.finish() { try await client.sendVoice(last, sessionID: id) }
                await client.endVoice(sessionID: id)
                sessionID = nil
                if operation == token { voiceState = .error; voiceStatus = "Voice stopped because microphone input was interrupted." }
            } catch {
                if let sessionID { await client.endVoice(sessionID: sessionID) }
                if operation == token && !Task.isCancelled {
                    voiceState = .error
                    voiceStatus = error as? CompanionError == .timedOut
                        ? "The TV did not start voice. Try the keyboard or check TV voice support."
                        : companionMessage(error)
                }
            }
            microphone?.stop(); microphone = nil
            if operation == token {
                if Task.isCancelled { voiceState = connected ? .idle : .unavailable; voiceStatus = "Voice stopped." }
                voiceTask = nil
            }
        }
    }

    func stopVoice() {
        guard voiceTask != nil else { return }
        microphone?.stop()
        voiceState = .ending; voiceStatus = "Finishing voice…"
        voiceTask?.cancel()
    }
}

private struct MicrophoneSamples: Sendable {
    let values: [Float]
}

private final class VoiceOverflowFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    func mark() { lock.lock(); value = true; lock.unlock() }
    var marked: Bool { lock.lock(); defer { lock.unlock() }; return value }
}

@MainActor private final class VoiceCapture {
    private let engine = AVAudioEngine()
    private let overflow = VoiceOverflowFlag()
    private var continuation: AsyncStream<MicrophoneSamples>.Continuation?
    private var tapInstalled = false
    private var observers: [NSObjectProtocol] = []
    private(set) var sampleRate: Double = 48_000
    var overflowed: Bool { overflow.marked }

    static func requestPermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .denied: return false
        case .undetermined: return await AVAudioApplication.requestRecordPermission()
        @unknown default: return false
        }
    }

    func start() throws -> AsyncStream<MicrophoneSamples> {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement)
        try session.setActive(true)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.channelCount > 0, format.sampleRate > 0 else { throw CompanionError.unsupported }
        sampleRate = format.sampleRate
        let (stream, destination) = AsyncStream<MicrophoneSamples>.makeStream(bufferingPolicy: .bufferingNewest(8))
        continuation = destination
        let overflow = self.overflow
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            guard let channels = buffer.floatChannelData else { destination.finish(); return }
            let count = Int(buffer.frameLength)
            let channelCount = Int(buffer.format.channelCount)
            var mono = [Float](repeating: 0, count: count)
            for frame in 0..<count {
                var value: Float = 0
                for channel in 0..<channelCount { value += channels[channel][frame] }
                mono[frame] = value / Float(channelCount)
            }
            if case .dropped = destination.yield(MicrophoneSamples(values: mono)) {
                overflow.mark()
                destination.finish()
            }
        }
        tapInstalled = true
        for name in [AVAudioSession.interruptionNotification, AVAudioSession.routeChangeNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: session, queue: nil) { [weak self] _ in
                Task { @MainActor [weak self] in self?.interrupt() }
            })
        }
        engine.prepare()
        do { try engine.start() }
        catch { stop(); throw error }
        return stream
    }

    func stop() {
        if tapInstalled { engine.inputNode.removeTap(onBus: 0); tapInstalled = false }
        engine.stop()
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        continuation?.finish(); continuation = nil
        try? AVAudioSession.sharedInstance().setActive(false)
    }
    private func interrupt() {
        overflow.mark()
        stop()
    }
}
