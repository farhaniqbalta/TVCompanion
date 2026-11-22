import Foundation
import TVCompanionKit

/// Shows only what the TV reports. Refreshes on appear, pull-to-refresh, after each command and once after `pending`; never polls.
@MainActor final class ControlsViewModel: ObservableObject {
    enum Feature: Hashable { case ambient, ambilight, light, notifications, airplay }
    @Published private(set) var status: ControlStatus?
    @Published private(set) var waiting: Set<Feature> = []
    @Published private(set) var banner: String?
    @Published private(set) var paired = false
    @Published private(set) var pairing = false
    @Published var confirmAirPlayOff = false
    private let store: TVStore
    private let service: ControlService
    private var generation = UUID()   // Responses from a suspended or switched-away session are dropped.
    private var shownTV: UUID?

    init(store: TVStore, service: ControlService) { self.store = store; self.service = service }

    func appear() {
        guard let tv = store.profile else { paired = false; status = nil; return }
        if shownTV != tv.id { shownTV = tv.id; status = nil; banner = nil }
        paired = service.isPaired(tv)
        if paired { Task { await refresh() } }
    }

    func refresh() async {
        guard let tv = store.profile, paired else { return }
        let token = generation
        do {
            let response = try await service.send(.status, to: tv)
            if generation == token { apply(response) }
        } catch { if generation == token { fail(error, tv) } }
    }

    func set(_ feature: Feature, _ request: ControlRequest) {
        guard let tv = store.profile, paired, status != nil, !waiting.contains(feature) else { return }
        waiting.insert(feature)
        let token = generation
        Task {
            do {
                let response = try await service.send(request, to: tv)
                guard generation == token else { return }
                waiting.remove(feature)
                apply(response)
                if response.reason == "would_end_playback" { confirmAirPlayOff = true }
                if response.outcome == .pending {
                    try? await Task.sleep(for: .seconds(2))
                    if generation == token { await refresh() }
                }
            } catch {
                guard generation == token else { return }
                waiting.remove(feature); fail(error, tv)
            }
        }
    }

    func confirmAirPlayOffNow() { set(.airplay, .airplay(available: false, confirm: true)) }

    func pair(code: String) async -> Bool {
        guard let tv = store.profile, !pairing else { return false }
        pairing = true; defer { pairing = false }
        do {
            try await service.pair(tv, code: code)
            paired = true; banner = nil
            await refresh()
            return true
        } catch CompanionError.invalidCode {
            banner = "That code didn't match. Check the 6-digit code on the TV."
        } catch { banner = message(for: error) }
        return false
    }

    func forget() {
        guard let tv = store.profile else { return }
        service.forget(tv); paired = false; status = nil; banner = nil
    }

    func suspend() { generation = UUID(); waiting = []; confirmAirPlayOff = false }

    private func apply(_ response: ControlResponse) {
        if let next = response.status { status = next }
        banner = switch response.outcome {
        case .ok, .pending: nil
        case .busy: "The TV is busy with another change. Try again in a moment."
        case .unavailable: "That feature isn't available on the TV right now."
        case .unconfirmed: "The TV couldn't confirm the change. It may have applied, so check before trying again."
        case .needsSetup: "Set this feature up on the TV first."
        case .needsApproval: "Approve screen capture on the TV."
        case .invalid: response.reason == "would_end_playback" ? nil : "The TV couldn't do that right now."
        case .denied: "This iPhone isn't paired with the TV."
        case .unknown: "The TV sent a reply this app doesn't understand. Update the iPhone app."
        }
    }

    private func fail(_ error: Error, _ tv: TVProfile) {
        if case CompanionError.remoteRejected = error {
            // The TV no longer knows this iPhone (forgotten on the TV).
            service.forget(tv); paired = false; status = nil
            banner = "This iPhone is no longer paired. Pair again."
            return
        }
        status = nil   // Unreachable or untrusted: show nothing rather than stale state.
        banner = message(for: error)
    }

    private func message(for error: Error) -> String {
        switch error {
        case CompanionError.timedOut, CompanionError.disconnected:
            return "Can't reach the TV controls. Check the TV is on and its TVCompanion app is up to date."
        default: return companionMessage(error)
        }
    }
}
