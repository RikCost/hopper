import CryptoKit
import Foundation
import Security

enum HopperLanCertificate {
    struct Identity {
        let secIdentity: SecIdentity
        let fingerprint: String
        fileprivate let label: String
        fileprivate let keyTag: Data

        func dispose() {
            SecItemDelete([
                kSecClass: kSecClassIdentity,
                kSecAttrLabel: label,
            ] as CFDictionary)
            SecItemDelete([
                kSecClass: kSecClassCertificate,
                kSecAttrLabel: label,
            ] as CFDictionary)
            SecItemDelete([
                kSecClass: kSecClassKey,
                kSecAttrApplicationTag: keyTag,
            ] as CFDictionary)
        }
    }

    static func fingerprint(der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    static func makeEphemeral() throws -> Identity {
        let signing = P256.Signing.PrivateKey()
        let certDER = try SelfSignedX509.make(privateKey: signing, commonName: "hopper-lan")
        guard let certificate = SecCertificateCreateWithData(nil, certDER as CFData) else {
            throw HopperLanError.identityFailed
        }

        let keyAttrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
        ]
        var keyError: Unmanaged<CFError>?
        guard let secKey = SecKeyCreateWithData(
            signing.x963Representation as CFData,
            keyAttrs as CFDictionary,
            &keyError
        ) else {
            throw HopperLanError.identityFailed
        }

        let label = "hopper-lan-\(UUID().uuidString)"
        let keyTag = Data(label.utf8)
        SecItemDelete([kSecClass: kSecClassCertificate, kSecAttrLabel: label] as CFDictionary)
        SecItemDelete([kSecClass: kSecClassKey, kSecAttrApplicationTag: keyTag] as CFDictionary)

        let certStatus = SecItemAdd([
            kSecClass: kSecClassCertificate,
            kSecValueRef: certificate,
            kSecAttrLabel: label,
        ] as CFDictionary, nil)
        guard certStatus == errSecSuccess || certStatus == errSecDuplicateItem else {
            throw HopperLanError.identityFailed
        }

        let keyStatus = SecItemAdd([
            kSecClass: kSecClassKey,
            kSecAttrApplicationTag: keyTag,
            kSecAttrLabel: label,
            kSecValueRef: secKey,
        ] as CFDictionary, nil)
        guard keyStatus == errSecSuccess || keyStatus == errSecDuplicateItem else {
            throw HopperLanError.identityFailed
        }

        var match: CFTypeRef?
        let copyStatus = SecItemCopyMatching([
            kSecClass: kSecClassIdentity,
            kSecAttrLabel: label,
            kSecReturnRef: true,
        ] as CFDictionary, &match)
        guard copyStatus == errSecSuccess, let identity = match else {
            throw HopperLanError.identityFailed
        }

        return Identity(
            secIdentity: identity as! SecIdentity,
            fingerprint: fingerprint(der: certDER),
            label: label,
            keyTag: keyTag
        )
    }
}

private enum SelfSignedX509 {
    static func make(privateKey: P256.Signing.PrivateKey, commonName: String) throws -> Data {
        let tbs = try tbsCertificate(publicPoint: privateKey.publicKey.x963Representation, commonName: commonName)
        let signature = try privateKey.signature(for: tbs)
        let cert = ASN1.sequence(
            tbs
            + ASN1.sequence(ASN1.oid(OID.ecdsaSHA256))
            + ASN1.bitString(signature.derRepresentation)
        )
        return cert
    }

    static func tbsCertificate(publicPoint: Data, commonName: String) throws -> Data {
        var serialBytes = Data(count: 16)
        serialBytes.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        serialBytes[0] &= 0x7F
        if serialBytes[0] == 0 { serialBytes[0] = 1 }

        let name = distinguishedName(commonName)
        let now = Date()
        let validity = ASN1.sequence(
            ASN1.utcTime(now.addingTimeInterval(-3600))
            + ASN1.utcTime(now.addingTimeInterval(86_400))
        )
        let spki = ASN1.sequence(
            ASN1.sequence(ASN1.oid(OID.ecPublicKey) + ASN1.oid(OID.prime256v1))
            + ASN1.bitString(publicPoint)
        )
        return ASN1.sequence(
            ASN1.context(0, ASN1.integer(Data([0x02])))
            + ASN1.integer(serialBytes)
            + ASN1.sequence(ASN1.oid(OID.ecdsaSHA256))
            + name
            + validity
            + name
            + spki
        )
    }

    static func distinguishedName(_ cn: String) -> Data {
        ASN1.sequence(
            ASN1.set(
                ASN1.sequence(ASN1.oid(OID.commonName) + ASN1.utf8(cn))
            )
        )
    }
}

private enum OID {
    static let commonName: [UInt8] = [0x55, 0x04, 0x03]
    static let ecPublicKey: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01]
    static let prime256v1: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07]
    static let ecdsaSHA256: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02]
}

private enum ASN1 {
    static func length(_ n: Int) -> Data {
        if n < 0x80 { return Data([UInt8(n)]) }
        if n < 0x100 { return Data([0x81, UInt8(n)]) }
        return Data([0x82, UInt8(n >> 8), UInt8(n & 0xFF)])
    }

    static func node(_ tag: UInt8, _ content: Data) -> Data {
        Data([tag]) + length(content.count) + content
    }

    static func sequence(_ content: Data) -> Data { node(0x30, content) }
    static func set(_ content: Data) -> Data { node(0x31, content) }
    static func oid(_ bytes: [UInt8]) -> Data { node(0x06, Data(bytes)) }
    static func utf8(_ text: String) -> Data { node(0x0C, Data(text.utf8)) }

    static func integer(_ bytes: Data) -> Data {
        var value = bytes
        while value.count > 1, value[0] == 0, value[1] < 0x80 {
            value.removeFirst()
        }
        if let first = value.first, first >= 0x80 {
            value.insert(0x00, at: 0)
        }
        return node(0x02, value)
    }

    static func bitString(_ bytes: Data) -> Data {
        node(0x03, Data([0x00]) + bytes)
    }

    static func context(_ n: UInt8, _ content: Data) -> Data {
        node(0xA0 + n, content)
    }

    static func utcTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        return node(0x17, Data(formatter.string(from: date).utf8))
    }
}
