import Foundation
import TVCompanionKit

private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
struct NotificationHTTPClient {
    func send(_ message: NotificationEnvelope, to profile: TVProfile, pin: String) async throws -> DeliveryReceipt {
        guard let url = profile.notificationURL, !pin.isEmpty && pin.count <= 64 && !pin.contains(where: { $0.isNewline }) else { throw CompanionError.invalidNotification }
        var request = URLRequest(url: url); request.httpMethod = "POST"
        request.setValue("Bearer " + pin, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try message.encoded()
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 5; config.timeoutIntervalForResource = 10
        config.urlCache = nil; config.httpCookieStorage = nil; config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config, delegate: NoRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse, response.expectedContentLength <= 4096 else { throw CompanionError.unconfirmed }
            var data = Data()
            for try await byte in bytes {
                guard data.count < 4096 else { throw CompanionError.unconfirmed }
                data.append(byte)
            }
            return try NotificationResponse.parse(code: http.statusCode, data: data, kind: message.source)
        } catch let error as CompanionError { throw error }
        catch is CancellationError { throw CancellationError() }
        catch { throw CompanionError.unconfirmed }
    }
}
