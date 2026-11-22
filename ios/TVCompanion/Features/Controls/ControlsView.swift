import SwiftUI
import TVCompanionKit

struct ControlsView: View {
    @EnvironmentObject private var store: TVStore
    @ObservedObject var model: ControlsViewModel
    @State private var setup = false
    @State private var code = ""
    @State private var look: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Destination") {
                    Button(store.profile?.name ?? "Choose a TV") { setup = true }
                }
                if let banner = model.banner {
                    Section { Label(banner, systemImage: "exclamationmark.triangle").foregroundStyle(.secondary) }
                }
                if store.profile != nil && (!model.paired || model.pinChanged) { pairSection }
                if model.paired && !model.pinChanged { features }
            }
            .navigationTitle("Controls")
            .refreshable { await model.refresh() }
            .sheet(isPresented: $setup) { TVSetupView() }
            .onAppear { model.appear() }
            .onChange(of: store.profile?.id) { _, _ in look = nil }
            .alert("This stops the current playback", isPresented: $model.confirmAirPlayOff) {
                Button("Turn off", role: .destructive) { model.confirmAirPlayOffNow() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("AirPlay is playing and keeping the session is off on the TV.") }
        }
    }

    private var pairSection: some View {
        Section {
            Text("On the TV, open TVCompanion and choose Pair iPhone, then enter the 6-digit code it shows.")
                .font(.footnote).foregroundStyle(.secondary)
            TextField("6-digit code", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode)
            Button(model.pairing ? "Pairing…" : "Pair with TV") {
                Task { if await model.pair(code: code) { code = "" } }
            }.disabled(model.pairing || code.count != 6)
        } header: { Text("Pair with TV") }
    }

    @ViewBuilder private var features: some View {
        if let status = model.status {
            ambient(status.ambient)
            ambilight(status.ambilight.state)
            light(status.light)
            Section("Notifications") {
                let state = status.notifications.state
                row("Receiver", .notifications, on: ["listening", "starting"].contains(state),
                    enabled: ["listening", "starting", "paused", "failed"].contains(state)) { model.set(.notifications, .notifications(on: $0)) }
                if state == "failed" { note("The receiver failed on the TV. Check its overlay permission.") }
            }
            airplay(status.airplay)
        } else {
            Section { Text("Pull down to refresh.").foregroundStyle(.secondary) }
        }
        Section { Button("Forget this TV", role: .destructive) { model.forget() } }
    }

    private func ambient(_ ambient: ControlStatus.Ambient) -> some View {
        let showing = ambient.state == "showing"
        let chosen = ambient.displayedLook(pending: look)
        return Section("Ambient") {
            row("Visualizer", .ambient, on: showing, enabled: ["available", "showing"].contains(ambient.state) && chosen != nil) { open in
                model.set(.ambient, .ambient(open: open, look: open ? chosen : nil))
            }
            if !ambient.looks.isEmpty {
                // While showing, the picker reflects only the TV's look; a pick is a request, not a local change.
                Picker("Look", selection: Binding(get: { chosen ?? "" }, set: { next in
                    if showing { model.set(.ambient, .ambient(open: true, look: next)) } else { look = next }
                })) { ForEach(ambient.looks, id: \.self) { Text($0.capitalized).tag($0) } }
                .disabled(model.waiting.contains(.ambient))
            }
            if ambient.state == "unavailable" { note("Play music to the TV over AirPlay first.") }
        }
    }

    private func ambilight(_ state: String) -> some View {
        Section("Ambilight") {
            row("Screen lighting", .ambilight, on: ["running", "starting", "needs_approval"].contains(state),
                enabled: ["off", "running", "starting", "needs_approval", "error"].contains(state)) { model.set(.ambilight, .ambilight(on: $0)) }
            switch state {
            case "needs_approval": note("Approve screen capture on the TV.")
            case "not_set_up": note("Set up the LED strip in TVCompanion on the TV first.")
            case "error": note("Ambilight stopped with an error. Check the TV.")
            default: EmptyView()
            }
        }
    }

    private func light(_ light: ControlStatus.Light) -> some View {
        let on = light.state == "on"
        return Section("Light") {
            row("Power", .light, on: on, enabled: on || light.state == "off") { model.set(.light, .light(on: $0)) }
            if !light.presets.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 92))], spacing: 8) {
                    ForEach(light.presets, id: \.self) { name in
                        Button { model.set(.light, .lightPreset(name)) } label: {
                            Label(name.capitalized, systemImage: light.preset == name ? "checkmark.circle.fill" : "circle")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityAddTraits(light.preset == name ? .isSelected : [])
                    }
                }
                .disabled(!on || model.waiting.contains(.light))
            }
            switch light.state {
            case "unavailable": note("The light isn't responding. Check it's powered and on the network.")
            case "not_set_up": note("The light isn't set up on the TV.")
            default: EmptyView()
            }
        }
    }

    private func airplay(_ airplay: ControlStatus.AirPlay) -> some View {
        Section("AirPlay") {
            row("Available in background", .airplay, on: airplay.available,
                enabled: ["stopped", "starting", "ready", "playing", "failed"].contains(airplay.state)) { model.set(.airplay, .airplay(available: $0, confirm: false)) }
            note(airplayLine(airplay.state))
        }
    }

    /// A toggle that only ever shows the TV's state; while a change is in flight it shows a spinner instead.
    private func row(_ title: String, _ feature: ControlsViewModel.Feature, on: Bool, enabled: Bool, set: @escaping (Bool) -> Void) -> some View {
        HStack {
            Text(title)
            Spacer()
            if model.waiting.contains(feature) {
                ProgressView().accessibilityLabel("\(title), updating")
            } else {
                Toggle(title, isOn: Binding(get: { on }, set: set)).labelsHidden().disabled(!enabled)
            }
        }
    }

    private func airplayLine(_ state: String) -> String {
        switch state {
        case "playing": "Playing now"
        case "ready": "Ready for AirPlay"
        case "starting": "Starting…"
        case "failed": "AirPlay failed on the TV"
        default: "Off"
        }
    }

    private func note(_ text: String) -> some View { Text(text).font(.footnote).foregroundStyle(.secondary) }
}

private final class PreviewControlService: ControlService {
    func isPaired(_ tv: TVProfile) -> Bool { true }
    func pair(_ tv: TVProfile, code: String) async throws {}
    func forget(_ tv: TVProfile) {}
    func send(_ request: ControlRequest, to tv: TVProfile) async throws -> ControlResponse {
        let body = #"{"v":1,"outcome":"ok","status":{"ambient":{"state":"available","look":"aurora","looks":["aurora","strings","dusk","ripples"]},"ambilight":{"state":"off"},"light":{"state":"on","preset":"blue","presets":["red","orange","yellow","green","blue","purple","white"]},"notifications":{"state":"listening"},"airplay":{"state":"playing","available":true}}}"#
        return try ControlResponse.decode(Data(body.utf8))
    }
}

#Preview {
    let store = TVStore()
    return ControlsView(model: ControlsViewModel(store: store, service: PreviewControlService())).environmentObject(store)
}
