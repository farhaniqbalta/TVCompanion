import Foundation
import Security
import CryptoKit
import TVCompanionKit

/// Secret data never enters UserDefaults or application logs.
final class CredentialStore {
    private let service = "id.poncol.tvcompanion.credentials"
    func data(_ account: String) throws -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?; let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw CompanionError.notPaired }
        return result as? Data
    }
    func save(_ data: Data, account: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw CompanionError.notPaired }
        var item = query; item[kSecValueData as String] = data; item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw CompanionError.notPaired }
    }
}

struct RemoteIdentity {
    let identity: SecIdentity
    let publicRSA: Data
}

/// Original, minimal self-signed RSA client certificate construction using platform Security APIs.
/// The certificate is a TLS client identity, not a trust anchor or an app authorization token.
final class RemoteIdentityStore {
    private let tag = Data("id.poncol.tvcompanion.remote.rsa.v1".utf8)
    private let label = "TVCompanion Remote Client v1"
    func loadOrCreate() throws -> RemoteIdentity {
        if let result = try existing() { return result }
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: true, kSecAttrApplicationTag as String: tag,
                kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]]
        var error: Unmanaged<CFError>?
        // Reuse an orphaned key from an interrupted certificate creation rather than create duplicates.
        var item: CFTypeRef?
        let query: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag, kSecReturnRef as String: true]
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        let key: SecKey
        if status == errSecSuccess, let item { key = (item as! SecKey) }
        else {
            guard let created = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else { throw CompanionError.notPaired }
            key = created
        }
        let publicKey = try publicData(key)
        let algorithm = DER.sequence(DER.value(6, Data([0x2a,0x86,0x48,0x86,0xf7,0x0d,1,1,11])) + DER.value(5, Data()))
        let name = DER.sequence(DER.value(0x31, DER.sequence(DER.value(6, Data([0x55,4,3])) + DER.value(12, Data("TVCompanion".utf8)))))
        let date = DateFormatter(); date.locale = Locale(identifier: "en_US_POSIX"); date.timeZone = TimeZone(secondsFromGMT: 0); date.dateFormat = "yyyyMMddHHmmss'Z'"
        let validity = DER.sequence(DER.value(0x18, Data(date.string(from: Date().addingTimeInterval(-86400)).utf8)) + DER.value(0x18, Data(date.string(from: Date().addingTimeInterval(3650 * 86400)).utf8)))
        let publicAlgorithm = DER.sequence(DER.value(6, Data([0x2a,0x86,0x48,0x86,0xf7,0x0d,1,1,1])) + DER.value(5, Data()))
        let publicInfo = DER.sequence(publicAlgorithm + DER.value(3, Data([0]) + publicKey))
        var serial = Data(count: 16)
        let randomStatus = serial.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard randomStatus == errSecSuccess else { throw CompanionError.notPaired }
        serial[0] &= 0x7f
        let tbs = DER.sequence(DER.value(0xa0, DER.integer(Data([2]))) + DER.integer(serial) + algorithm + name + validity + name + publicInfo)
        guard let signature = SecKeyCreateSignature(key, .rsaSignatureMessagePKCS1v15SHA256, tbs as CFData, &error) as Data?,
              let certificate = SecCertificateCreateWithData(nil, DER.sequence(tbs + algorithm + DER.value(3, Data([0]) + signature)) as CFData) else { throw CompanionError.notPaired }
        let result = SecItemAdd([kSecClass as String: kSecClassCertificate, kSecValueRef as String: certificate,
            kSecAttrLabel as String: label, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly] as CFDictionary, nil)
        guard result == errSecSuccess || result == errSecDuplicateItem, let identity = try existing() else { throw CompanionError.notPaired }
        return identity
    }
    private func existing() throws -> RemoteIdentity? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass as String: kSecClassIdentity, kSecAttrLabel as String: label,
            kSecReturnRef as String: true, kSecMatchLimit as String: kSecMatchLimitOne] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let result else { throw CompanionError.notPaired }
        let identity = result as! SecIdentity; var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess, let certificate,
              let key = SecCertificateCopyKey(certificate) else { throw CompanionError.notPaired }
        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(key, &error) as Data? else { throw CompanionError.notPaired }
        _ = try DER.rsaNumbers(data)
        return RemoteIdentity(identity: identity, publicRSA: data)
    }
    private func publicData(_ key: SecKey) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let publicKey = SecKeyCopyPublicKey(key), let data = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else { throw CompanionError.notPaired }
        _ = try DER.rsaNumbers(data); return data
    }
}
