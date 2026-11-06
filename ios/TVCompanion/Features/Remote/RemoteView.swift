import SwiftUI
import UIKit
import TVCompanionKit

struct RemoteView: View {
    @EnvironmentObject private var store: TVStore
    @ObservedObject var model: RemoteViewModel
    @State private var setup = false
    @State private var pairing = false
    @State private var keyboard = false
    @State private var code = ""
    @FocusState private var keyboardFocused: Bool

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 20) {
                        header
                        if geometry.size.width >= 700 {
                            HStack(alignment: .top, spacing: 36) {
                                VStack(spacing: 18) {
                                    DPadView(model: model)
                                    HStack(spacing: 16) {
                                        key(.back, "Back", "arrow.uturn.backward")
                                        key(.home, "Home", "house.fill")
                                    }
                                }
                                .frame(maxWidth: .infinity)
                                controls(wide: true)
                                    .frame(maxWidth: .infinity, alignment: .top)
                            }
                            .padding(.horizontal, 24)
                        } else {
                            DPadView(model: model)
                            controls(wide: false)
                                .frame(maxWidth: 360)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 16)
                    .frame(maxWidth: 980)
                    .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("Remote")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                Button { setup = true } label: { Label("Choose TV", systemImage: "tv") }
                    .accessibilityHint("Choose or update the TV used by the remote and message sender")
            }
            .sheet(isPresented: $setup) { TVSetupView() }
            .sheet(isPresented: $pairing) { pairingSheet }
            .sheet(isPresented: $keyboard) { keyboardSheet }
            .onChange(of: model.state) { _, state in
                if state == .connected { pairing = false }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "tv").font(.title3)
                Text(store.profile?.name ?? "Choose a TV").font(.headline).lineLimit(1)
                Spacer()
                Text(stateLabel)
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(.quaternary, in: Capsule())
                    .accessibilityLabel("Remote status: \(stateLabel)")
            }
            if model.state != .connected {
                Text(model.status).font(.footnote).foregroundStyle(.secondary)
                HStack {
                    if let tv = store.profile {
                        switch model.state {
                        case .needsPairing:
                            Button("Pair with TV") { code = ""; model.pair(tv); pairing = true }
                                .buttonStyle(.borderedProminent)
                        case .connecting, .reconnecting:
                            ProgressView().accessibilityLabel(stateLabel)
                        default:
                            Button("Reconnect") { model.connect(tv) }
                                .buttonStyle(.borderedProminent)
                            Button("Pair again") { code = ""; model.pair(tv); pairing = true }
                                .buttonStyle(.bordered)
                        }
                    } else {
                        Button("Choose a TV") { setup = true }.buttonStyle(.borderedProminent)
                    }
                }
            }
        }
        .padding(16)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 18))
    }

    private var stateLabel: String {
        switch model.state {
        case .notConfigured: "No TV"
        case .needsPairing: "Needs pairing"
        case .connecting: "Connecting"
        case .connected: "Connected"
        case .reconnecting: "Reconnecting"
        case .offline: "Offline"
        case .error: "Connection failed"
        }
    }

    private func controls(wide: Bool) -> some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                if !wide {
                    key(.back, "Back", "arrow.uturn.backward")
                    key(.home, "Home", "house.fill")
                }
                Button {
                    if model.voiceState == .listening || model.voiceState == .starting { model.stopVoice() }
                    else { model.startVoice() }
                } label: {
                    Label(model.voiceState == .listening ? "Stop" : "Voice",
                          systemImage: model.voiceState == .listening ? "stop.fill" : "mic.fill")
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.bordered)
                .disabled(!model.connected || model.voiceState == .unavailable ||
                          model.voiceState == .permissionDenied || model.voiceState == .ending)
                .accessibilityHint("Tap to start or stop sending microphone audio to the TV")
            }
            HStack(spacing: 12) {
                key(.volumeDown, "Volume down", "speaker.minus")
                key(.playPause, "Play or pause", "playpause.fill")
                key(.volumeUp, "Volume up", "speaker.plus")
            }
            HStack(spacing: 12) {
                key(.mute, "Mute", "speaker.slash")
                Button { keyboard = true } label: {
                    Label("Keyboard", systemImage: "keyboard").frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!model.connected)
            }
            DisclosureGroup("More media controls") {
                HStack(spacing: 12) {
                    key(.previous, "Previous", "backward.end.fill")
                    key(.next, "Next", "forward.end.fill")
                }
                .padding(.top, 8)
            }
            .font(.footnote)
            if model.connected {
                Text(model.voiceStatus).font(.footnote).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                if model.voiceState == .permissionDenied {
                    Button("Open microphone settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }
            }
            Text("Remote keys work while connected. Playback and volume depend on the TV and active app.")
                .font(.footnote).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private func key(_ key: RemoteKey, _ label: String, _ symbol: String) -> some View {
        Button {
            UISelectionFeedbackGenerator().selectionChanged()
            model.send(key)
        } label: {
            Image(systemName: symbol).font(.title3)
                .frame(maxWidth: .infinity, minHeight: 52)
                .contentShape(Rectangle())
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(label)
        .disabled(!model.connected)
    }

    private var keyboardSheet: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Focus a text field on the TV first. Some apps do not accept remote text input.")
                        .font(.footnote).foregroundStyle(.secondary)
                    TextField("Text for TV", text: $model.text)
                        .focused($keyboardFocused)
                        .submitLabel(.send)
                        .onSubmit { model.sendText() }
                    if model.text.utf16.count > 512 {
                        Text("Text is too long to send.").foregroundStyle(.red)
                    }
                    Button("Send text") { model.sendText() }
                        .disabled(!model.connected || model.text.isEmpty || model.text.utf16.count > 512)
                }
                Section("TV keys") {
                    HStack {
                        key(.delete, "Delete on TV", "delete.left")
                        key(.enter, "Enter on TV", "return")
                    }
                }
            }
            .navigationTitle("TV keyboard")
            .toolbar { Button("Done") { keyboard = false } }
            .onAppear { keyboardFocused = true }
        }
        .presentationDetents([.medium, .large])
    }

    private var pairingSheet: some View {
        NavigationStack {
            Form {
                Section {
                    Text(model.status)
                    if model.needsCode {
                        TextField("Six characters shown on TV", text: $code)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .keyboardType(.asciiCapable)
                        Button("Confirm pairing") { model.finishPairing(code: code) }
                            .disabled(model.busy || code.count != 6)
                    }
                }
            }
            .navigationTitle("Pair remote")
            .toolbar { Button("Close") { pairing = false } }
        }
        .presentationDetents([.medium])
    }
}

