import SwiftUI

@main struct CompanionApp: App {
    @StateObject private var store: TVStore
    @StateObject private var remote: RemoteViewModel
    @StateObject private var notifications: NotificationViewModel
    @StateObject private var controls: ControlsViewModel
    @Environment(\.scenePhase) private var phase
    init() {
        let store = TVStore()
        _store = StateObject(wrappedValue: store)
        _remote = StateObject(wrappedValue: RemoteViewModel(credentials: store.credentials))
        _notifications = StateObject(wrappedValue: NotificationViewModel(store: store))
        _controls = StateObject(wrappedValue: ControlsViewModel(store: store, service: ControlClient(credentials: store.credentials)))
    }
    var body: some Scene {
        WindowGroup {
            TabView {
                RemoteView(model: remote).tabItem { Label("Remote", systemImage: "dpad") }
                NotificationSenderView(model: notifications).tabItem { Label("Send message", systemImage: "bell") }
                ControlsView(model: controls).tabItem { Label("Controls", systemImage: "switch.2") }
            }
            .tint(Color(red: 0.25, green: 0.65, blue: 0.52))
            .environmentObject(store)
            .onChange(of: phase) { _, state in
                if state == .active { remote.resume(store.profile) } else { remote.suspend(); notifications.suspend(); controls.suspend() }
            }
            .onChange(of: store.profile) { _, _ in remote.suspend(); remote.resume(store.profile); notifications.suspend(); notifications.loadPIN(); controls.suspend(); controls.appear() }
            .task { remote.resume(store.profile) }
        }
    }
}
