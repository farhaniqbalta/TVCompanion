# Quick Controls — Phase 3 (iOS Controls tab) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A third iOS tab, "Controls", that pairs with the TV and drives Ambient, Ambilight, the Bardi light, the notification receiver and AirPlay over the `controls-v1` contract. It shows only the state the TV reports.

**Architecture:**
- **Order:** the owner chose to build iOS before the Android listener (Phase 2). Against a real TV the tab shows "TV not reachable" until Phase 2 ships.
- **Kit:** wire types and the pairing proof live in `TVCompanionKit`, unit-tested.
- **Transport:** `ControlClient` reuses `TLSChannel`, `RemoteIdentityStore` and `CredentialStore`.
- **UI:** a `ControlsViewModel` + `ControlsView` pair follows the `NotificationViewModel`/`NotificationSenderView` pattern. A small `ControlService` protocol lets SwiftUI previews use a fake TV.

**Tech Stack:** Swift 6 toolchain (language mode 5), SwiftUI, iOS 17, Network.framework, XCTest in `ios/TVCompanionKit`.

**Spec:** `docs/superpowers/specs/2026-11-22-quick-controls-design.md` (Phase 3) and the wire contract `contracts/controls-v1.md`. The contract wins on field names and values.

## Global Constraints

- Branch `feature/quick-controls`. Nothing is installed on any device.
- No new dependencies. The Remote and Send message tabs stay unchanged.
- No background polling. Refresh on tab appear, on pull-to-refresh, after each command response, and once ~2 s after a `pending` outcome.
- No optimistic UI. A control shows the TV's reported state; while waiting it shows a spinner.
- Unknown `state`/`outcome` values disable that one control, never the whole tab.
- Controls never use the notification PIN. Pins and keys are stored in Keychain via `CredentialStore`; no secrets or addresses in logs.
- Port `ControlWire.port = 7380`. `TVProfile` is unchanged (no migration).
- New app files must be added to `ios/TVCompanion.xcodeproj/project.pbxproj`: PBXFileReference, PBXBuildFile, the main group's `children`, and the app target's Sources phase. Follow the existing entries' style, using 24-hex-digit IDs not already present.
- Commands: Kit tests use `cd ios/TVCompanionKit && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`. The app build uses `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project TVCompanion.xcodeproj -scheme TVCompanion -destination 'generic/platform=iOS Simulator' -derivedDataPath <scratchpad>/dd build` (expected `** BUILD SUCCEEDED **`).

## Review Focus

1. A response with an unknown `state` for one feature (e.g. a newer TV). The rest of the tab still works; that control is disabled (`testUnknownStatesAndOutcomesDecodeWithoutFailing`).
2. The TV answers `would_end_playback` to AirPlay off. The user is asked, and only a confirmed second request carries `confirm: true` (VM path; encoding pinned by `testRequestsEncodeExactlyPerContract`).
3. A wrong or malformed pairing code. It is rejected locally before any network use (`testPairingProofMatchesContractAndRejectsBadCodes`).
4. An oversized or wrong-version response. It is rejected as malformed, with no crash (`testResponseRejectsOversizeAndWrongVersion`).
5. The TV is unreachable or the pin changed. One clear banner appears, controls are disabled, and the pairing state is kept unless the identity changed (`certificateChanged` → "Pair again").

---

### Task 1: `ControlWire` in TVCompanionKit

**Files:**
- Create: `ios/TVCompanionKit/Sources/TVCompanionKit/ControlWire.swift`
- Modify test: `ios/TVCompanionKit/Tests/TVCompanionKitTests/CompanionTests.swift` (append tests)

