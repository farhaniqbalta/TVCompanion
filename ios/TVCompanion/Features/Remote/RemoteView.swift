import SwiftUI
import TVCompanionKit

struct RemoteView: View {
    @EnvironmentObject private var store: TVStore
    @ObservedObject var model: RemoteViewModel
    @State private var setup = false
    @State private var code = ""
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    VStack(spacing: 8) {
                        Text(store.profile?.name ?? "Your TV, close at hand.").font(.largeTitle.bold())
                        Text(model.status).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                    if let tv = store.profile {
                        HStack {
                            Button("Connect") { model.connect(tv) }.buttonStyle(.borderedProminent)
                            Button("Pair with TV") { model.pair(tv) }.buttonStyle(.bordered)
                        }.disabled(model.busy)
                    } else { Button("Choose a TV") { setup = true }.buttonStyle(.borderedProminent) }
                    if model.needsCode {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("TV pairing code").font(.headline)
                            TextField("Six characters shown on TV", text: $code).textFieldStyle(.roundedBorder)
                                .textInputAutocapitalization(.characters).autocorrectionDisabled().keyboardType(.asciiCapable)
                            Button("Confirm pairing") { model.finishPairing(code: code) }.disabled(model.busy || code.count != 6)
                        }.padding().background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
                    }
                    VStack(spacing: 12) {
                        key(.up, "Up", "chevron.up")
                        HStack(spacing: 18) { key(.left, "Left", "chevron.left"); key(.select, "Select", "circle.inset.filled"); key(.right, "Right", "chevron.right") }
                        key(.down, "Down", "chevron.down")
                    }.padding(24).background(.quaternary, in: RoundedRectangle(cornerRadius: 30))
                    HStack { key(.back, "Back", "arrow.uturn.backward"); key(.home, "Home", "house"); key(.playPause, "Play or pause", "playpause") }
                    HStack { key(.volumeDown, "Volume down", "speaker.minus"); key(.mute, "Mute", "speaker.slash"); key(.volumeUp, "Volume up", "speaker.plus") }
                    HStack { key(.previous, "Previous", "backward.end"); key(.next, "Next", "forward.end") }
                    Text("Volume and playback behavior depend on the TV and active app. Remote sessions close when this app becomes inactive.")
                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }.padding(24).frame(maxWidth: 520).frame(maxWidth: .infinity)
            }
            .navigationTitle("Remote").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button { setup = true } label: { Label("Choose TV", systemImage: "tv") } }
            .sheet(isPresented: $setup) { TVSetupView() }
        }
    }
    private func key(_ key: RemoteKey, _ label: String, _ symbol: String) -> some View {
        Button { model.send(key) } label: { Image(systemName: symbol).font(.title2).frame(width: 68, height: 56) }
            .buttonStyle(.bordered).accessibilityLabel(label).disabled(!model.connected || model.busy)
    }
}
