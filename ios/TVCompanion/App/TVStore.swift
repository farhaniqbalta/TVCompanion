import Foundation
import TVCompanionKit

@MainActor final class TVStore: ObservableObject {
    @Published private(set) var profile: TVProfile?
    @Published private(set) var knownProfiles: [TVProfile] = []
    let credentials = CredentialStore()
    init() {
        if let bytes = UserDefaults.standard.data(forKey: "tv-profile"), let saved = try? JSONDecoder().decode(TVProfile.self, from: bytes),
           (try? TVProfile(id: saved.id, name: saved.name, host: saved.host, remotePort: saved.remotePort, pairingPort: saved.pairingPort, notificationPort: saved.notificationPort)) != nil { profile = saved }
        if let bytes = UserDefaults.standard.data(forKey: "tv-profiles"),
           let saved = try? JSONDecoder().decode([TVProfile].self, from: bytes) {
            knownProfiles = saved.filter {
                (try? TVProfile(id: $0.id, name: $0.name, host: $0.host, remotePort: $0.remotePort,
                                pairingPort: $0.pairingPort, notificationPort: $0.notificationPort)) != nil
            }
        }
        if let profile, !knownProfiles.contains(where: { $0.id == profile.id }) { knownProfiles.append(profile) }
    }
    func select(_ selected: TVProfile, updatingCurrentAddress: Bool = false) {
        let resolved = selected.preservingIdentity(from: profile, addressUpdate: updatingCurrentAddress)
        activate(resolved)
    }
    func selectSaved(_ saved: TVProfile) { activate(saved) }
    private func activate(_ resolved: TVProfile) {
        profile = resolved
        if let index = knownProfiles.firstIndex(where: { $0.id == resolved.id }) { knownProfiles[index] = resolved }
        else { knownProfiles.append(resolved) }
        UserDefaults.standard.set(try? JSONEncoder().encode(resolved), forKey: "tv-profile")
        UserDefaults.standard.set(try? JSONEncoder().encode(knownProfiles), forKey: "tv-profiles")
    }
}

func companionMessage(_ error: Error) -> String {
    switch error {
    case CompanionError.invalidAddress: return "Enter a TV address without a URL or path."
    case CompanionError.invalidCode: return "Check the six-character code on your TV and try again."
    case CompanionError.certificateChanged: return "The TV identity changed. Pair again before sending commands."
    case CompanionError.notPaired: return "Pair with your TV first. Your certificate is stored securely on this device."
    case CompanionError.timedOut: return "The TV did not reply in time. Check the network and reconnect."
    case CompanionError.disconnected: return "Connection closed. Check the TV and reconnect."
    case CompanionError.remoteRejected: return "The TV rejected this session. Try pairing again."
    case CompanionError.unauthorized: return "The notification PIN was rejected. Check it in the TV app."
    case CompanionError.unsupported: return "This feature is unavailable. For alert2, update the TVCompanion TV app."
    case CompanionError.rateLimited: return "The TV queue is busy. Wait before trying again."
    case CompanionError.receiverUnavailable: return "Start the notification receiver in TVCompanion and check overlay permission."
    case CompanionError.invalidNotification: return "Enter a short message (up to 512 UTF-16 units) and a valid notification PIN."
    case CompanionError.invalidText: return "Enter a shorter message for the TV text field."
    case CompanionError.unconfirmed: return "Delivery is unconfirmed and may have succeeded. An explicit retry reuses this message ID."
    default: return "The operation could not finish. Check the TV and connection."
    }
}
