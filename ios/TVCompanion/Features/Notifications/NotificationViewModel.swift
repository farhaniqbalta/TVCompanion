import Foundation
import TVCompanionKit

@MainActor final class NotificationViewModel: ObservableObject {
    @Published var kind: NotificationKind = .alert
    @Published var text = ""
    @Published var pin = ""
    @Published var status = "Messages go to the TVCompanion notification receiver."
    @Published var busy = false
    private var pending: NotificationEnvelope?
    private var operation = UUID()
    private var task: Task<Void, Never>?
    private let store: TVStore
    init(store: TVStore) { self.store = store }
    func loadPIN() {
        guard let tv = store.profile else { pin = ""; return }
        pin = (try? store.credentials.data("notification-pin-" + tv.id.uuidString)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }
    func send() {
        guard !busy, let tv = store.profile else { return }
        do {
            let draft = try NotificationEnvelope(source: kind, title: kind == .alert2 ? "Urgent message" : "Message", body: text)
            if pending?.body != draft.body || pending?.source != draft.source { pending = draft }
            let message = pending!
            guard !pin.isEmpty else { throw CompanionError.invalidNotification }
            try store.credentials.save(Data(pin.utf8), account: "notification-pin-" + tv.id.uuidString)
            busy = true; status = "Sending…"
            operation = UUID(); let token = operation
            let pin = self.pin
            task = Task {
                do {
                    let receipt = try await NotificationHTTPClient().send(message, to: tv, pin: pin)
                    guard !Task.isCancelled && operation == token else { return }
                    switch receipt {
                    case .queued: status = "Queued on TV. This does not confirm the message was displayed."
                    case .duplicate: status = "The TV already accepted this message ID."
                    case .filtered: status = "The TV filtered this source. Check notification settings."
                    }
                    pending = nil
                } catch { if !Task.isCancelled && operation == token { status = companionMessage(error) } }
                if operation == token { busy = false }
            }
        } catch { status = companionMessage(error) }
    }
    func suspend() {
        operation = UUID(); task?.cancel(); task = nil
        if busy { status = "Delivery was interrupted and may have succeeded. Retry explicitly to reuse this message ID." }
        busy = false
    }
}
