import Foundation
import TVCompanionKit

@MainActor final class RemoteViewModel: ObservableObject {
    @Published var status = "Choose a TV, then connect or pair."
    @Published var connected = false
    @Published var busy = false
    @Published var needsCode = false
    private let client: GoogleTVRemote
    private var task: Task<Void, Never>?
    private var foreground = true
    private var reconnectAllowed = false
    private var retried = false
    private var activeTV: TVProfile?
    private var operation = UUID()
    init(credentials: CredentialStore) { client = GoogleTVRemote(secrets: credentials) }
    func pair(_ tv: TVProfile) {
        guard !busy else { return }; reconnectAllowed = false
        task?.cancel(); busy = true; connected = false; needsCode = false; status = "Waiting for the code on your TV…"
        activeTV = tv; operation = UUID(); let token = operation
        task = Task {
            do { try await client.beginPairing(tv); guard !Task.isCancelled && operation == token else { return }; needsCode = true; status = "Enter the code displayed on your TV." }
            catch { if !Task.isCancelled && operation == token { status = companionMessage(error) } }
            if operation == token { busy = false }
        }
    }
    func finishPairing(code: String) {
        guard !busy, let tv = activeTV else { return }; busy = true
        operation = UUID(); let token = operation
        task = Task {
            do {
                try await client.finishPairing(code: code)
                guard !Task.isCancelled && operation == token else { return }
                needsCode = false; busy = false; status = "Paired. Connecting…"; connect(tv)
            } catch { if !Task.isCancelled && operation == token { status = companionMessage(error); busy = false } }
        }
    }
    func connect(_ tv: TVProfile) {
        guard !busy else { return }; retried = false; reconnectAllowed = true; activeTV = tv
        connectAttempt(tv)
    }
    private func connectAttempt(_ tv: TVProfile) {
        task?.cancel(); busy = true; needsCode = false; connected = false; status = "Connecting to the TV remote service…"
        operation = UUID(); let token = operation
        let changed: @Sendable (Bool) -> Void = { [weak self] ready in
            Task { @MainActor [weak self] in
                guard let self, self.operation == token else { return }
                self.connectionChanged(ready)
            }
        }
        task = Task {
            do {
                try await client.connect(tv, changed: changed)
                guard !Task.isCancelled && operation == token else { return }
                busy = false
            } catch { if !Task.isCancelled && operation == token { status = companionMessage(error); connected = false; busy = false } }
        }
    }
    private func connectionChanged(_ ready: Bool) {
        guard foreground else { return }
        connected = ready; status = ready ? "Remote connected" : "Remote disconnected"
        if !ready && reconnectAllowed && !retried, let tv = activeTV {
            retried = true
            task = Task {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled && foreground else { return }
                connectAttempt(tv)
            }
        }
    }
    func send(_ key: RemoteKey) {
        guard connected && !busy else { return }
        // SHORT commands have no matching release to lose when the scene backgrounds.
        busy = true; let token = operation
        task = Task {
            do { try await client.send(key) }
            catch { if operation == token && !Task.isCancelled { status = companionMessage(error); connected = false } }
            if operation == token { busy = false }
        }
    }
    func suspend() {
        operation = UUID(); foreground = false; reconnectAllowed = false; task?.cancel(); task = nil
        connected = false; busy = false; needsCode = false; status = "Disconnected while the app is inactive. Tap Connect to resume."
        Task { await client.disconnect() }
    }
    func resume() { foreground = true }
}
