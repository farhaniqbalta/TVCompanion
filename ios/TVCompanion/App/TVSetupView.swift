import SwiftUI
import TVCompanionKit

struct TVSetupView: View {
    @EnvironmentObject private var store: TVStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var discovery = TVDiscovery()
    @State private var host = ""
    @State private var name = "Google TV"
    @State private var port = "8766"
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section("Nearby TVs") {
                    Button(discovery.searching ? "Searching…" : "Find TVs") { discovery.search() }.disabled(discovery.searching)
                    ForEach(discovery.devices) { tv in
                        Button { store.select(tv); dismiss() } label: {
                            VStack(alignment: .leading) { Text(tv.name); Text(tv.host).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                    Text("Allow local network access. If your TV is not listed, enter its address below.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Manual connection") {
                    TextField("TV name", text: $name)
                    TextField("TV address", text: $host).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    TextField("Notification port", text: $port).keyboardType(.numberPad)
                    Button("Use this TV") {
                        do {
                            guard let number = UInt16(port), number > 0 else { throw CompanionError.invalidAddress }
                            let id = store.profile?.host == host.trimmingCharacters(in: .whitespacesAndNewlines) ? store.profile!.id : UUID()
                            store.select(try TVProfile(id: id, name: name, host: host, notificationPort: number)); dismiss()
                        } catch { self.error = companionMessage(error) }
                    }.disabled(host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
                Section { Text("Remote pairing uses Google TV’s remote service. Message delivery uses the TVCompanion notification receiver; they have separate credentials.").font(.footnote) }
            }
            .navigationTitle("Choose your TV")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .onAppear {
                if let tv = store.profile { host = tv.host; name = tv.name; port = String(tv.notificationPort) }
            }
            .onDisappear { discovery.stop() }
            .onChange(of: scenePhase) { _, phase in if phase != .active { discovery.stop() } }
        }
    }
}