private struct DPadView: View {
    @ObservedObject var model: RemoteViewModel
    var body: some View {
        VStack(spacing: 8) {
            row(nil, .up, nil)
            row(.left, .select, .right)
            row(nil, .down, nil)
        }
        .padding(14)
        .frame(maxWidth: 280)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 32))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Directional pad")
    }
    private func row(_ left: RemoteKey?, _ middle: RemoteKey, _ right: RemoteKey?) -> some View {
        HStack(spacing: 8) {
            cell(left)
            cell(middle)
            cell(right)
        }
    }
    @ViewBuilder private func cell(_ key: RemoteKey?) -> some View {
        if let key {
            Button {
                UISelectionFeedbackGenerator().selectionChanged()
                model.send(key)
            } label: {
                Image(systemName: symbol(key))
                    .font(key == .select ? .headline : .title2)
                    .frame(maxWidth: .infinity)
                    .frame(height: 72)
                    .contentShape(RoundedRectangle(cornerRadius: 20))
            }
            .buttonStyle(.borderedProminent)
            .tint(key == .select ? .green : .gray)
            .accessibilityLabel(key == .select ? "Select" : String(describing: key).capitalized)
            .disabled(!model.connected)
        } else {
            Color.clear.frame(maxWidth: .infinity).frame(height: 72)
                .accessibilityHidden(true)
        }
    }
    private func symbol(_ key: RemoteKey) -> String {
        switch key {
        case .up: "chevron.up"
        case .down: "chevron.down"
        case .left: "chevron.left"
        case .right: "chevron.right"
        default: "circle.inset.filled"
        }
    }
}
