import Foundation
import Darwin
import TVCompanionKit

@MainActor final class TVDiscovery: NSObject, ObservableObject, @preconcurrency NetServiceBrowserDelegate, @preconcurrency NetServiceDelegate {
    @Published var devices: [TVProfile] = []
    @Published var searching = false
    private var browser: NetServiceBrowser?
    private var services: [NetService] = []
    private var deadline: Task<Void, Never>?
    func search() {
        stop(); devices = []; searching = true
        let browser = NetServiceBrowser(); self.browser = browser; browser.delegate = self
        browser.searchForServices(ofType: "_androidtvremote2._tcp.", inDomain: "local.")
        deadline = Task { try? await Task.sleep(for: .seconds(8)); if !Task.isCancelled { stop() } }
    }
    func stop() {
        deadline?.cancel(); deadline = nil; browser?.stop(); browser?.delegate = nil; browser = nil
        services.forEach { $0.stop(); $0.delegate = nil }; services = []; searching = false
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard services.count < 16 else { return }
        services.append(service); service.delegate = self; service.resolve(withTimeout: 5)
    }
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard browser != nil else { return }
        var host: String?
        for address in (sender.addresses ?? []).sorted(by: { $0.count < $1.count }) {
            host = address.withUnsafeBytes { raw -> String? in
                guard let pointer = raw.baseAddress, raw.count >= MemoryLayout<sockaddr>.size else { return nil }
                let socket = pointer.assumingMemoryBound(to: sockaddr.self)
                guard socket.pointee.sa_family == sa_family_t(AF_INET) || socket.pointee.sa_family == sa_family_t(AF_INET6) else { return nil }
                var output = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                guard getnameinfo(socket, socklen_t(raw.count), &output, socklen_t(output.count), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
                return String(cString: output)
            }
            if host != nil { break }
        }
        guard let host = host ?? sender.hostName,
              let tv = try? TVProfile(name: sender.name, host: host), !devices.contains(where: { $0.host == host }) else { return }
        devices.append(tv)
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) { stop() }
}
