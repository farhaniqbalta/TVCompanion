import Foundation
import TVCompanionKit

@MainActor final class TVStore: ObservableObject {
    @Published private(set) var profile: TVProfile?
    let credentials = CredentialStore()
    init() {
        if let bytes = UserDefaults.standard.data(forKey: "tv-profile"), let saved = try? JSONDecoder().decode(TVProfile.self, from: bytes),
           (try? TVProfile(id: saved.id, name: saved.name, host: saved.host, remotePort: saved.remotePort, pairingPort: saved.pairingPort, notificationPort: saved.notificationPort)) != nil { profile = saved }
    }
    func select(_ selected: TVProfile) {
        // Preserve pairing identity when reselecting the same host from discovery.
        var selected = selected
        if selected.host == profile?.host { selected.id = profile!.id }
        profile = selected
        UserDefaults.standard.set(try? JSONEncoder().encode(selected), forKey: "tv-profile")
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
    case CompanionError.unconfirmed: return "Delivery is unconfirmed and may have succeeded. An explicit retry reuses this message ID."
    default: return "The operation could not finish. Check the TV and connection."
    }
}