**Interfaces — Produces:**
```swift
public enum ControlWire {
    public static let port: UInt16 = 7380
    /// SHA-256( utf8(code) ‖ SHA-256(clientRSA) ‖ SHA-256(serverRSA) ) as 64 lowercase hex. Code: exactly 6 ASCII digits, else CompanionError.invalidCode.
    public static func pairingProof(code: String, clientRSA: Data, serverRSA: Data) throws -> String
}
public enum ControlRequest: Equatable {
    case status, pair(proof: String)
    case ambient(open: Bool, look: String?), ambilight(on: Bool)
    case light(on: Bool), lightPreset(String), notifications(on: Bool)
    case airplay(available: Bool, confirm: Bool)
    public func encoded() throws -> Data   // JSONSerialization, .sortedKeys; ≤ 4096 bytes else CompanionError.oversizedMessage
}
public enum ControlOutcome: String, Decodable { case ok, pending, busy, unavailable, unconfirmed, needsSetup = "needs_setup", needsApproval = "needs_approval", invalid, denied, unknown }  // unknown raw → .unknown
public struct ControlStatus: Decodable, Equatable {
    public struct Ambient: Decodable, Equatable { public let state: String; public let look: String?; public let looks: [String] }
    public struct Light: Decodable, Equatable { public let state: String; public let preset: String?; public let presets: [String] }
    public struct Simple: Decodable, Equatable { public let state: String }
    public struct AirPlay: Decodable, Equatable { public let state: String; public let available: Bool }
    public let ambient: Ambient; public let ambilight: Simple; public let light: Light; public let notifications: Simple; public let airplay: AirPlay
}
public struct ControlResponse: Decodable, Equatable {
    public let outcome: ControlOutcome; public let reason: String?; public let status: ControlStatus?
    public static func decode(_ data: Data) throws -> ControlResponse   // > 16384 bytes → .oversizedMessage; bad JSON or v != 1 → .malformedMessage
}
```
`ControlRequest` JSON keys follow `contracts/controls-v1.md` exactly. `ambient(open: false, look: nil)` omits `look`.

- [ ] **Step 1: Write the failing tests**
```swift
func testRequestsEncodeExactlyPerContract() throws {
    func json(_ r: ControlRequest) throws -> String { String(decoding: try r.encoded(), as: UTF8.self) }
    XCTAssertEqual(try json(.status), #"{"cmd":"status","v":1}"#)
    XCTAssertEqual(try json(.ambient(open: true, look: "aurora")), #"{"cmd":"ambient.set","look":"aurora","open":true,"v":1}"#)
    XCTAssertEqual(try json(.ambient(open: false, look: nil)), #"{"cmd":"ambient.set","open":false,"v":1}"#)
    XCTAssertEqual(try json(.lightPreset("blue")), #"{"cmd":"light.preset","name":"blue","v":1}"#)
    XCTAssertEqual(try json(.airplay(available: false, confirm: true)), #"{"available":false,"cmd":"airplay.set","confirm":true,"v":1}"#)
    XCTAssertEqual(try json(.notifications(on: true)), #"{"cmd":"notifications.set","on":true,"v":1}"#)
}
func testPairingProofMatchesContractAndRejectsBadCodes() throws {
    let client = Data([1, 2, 3]), server = Data([4, 5, 6])
    let expected = SHA256.hash(data: Data("123456".utf8) + Data(SHA256.hash(data: client)) + Data(SHA256.hash(data: server)))
        .map { String(format: "%02x", $0) }.joined()
    XCTAssertEqual(try ControlWire.pairingProof(code: " 123456 ", clientRSA: client, serverRSA: server), expected)
    for bad in ["12345", "1234567", "12a456", "１２３４５６"] {
        XCTAssertThrowsError(try ControlWire.pairingProof(code: bad, clientRSA: client, serverRSA: server))
    }
}
func testResponseDecodesFullStatus() throws {
    let body = #"{"v":1,"outcome":"pending","reason":null,"status":{"ambient":{"state":"available","look":"aurora","looks":["aurora","dusk"]},"ambilight":{"state":"needs_approval"},"light":{"state":"on","preset":"blue","presets":["blue","white"]},"notifications":{"state":"listening"},"airplay":{"state":"playing","available":true}}}"#
    let r = try ControlResponse.decode(Data(body.utf8))
    XCTAssertEqual(r.outcome, .pending); XCTAssertEqual(r.status?.ambilight.state, "needs_approval")
    XCTAssertEqual(r.status?.light.preset, "blue"); XCTAssertEqual(r.status?.airplay.available, true)
}
func testUnknownStatesAndOutcomesDecodeWithoutFailing() throws {
    let body = #"{"v":1,"outcome":"teleported","status":{"ambient":{"state":"dancing","looks":[]},"ambilight":{"state":"off"},"light":{"state":"off","presets":[]},"notifications":{"state":"paused"},"airplay":{"state":"stopped","available":false}}}"#
    let r = try ControlResponse.decode(Data(body.utf8))
    XCTAssertEqual(r.outcome, .unknown); XCTAssertEqual(r.status?.ambient.state, "dancing"); XCTAssertNil(r.status?.ambient.look)
}
func testResponseRejectsOversizeAndWrongVersion() {
    XCTAssertThrowsError(try ControlResponse.decode(Data(count: 16_385))) { XCTAssertEqual($0 as? CompanionError, .oversizedMessage) }
    XCTAssertThrowsError(try ControlResponse.decode(Data(#"{"v":2,"outcome":"ok"}"#.utf8))) { XCTAssertEqual($0 as? CompanionError, .malformedMessage) }
    XCTAssertThrowsError(try ControlResponse.decode(Data("nope".utf8))) { XCTAssertEqual($0 as? CompanionError, .malformedMessage) }
}
```
- [ ] **Step 2:** Run the Kit tests. Expected: FAIL (compile: `ControlRequest` not found).
- [ ] **Step 3:** Implement `ControlWire.swift`.
- [ ] **Step 4:** Run the Kit tests. Expected: `Executed 20 tests, with 0 failures`.
- [ ] **Step 5:** Commit `Add the Quick Controls wire contract to TVCompanionKit`.

