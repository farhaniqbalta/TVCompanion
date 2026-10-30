import SwiftUI
import TVCompanionKit

struct NotificationSenderView: View {
    @EnvironmentObject private var store: TVStore
    @ObservedObject var model: NotificationViewModel
    @State private var setup = false
    var body: some View {
        NavigationStack {
            Form {
                Section("Destination") {
                    Button(store.profile?.name ?? "Choose a TV") { setup = true }
                    Text("Start the notification receiver in the TVCompanion TV app.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Message") {
                    Picker("Type", selection: $model.kind) {
                        Text("Alert").tag(NotificationKind.alert)
                        Text("Alert2 · urgent").tag(NotificationKind.alert2)
                    }
                    TextEditor(text: $model.text).frame(minHeight: 140).accessibilityLabel("Message text")
                    Text("\(model.text.utf16.count) / 512").font(.caption).foregroundStyle(.secondary)
                    SecureField("TVCompanion notification PIN", text: $model.pin).keyboardType(.numberPad)
                    Button(model.busy ? "Sending…" : "Send to TV") { model.send() }
                        .disabled(model.busy || store.profile == nil || model.pin.isEmpty || model.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.text.utf16.count > 512)
                }.disabled(model.busy)
                Section {
                    Text(model.status)
                    Text("Alert2 uses a large TV overlay with Dismiss and an automatic timeout. TV privacy settings still apply. The existing notification API sends its PIN and message over unencrypted HTTP; use a trusted local network.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Send a message")
            .sheet(isPresented: $setup) { TVSetupView() }
            .onAppear { model.loadPIN() }
        }
    }
}
