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
                if !store.knownProfiles.isEmpty {
                    Section("Saved TVs") {
                        ForEach(store.knownProfiles) { tv in
                            Button {
                                store.selectSaved(tv)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(tv.name)
                                    Text(tv.host).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
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
                            store.select(try TVProfile(name: name, host: host, notificationPort: number)); dismiss()
                        } catch { self.error = companionMessage(error) }
                    }.disabled(host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if store.profile != nil {
                        Button("Update saved TV address") {
                            do {
                                guard let number = UInt16(port), number > 0 else { throw CompanionError.invalidAddress }
                                store.select(try TVProfile(name: name, host: host, notificationPort: number), updatingCurrentAddress: true)
                                dismiss()
                            } catch { self.error = companionMessage(error) }
                        }.disabled(host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Text("Use this only for the same physical TV. Its saved remote and message credentials stay associated with this profile.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
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