---

### Task 2: `ControlClient` (transport + pairing storage)

**Files:**
- Create: `ios/TVCompanion/Infrastructure/ControlClient.swift`
- Modify: `ios/TVCompanion.xcodeproj/project.pbxproj` (add the file)

**Interfaces — Consumes:** Task 1. **Produces:**
```swift
protocol ControlService: AnyObject {
    func isPaired(_ tv: TVProfile) -> Bool
    func pair(_ tv: TVProfile, code: String) async throws          // throws .invalidCode (bad code or TV said denied)
    func send(_ request: ControlRequest, to tv: TVProfile) async throws -> ControlResponse   // throws .notPaired, .certificateChanged, .timedOut, .disconnected, .malformedMessage
    func forget(_ tv: TVProfile)
}
final class ControlClient: ControlService { init(credentials: CredentialStore) }
```
Behaviour:
- **Pin storage:** the pin is stored in Keychain account `"controls-pin-" + tv.id.uuidString`.
- **`pair`:**
  - validates the code with `ControlWire.pairingProof` before connecting;
  - opens `TLSChannel(host: tv.host, port: ControlWire.port, identity: identity.identity, expectedServerKey: nil)` and computes the proof from `identity.publicRSA` + `channel.serverRSA`;
  - sends `.pair`, reads one frame (`timeout: 10`), and on `.ok` saves `Data(SHA256.hash(data: serverRSA))`;
  - maps `.denied` to `.invalidCode`.
- **`send`:**
  - requires a pin (else `.notPaired`) and connects with `expectedServerKey: pin`;
  - writes one request and reads one frame with `timeout: 10`, then closes;
  - maps `.denied` to `.remoteRejected`.
- **`forget`:** saves empty `Data()` (`CredentialStore` has no delete). `isPaired` treats an empty pin as unpaired.
- **Lifetime:** every call uses its own `TLSChannel` and closes it in `defer`.

- [ ] **Step 1:** Implement `ControlClient.swift` and add it to `project.pbxproj`.
- [ ] **Step 2:** Run the app build. Expected: `** BUILD SUCCEEDED **`. (No unit test: this is the network adapter; the wire is covered by Task 1, and end-to-end is a Phase 2 device check.)
- [ ] **Step 3:** Commit `Add the Quick Controls TLS client`.

---

### Task 3: Controls tab (view model + view)

**Files:**
- Create: `ios/TVCompanion/Features/Controls/ControlsViewModel.swift`
- Create: `ios/TVCompanion/Features/Controls/ControlsView.swift`
- Modify: `ios/TVCompanion/App/CompanionApp.swift` (third tab, suspend/refresh hooks)
- Modify: `ios/TVCompanion/App/TVStore.swift` (`companionMessage` gains nothing; reuse existing messages)
- Modify: `ios/TVCompanion.xcodeproj/project.pbxproj` (add both files)

