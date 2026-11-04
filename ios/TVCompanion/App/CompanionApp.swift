import SwiftUI

@main struct CompanionApp: App {
    @StateObject private var store: TVStore
    @StateObject private var remote: RemoteViewModel
    @StateObject private var notifications: NotificationViewModel
    @Environment(\.scenePhase) private var phase
    init() {
        let store = TVStore()
        _store = StateObject(wrappedValue: store)
        _remote = StateObject(wrappedValue: RemoteViewModel(credentials: store.credentials))
        _notifications = StateObject(wrappedValue: NotificationViewModel(store: store))
    }
    var body: some Scene {
        WindowGroup {
            TabView {
                RemoteView(model: remote).tabItem { Label("Remote", systemImage: "dpad") }
                NotificationSenderView(model: notifications).tabItem { Label("Send message", systemImage: "bell") }
            }
            .tint(Color(red: 0.25, green: 0.65, blue: 0.52))
            .environmentObject(store)
            .onChange(of: phase) { _, state in
                if state == .active { remote.resume() } else { remote.suspend(); notifications.suspend() }
            }
            .onChange(of: store.profile?.id) { _, _ in remote.suspend(); remote.resume(); notifications.suspend(); notifications.loadPIN() }
        }
    }
}
