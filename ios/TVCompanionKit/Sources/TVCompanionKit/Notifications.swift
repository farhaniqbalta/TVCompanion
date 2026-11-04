import Foundation

public struct TVProfile: Codable, Equatable, Identifiable {
    public var id: UUID
    public var name: String
    public var host: String
    public var remotePort: UInt16
    public var pairingPort: UInt16
    public var notificationPort: UInt16
    public init(id: UUID = UUID(), name: String = "Google TV", host: String, remotePort: UInt16 = 6466, pairingPort: UInt16 = 6467, notificationPort: UInt16 = 8766) throws {
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty && host.count <= 253 && !host.contains(where: { $0.isWhitespace || "/?#@\\".contains($0) }) && notificationPort > 0 && remotePort > 0 && pairingPort > 0 else { throw CompanionError.invalidAddress }
        self.id = id; self.name = String(name.prefix(80)); self.host = host
        self.remotePort = remotePort; self.pairingPort = pairingPort; self.notificationPort = notificationPort
    }
    public var notificationURL: URL? {
        var value = URLComponents(); value.scheme = "http"; value.host = host; value.port = Int(notificationPort)
        value.path = "/api/v1/notifications"; return value.url
    }
}
public enum NotificationKind: String, Codable, CaseIterable { case alert, alert2 }
public struct NotificationEnvelope: Encodable, Equatable {
    public let id: String
    public let source: NotificationKind
    public let title: String
    public let body: String
    public let ttlSeconds: Int
    public let durationSeconds: Int
    public init(id: String = UUID().uuidString, source: NotificationKind, title: String, body: String, durationSeconds: Int = 8) throws {
        guard !id.isEmpty && id.count <= 128 && title.utf16.count <= 512 && body.utf16.count <= 512 &&
            !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && (3...30).contains(durationSeconds) else { throw CompanionError.invalidNotification }
        self.id = id; self.source = source; self.title = title; self.body = body
        self.ttlSeconds = 120; self.durationSeconds = durationSeconds
    }
    public func encoded() throws -> Data {
        let bytes = try JSONEncoder().encode(self)
        guard bytes.count <= 4096 else { throw CompanionError.invalidNotification }; return bytes
    }
}
public enum DeliveryReceipt: Equatable { case queued, duplicate, filtered }
public enum NotificationResponse {
    public static func parse(code: Int, data: Data, kind: NotificationKind) throws -> DeliveryReceipt {
        guard data.count <= 4096 else { throw CompanionError.malformedMessage }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let status = json?["status"] as? String
        switch (code, status) {
        case (202, "accepted"): return .queued
        case (200, "duplicate"): return .duplicate
        case (200, "filtered"): return .filtered
        case (401, _): throw CompanionError.unauthorized
        case (400, "invalid_source") where kind == .alert2: throw CompanionError.unsupported
        case (429, _): throw CompanionError.rateLimited
        case (503, _): throw CompanionError.receiverUnavailable
        case (400, _), (410, _), (413, _), (415, _): throw CompanionError.invalidNotification
        default: throw CompanionError.unconfirmed
        }
    }
}