**Interfaces — Consumes:** Task 2's `ControlService`. **Produces:**
```swift
@MainActor final class ControlsViewModel: ObservableObject {
    enum Feature: Hashable { case ambient, ambilight, light, notifications, airplay }
    @Published private(set) var status: ControlStatus?
    @Published private(set) var waiting: Set<Feature> = []
    @Published private(set) var banner: String?          // unreachable / error copy via companionMessage
    @Published private(set) var paired = false
    @Published var confirmAirPlayOff = false             // drives the alert
    init(store: TVStore, service: ControlService)
    func appear()                                         // paired = service.isPaired; refresh if paired
    func refresh() async
    func set(_ feature: Feature, _ request: ControlRequest)  // marks waiting, sends, applies response.status, maps outcome to banner; .pending → one refresh after 2 s; airplay reason "would_end_playback" → confirmAirPlayOff = true
    func confirmAirPlayOffNow()                           // re-sends .airplay(available: false, confirm: true)
    func pair(code: String) async -> Bool
    func forget()
    func suspend()                                        // cancel in-flight tasks, clear waiting
}
```
UI (`Form`, existing tint, `.refreshable { await model.refresh() }`, `navigationTitle("Controls")`):
- **Unpaired:** a "Pair with TV" section with a one-line explanation ("Open Pair iPhone on the TV, then enter its 6-digit code") and a code `TextField(.numberPad)` + Pair button.
- **Destination:** TV name button → `TVSetupView` sheet, same as Send message.
- **Ambient:**
  - `Toggle("Visualizer")`: on = `state == "showing"`; enabled for `available`/`showing`.
  - Look `Picker` (menu) from `looks`. Picking a look while showing sends `.ambient(open: true, look:)`.
  - Footnote when `unavailable`: "Play music to the TV over AirPlay first."
- **Ambilight:**
  - `Toggle`: on = `running`/`starting`/`needs_approval`; enabled unless `stopping`/`not_set_up`/unknown.
  - Footnote: `needs_approval` → "Approve screen capture on the TV."; `not_set_up` → "Set up the LED strip on the TV first."
- **Light:**
  - `Toggle("Power")`: on = `state == "on"`.
  - Preset buttons in a `LazyVGrid` from `presets` (title-cased), enabled only when `on`. The current `preset` is marked with a checkmark.
- **Notifications:** `Toggle("Receiver")`: on = `listening`/`starting`.
- **AirPlay:**
  - `Toggle("Available in background")` bound to `available`.
  - A state line: `playing` → "Playing now", `ready` → "Ready", otherwise "Off".
  - `.alert("This stops the current playback", isPresented: $model.confirmAirPlayOff)` with Turn off / Cancel.
- **Waiting and errors:** a feature in `waiting` shows a `ProgressView` in place of its toggle. `banner` shows as a section at top.
- **Forget:** a "Forget this TV" button at the bottom when paired.
- Toggle bindings: `get` reads the TV state; `set` calls `model.set(...)` and never mutates `status` locally.
- `#Preview` with a `PreviewControlService` (file-private in `ControlsView.swift`) returning a fixed status.
- **CompanionApp:** add `@StateObject controls = ControlsViewModel(store:service: ControlClient(credentials: store.credentials))`, a third tab `Label("Controls", systemImage: "switch.2")`, call `controls.suspend()` on background and on profile change. `appear()` is called by the view's `.onAppear`.

- [ ] **Step 1:** Implement the view model, view, app wiring and pbxproj entries.
- [ ] **Step 2:** Run the app build. Expected: `** BUILD SUCCEEDED **`.
- [ ] **Step 3:** Run the Kit tests. Expected: still 20/20.
- [ ] **Step 4:** Commit `Add the Quick Controls tab`.

---

### Task 4: Docs and stop

**Files:** Modify `contracts/client-commands-draft.md` (first line: superseded by `controls-v1.md`). Modify `docs/VERIFICATION.md` (append a "Quick Controls iOS tab" section: build/tests run, and device checks pending Phase 2).

- [ ] **Step 1:** Write both doc changes.
- [ ] **Step 2:** Run the app build and Kit tests one final time. Expected: SUCCEEDED, 20/20.
- [ ] **Step 3:** Commit `Record Quick Controls iOS verification`.
