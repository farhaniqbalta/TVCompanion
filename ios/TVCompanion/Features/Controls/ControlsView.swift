import SwiftUI
import UIKit
import TVCompanionKit

/// Live-state tiles: each shows only what the TV confirmed; tap switches, hold opens options.
struct ControlsView: View {
    @EnvironmentObject private var store: TVStore
    @ObservedObject var model: ControlsViewModel
    @State private var setup = false
    @State private var confirmForget = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    if store.profile == nil {
                        Button("Choose a TV") { setup = true }.buttonStyle(.borderedProminent)
                    } else if !model.paired || model.pinChanged {
                        PairCard(model: model)
                    } else {
                        if model.unreachable { offlineCard }
                        else if let banner = model.banner { bannerCard(banner) }
                        tiles
                        if let status = model.status { colours(status) }
                        Text(model.status == nil
                             ? "States appear once the TV confirms them."
                             : "Hold a tile for more options. Pull down to refresh.")
                            .font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Controls")
            .refreshable { await model.refresh() }
            .sheet(isPresented: $setup) { TVSetupView() }
            .onAppear { model.appear() }
            .alert("This stops the current playback", isPresented: $model.confirmAirPlayOff) {
                Button("Turn off", role: .destructive) { model.confirmAirPlayOffNow() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("AirPlay is playing and keeping the session is off on the TV.") }
            .confirmationDialog("Forget this TV?", isPresented: $confirmForget, titleVisibility: .visible) {
                Button("Forget", role: .destructive) { model.forget() }
            } message: { Text("You'll need a new code from the TV to use Controls again.") }
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            Button { setup = true } label: {
                HStack(spacing: 10) {
                    Image(systemName: "tv").foregroundStyle(.secondary)
                    Text(store.profile?.name ?? "No TV").font(.headline).lineLimit(1)
                    Spacer(minLength: 8)
                    chip
                }
                .padding(.horizontal, 14).frame(minHeight: 48)
                .background(Palette.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(store.profile?.name ?? "No TV"), \(chipText.text)")
            .accessibilityHint("Change TV")
            if model.paired {
                Menu {
                    Button { Task { await model.refresh() } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                    Button(role: .destructive) { confirmForget = true } label: { Label("Forget this TV", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis").font(.body.weight(.semibold)).foregroundStyle(.primary)
                        .frame(width: 48, height: 48)
                        .background(Palette.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .accessibilityLabel("More")
            }
        }
    }

    private var chipText: (text: String, tint: Color, fill: Color) {
        if store.profile == nil { return ("Choose a TV", .secondary, Palette.neutralFill) }
        if model.pinChanged { return ("Pair again", Palette.warnText, Palette.warnFill) }
        if !model.paired { return ("Not paired", .secondary, Palette.neutralFill) }
        if model.unreachable { return ("Not reachable", Palette.warnText, Palette.warnFill) }
        if model.status != nil { return ("Connected", Palette.mintText, Palette.mintFill) }
        return ("Connecting…", .secondary, Palette.neutralFill)
    }

    private var chip: some View {
        let c = chipText
        return HStack(spacing: 6) {
            Circle().fill(c.tint).frame(width: 7, height: 7)
            Text(c.text).font(.footnote.weight(.semibold)).foregroundStyle(c.tint)
        }
        .padding(.horizontal, 10).padding(.vertical, 4)
        .background(c.fill, in: Capsule())
    }

    // MARK: Messages

    private var offlineCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.triangle").foregroundStyle(Palette.warnText).font(.title3)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Can’t reach \(store.profile?.name ?? "the TV")").font(.headline)
                    Text("Check the TV is on and on the same Wi-Fi as this iPhone. If it was just updated, open TVCompanion on the TV once.")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Button { Task { await model.refresh() } } label: {
                HStack(spacing: 8) {
                    if model.refreshing { ProgressView().tint(.white) }
                    Text(model.refreshing ? "Trying…" : "Try again").fontWeight(.semibold)
                }
                .padding(.horizontal, 20).frame(minHeight: 44)
                .background(Palette.mint, in: Capsule()).foregroundStyle(.white)
            }
            .disabled(model.refreshing)
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.warnFill, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    private func bannerCard(_ text: String) -> some View {
        Label(text, systemImage: "info.circle")
            .font(.subheadline).foregroundStyle(.primary)
            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .accessibilityAddTraits(.updatesFrequently)
    }

    // MARK: Tiles

    private var tiles: some View {
        let s = model.status
        let unknown = ControlTile(isOn: false, enabled: false, detail: model.unreachable ? "Unknown" : "…")
        return Grid(horizontalSpacing: 12, verticalSpacing: 12) {
            GridRow {
                tile("Light", "lightbulb.fill", .light, s?.lightTile ?? unknown, lamp: s.flatMap(lampStyle)) { model.set(.light, .light(on: $0)) }
                    .contextMenu { if let s, s.lightTile.isOn { presetMenu(s) } }
                tile("Ambilight", "sparkles.tv", .ambilight, s?.ambilightTile ?? unknown) { model.set(.ambilight, .ambilight(on: $0)) }
                    .contextMenu { if let s, s.ambilight.state != "not_set_up" { ambilightMenu(s.ambilight) } }
            }
            GridRow {
                tile("Ambient", "waveform", .ambient, s?.ambientTile ?? unknown) { open in
                    model.set(.ambient, .ambient(open: open, look: open ? (s?.ambient.displayedLook(pending: nil)) : nil))
                }
                .contextMenu { if let s, s.ambientTile.enabled { lookMenu(s) } }
                tile("AirPlay", "airplayaudio", .airplay, s?.airplayTile ?? unknown) { model.set(.airplay, .airplay(available: $0, confirm: false)) }
            }
            GridRow {
                tile("Notifications", "bell.fill", .notifications, s?.notificationsTile ?? unknown, wide: true) { model.set(.notifications, .notifications(on: $0)) }
                    .gridCellColumns(2)
            }
        }
    }

    @ViewBuilder private func presetMenu(_ s: ControlStatus) -> some View {
        ForEach(s.light.presets, id: \.self) { name in
            Button { model.set(.light, .lightPreset(name)) } label: {
                Label(name.capitalized, systemImage: s.light.preset == name ? "checkmark" : "circle.fill")
            }
        }
    }

    /// Style and contrast apply to a running sync at once and are kept for the next one, so the menu works while off too.
    @ViewBuilder private func ambilightMenu(_ a: ControlStatus.Ambilight) -> some View {
        if let styles = a.styles {
            Section("Style") {
                ForEach(styles, id: \.self) { name in
                    optionButton(name, chosen: a.style == name) { model.set(.ambilight, .ambilightLook(style: name, contrast: nil)) }
                }
            }
        }
        if let contrasts = a.contrasts {
            Section("Contrast") {
                ForEach(contrasts, id: \.self) { name in
                    optionButton(name, chosen: a.contrast == name) { model.set(.ambilight, .ambilightLook(style: nil, contrast: name)) }
                }
            }
        }
    }

    private func optionButton(_ name: String, chosen: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(ControlNames.look(name))
            if let detail = ControlNames.ambilightDetail(name) { Text(detail) }
            if chosen { Image(systemName: "checkmark") }
        }
    }

    @ViewBuilder private func lookMenu(_ s: ControlStatus) -> some View {
        let current = s.ambient.displayedLook(pending: nil)
        ForEach(s.ambient.looks, id: \.self) { look in
            Button { model.set(.ambient, .ambient(open: true, look: look)) } label: {
                Label(s.ambient.state == "showing" ? ControlNames.look(look) : "Show with \(ControlNames.look(look))",
                      systemImage: current == look && s.ambient.state == "showing" ? "checkmark" : "sparkles")
            }
        }
    }

    private func tile(_ title: String, _ icon: String, _ feature: ControlsViewModel.Feature, _ t: ControlTile,
                      lamp: TileStyle? = nil, wide: Bool = false, set: @escaping (Bool) -> Void) -> some View {
        let waiting = model.waiting.contains(feature)
        let style = t.isOn ? (feature == .light ? (lamp ?? .on) : .on) : .off
        let detail = HStack(spacing: 6) {
            if waiting || t.inProgress { ProgressView().controlSize(.small).tint(style.detail) }
            Text(waiting ? "Updating…" : t.detail).font(.subheadline).foregroundStyle(style.detail).lineLimit(1).minimumScaleFactor(0.85)
        }
        let iconView = Image(systemName: icon).font(.system(size: 16, weight: .semibold))
            .foregroundStyle(style.iconForeground).frame(width: 34, height: 34)
            .background(style.iconBackground, in: Circle())
        return Button { if t.enabled && !waiting { set(!t.isOn) } } label: {
            Group {
                if wide {
                    HStack(spacing: 12) { iconView; Text(title).font(.body.weight(.semibold)); Spacer(minLength: 8); detail }
                        .frame(minHeight: 64)
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        iconView
                        Spacer(minLength: 12)
                        Text(title).font(.body.weight(.semibold))
                        detail
                    }
                    .frame(minHeight: 104, alignment: .leading)
                }
            }
            .foregroundStyle(style.title)
            .padding(.horizontal, 14).padding(.vertical, wide ? 0 : 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(style.background, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay {
                if waiting { RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Palette.mint, lineWidth: 2) }
            }
            .opacity(t.enabled || waiting || t.inProgress ? 1 : 0.5)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(waiting ? "Updating" : t.detail)
        .accessibilityHint(t.enabled && !waiting ? (t.isOn ? "Double-tap to turn off" : "Double-tap to turn on") : "")
        .accessibilityAddTraits(t.isOn ? [.isButton, .isSelected] : .isButton)
    }

    // MARK: Light colour

    private func colours(_ s: ControlStatus) -> some View {
        let on = s.lightTile.isOn && !model.waiting.contains(.light)
        return VStack(alignment: .leading, spacing: 10) {
            Text("Light colour").font(.subheadline.weight(.semibold))
            HStack {
                ForEach(s.light.presets, id: \.self) { name in
                    let current = s.light.preset == name
                    Button { model.set(.light, .lightPreset(name)) } label: {
                        Circle().fill(Palette.swatch(name))
                            .overlay { if name == "white" { Circle().strokeBorder(Color(uiColor: .systemGray3), lineWidth: 1.5) } }
                            .frame(width: 30, height: 30)
                            .padding(3)
                            .overlay { if current { Circle().strokeBorder(name == "white" ? Color(uiColor: .systemGray) : Palette.swatch(name), lineWidth: 2) } }
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(name.capitalized)
                    .accessibilityAddTraits(current ? [.isButton, .isSelected] : .isButton)
                    if name != s.light.presets.last { Spacer(minLength: 0) }
                }
            }
            .disabled(!on)
            .opacity(on ? 1 : 0.4)
            if !s.lightTile.isOn { Text("Turn the light on to change its colour.").font(.footnote).foregroundStyle(.secondary) }
        }
        .padding(14)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func lampStyle(_ s: ControlStatus) -> TileStyle? {
        guard s.light.state == "on", let preset = s.light.preset else { return nil }
        return TileStyle.lamp(preset)
    }
}

// MARK: Pairing

private struct PairCard: View {
    @ObservedObject var model: ControlsViewModel
    @State private var code = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text(model.pinChanged ? "Pair again" : "Pair this iPhone").font(.title2.bold())
                Text(model.pinChanged
                     ? "The TV’s identity changed. On the TV, open TVCompanion and choose Pair iPhone for Controls, then enter the new code."
                     : "On the TV, open TVCompanion and choose Pair iPhone for Controls. Enter the 6-digit code it shows.")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Text("Code from the TV").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
            ZStack {
                TextField("", text: $code)
                    .keyboardType(.numberPad).textContentType(.oneTimeCode)
                    .focused($focused)
                    .opacity(0.02)
                    .accessibilityLabel("Code from the TV")
                    .accessibilityValue(code.isEmpty ? "Empty" : code.map(String.init).joined(separator: " "))
                HStack(spacing: 8) {
                    ForEach(0..<6, id: \.self) { i in
                        let chars = Array(code)
                        Text(i < chars.count ? String(chars[i]) : "")
                            .font(.title.weight(.semibold).monospacedDigit())
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .background(i < chars.count ? Palette.card : Color(uiColor: .systemGroupedBackground),
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .strokeBorder(focused && i == min(chars.count, 5) ? Palette.mint : Color(uiColor: .systemGray3),
                                                  lineWidth: focused && i == min(chars.count, 5) ? 2 : 1)
                            }
                    }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
            .onTapGesture { focused = true }
            .onChange(of: code) { _, value in
                let digits = String(value.filter(\.isASCIIDigit).prefix(6))
                if digits != value { code = digits; return }
                if digits.count == 6 { submit() }
            }
            .disabled(model.pairing)

            if model.pairing {
                HStack(spacing: 8) { ProgressView(); Text("Pairing…").foregroundStyle(.secondary) }.font(.subheadline)
            } else if let banner = model.banner {
                Text(banner).font(.subheadline).foregroundStyle(Palette.warnText)
            } else {
                Text("Pairs as soon as the sixth digit is in. The code works for 2 minutes.").font(.footnote).foregroundStyle(.secondary)
            }

            Button {
                let digits = String((UIPasteboard.general.string ?? "").filter(\.isASCIIDigit).prefix(6))
                if !digits.isEmpty { code = digits }
            } label: {
                Label("Paste code", systemImage: "doc.on.clipboard").fontWeight(.semibold)
                    .padding(.horizontal, 18).frame(minHeight: 44)
                    .background(Palette.mintFill, in: Capsule()).foregroundStyle(Palette.mintText)
            }
            .disabled(model.pairing)
        }
        .padding(18)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .onAppear { focused = true }

        Text("Up to four iPhones or iPads can be paired with one TV. Remove them on the TV under Pair iPhone for Controls.")
            .font(.footnote).foregroundStyle(.secondary)
    }

    private func submit() {
        let entered = code
        Task {
            if await model.pair(code: entered) { code = "" } else { code = ""; focused = true }
        }
    }
}

private extension Character {
    var isASCIIDigit: Bool { isASCII && isNumber }
}

// MARK: Palette

private struct TileStyle {
    let background: Color, title: Color, detail: Color, iconBackground: Color, iconForeground: Color
    static let off = TileStyle(background: Palette.card, title: .primary, detail: .secondary,
                               iconBackground: Color(uiColor: .tertiarySystemFill), iconForeground: .primary)
    static let on = TileStyle(background: Palette.mintFill, title: .primary, detail: Palette.mintText,
                              iconBackground: Palette.mint, iconForeground: .white)
    /// The light tile wears the lamp's colour; light colours keep dark text for contrast.
    static func lamp(_ preset: String) -> TileStyle {
        let dark = preset == "yellow" || preset == "white"
        let ink: Color = dark ? Color(red: 0.11, green: 0.11, blue: 0.12) : .white
        return TileStyle(background: Palette.lampTile(preset), title: ink, detail: ink.opacity(0.9),
                         iconBackground: ink.opacity(0.18), iconForeground: ink)
    }
}

private enum Palette {
    static let mint = Color(red: 0.18, green: 0.61, blue: 0.45)                       // #2E9C74
    static let card = Color(uiColor: .secondarySystemGroupedBackground)
    static let neutralFill = Color(uiColor: .tertiarySystemFill)
    static let mintFill = dynamic(light: (0.89, 0.95, 0.93), dark: (0.10, 0.25, 0.20))  // #E3F3EC
    static let mintText = dynamic(light: (0.12, 0.42, 0.32), dark: (0.55, 0.85, 0.72))  // #1F6B52
    static let warnFill = dynamic(light: (1.00, 0.96, 0.93), dark: (0.28, 0.17, 0.08))  // #FFF6EE
    static let warnText = dynamic(light: (0.54, 0.23, 0.03), dark: (0.98, 0.70, 0.45))  // #8A3B07

    static func swatch(_ name: String) -> Color {
        switch name {
        case "red": return Color(red: 0.90, green: 0.28, blue: 0.30)
        case "orange": return Color(red: 0.95, green: 0.60, blue: 0.29)
        case "yellow": return Color(red: 0.96, green: 0.77, blue: 0.32)
        case "green": return Color(red: 0.25, green: 0.70, blue: 0.50)
        case "blue": return Color(red: 0.24, green: 0.39, blue: 0.87)
        case "purple": return Color(red: 0.56, green: 0.36, blue: 0.85)
        default: return .white
        }
    }
    /// Deeper tones than the swatches so white tile text stays readable (≥4.5:1).
    static func lampTile(_ name: String) -> Color {
        switch name {
        case "red": return Color(red: 0.75, green: 0.20, blue: 0.22)
        case "orange": return Color(red: 0.69, green: 0.33, blue: 0.05)
        case "yellow": return Color(red: 0.96, green: 0.77, blue: 0.32)
        case "green": return Color(red: 0.11, green: 0.47, blue: 0.32)
        case "blue": return Color(red: 0.24, green: 0.39, blue: 0.87)
        case "purple": return Color(red: 0.45, green: 0.27, blue: 0.75)
        default: return Color(red: 0.96, green: 0.94, blue: 0.89)
        }
    }
    private static func dynamic(light: (Double, Double, Double), dark: (Double, Double, Double)) -> Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark
            ? UIColor(red: dark.0, green: dark.1, blue: dark.2, alpha: 1)
            : UIColor(red: light.0, green: light.1, blue: light.2, alpha: 1) })
    }
}

// MARK: Preview

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
