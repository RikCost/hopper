import CryptoKit
import Foundation
import CommonCrypto

/// Encrypted `.hopperconf` file format + plaintext QR payload kinds.
enum HopperConf {
    static let fileExtension = "hopperconf"
    static let mimeType = "application/x-hopperconf"
    static let uti = "com.aengix.hopper.conf"
    static let envelopeVersion = 1
    static let payloadVersion = 1
    static let pbkdf2Iterations = 210_000
    static let saltLength = 16
    static let nonceLength = 12
    static let keyLength = 32

    enum Kind: String {
        case server
        case chain
        case key
    }

    enum Field {
        static let version = "v"
        static let fmt = "fmt"
        static let fmtValue = "hopperconf"
        static let alg = "alg"
        static let algValue = "aes-256-gcm"
        static let kdf = "kdf"
        static let kdfValue = "pbkdf2-sha256"
        static let iter = "iter"
        static let salt = "salt"
        static let nonce = "nonce"
        static let data = "data"
        static let kind = "kind"
        static let name = "name"
        static let server = "server"
        static let hops = "hops"
        static let key = "key"
        static let privateKey = "private_key"
    }

    enum Payload {
        case server(HopNodeProfile)
        case chain(name: String, hops: [HopNodeProfile])
        case key(DeploySSHKey)
    }

    enum ConfError: LocalizedError {
        case empty
        case invalidEnvelope
        case invalidPayload
        case decryptionFailed
        case encryptionFailed
        case emptyChain
        case emptyKey

        var errorDescription: String? {
            switch self {
            case .empty: return "The file is empty."
            case .invalidEnvelope: return "Not a valid .hopperconf file."
            case .invalidPayload: return "The file contents are invalid."
            case .decryptionFailed: return "Could not decrypt — check the password."
            case .encryptionFailed: return "Could not encrypt the configuration."
            case .emptyChain: return "The chain has no servers to share."
            case .emptyKey: return "The key has no private key material to share."
            }
        }
    }

    /// Empty / whitespace password uses the app display name as default.
    static func resolvedPassword(_ password: String?) -> String {
        let trimmed = password?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? HopConstants.appDisplayName : trimmed
    }

    static func suggestedFileName(for payload: Payload) -> String {
        let raw: String
        switch payload {
        case .server(let profile):
            raw = profile.displayName
        case .chain(let name, _):
            raw = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "chain" : name
        case .key(let key):
            raw = key.trimmedName.isEmpty ? "key" : key.name
        }
        let safe = raw
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let base = safe.isEmpty ? "hopper" : String(safe.prefix(40))
        return "\(base).\(fileExtension)"
    }

    // MARK: - Plaintext payload (QR + pre-encryption)

    static func exportPayloadDictionary(_ payload: Payload) throws -> [String: Any] {
        switch payload {
        case .server(let profile):
            return [
                Field.version: payloadVersion,
                Field.kind: Kind.server.rawValue,
                Field.server: HopProfileCodec.exportDictionary(profile),
            ]
        case .chain(let name, let hops):
            guard !hops.isEmpty else { throw ConfError.emptyChain }
            return [
                Field.version: payloadVersion,
                Field.kind: Kind.chain.rawValue,
                Field.name: name,
                Field.hops: hops.map { HopProfileCodec.exportDictionary($0) },
            ]
        case .key(let key):
            let pem = key.privateKey.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !pem.isEmpty else { throw ConfError.emptyKey }
            return [
                Field.version: payloadVersion,
                Field.kind: Kind.key.rawValue,
                Field.key: [
                    Field.name: key.name,
                    Field.privateKey: key.privateKey,
                ],
            ]
        }
    }

    static func exportPayloadJSON(_ payload: Payload) throws -> String {
        let dict = try exportPayloadDictionary(payload)
        let data = try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys, .prettyPrinted])
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// QR uses unencrypted payload JSON. Legacy hop-profile v2 (no `kind`) is also accepted on import.
    static func qrPayloadJSON(for payload: Payload) throws -> String {
        switch payload {
        case .server(let profile):
            return HopQRExporter.exportJSON(profile)
        case .chain, .key:
            return try exportPayloadJSON(payload)
        }
    }

    static func parsePayloadJSON(_ text: String) throws -> Payload {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ConfError.empty }
        guard let data = trimmed.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ConfError.invalidPayload
        }
        return try parsePayloadObject(object)
    }

    static func parsePayloadObject(_ json: [String: Any]) throws -> Payload {
        let kind = stringValue(in: json, keys: [Field.kind])?.lowercased()

        if kind == Kind.chain.rawValue {
            let name = stringValue(in: json, keys: [Field.name]) ?? ""
            guard let hopsRaw = json[Field.hops] as? [[String: Any]], !hopsRaw.isEmpty else {
                throw ConfError.emptyChain
            }
            let hops = try hopsRaw.map { try HopProfileCodec.parseDictionary($0) }
            return .chain(name: name, hops: hops)
        }

        if kind == Kind.key.rawValue {
            guard let keyObj = json[Field.key] as? [String: Any] else {
                throw ConfError.invalidPayload
            }
            let pem = stringValue(in: keyObj, keys: [Field.privateKey, "privateKey"]) ?? ""
            guard !pem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw ConfError.emptyKey
            }
            return .key(
                DeploySSHKey(
                    name: stringValue(in: keyObj, keys: [Field.name]) ?? "",
                    privateKey: pem
                )
            )
        }

        if kind == Kind.server.rawValue {
            guard let serverObj = json[Field.server] as? [String: Any] else {
                throw ConfError.invalidPayload
            }
            return .server(try HopProfileCodec.parseDictionary(serverObj))
        }

        // Legacy hop-profile v2 / deploy JSON (no kind).
        return .server(try HopProfileCodec.parseDictionary(json))
    }

    // MARK: - Encrypted file

    static func encryptFile(payload: Payload, password: String?) throws -> Data {
        let plaintext = try exportPayloadJSON(payload).data(using: .utf8) ?? Data()
        let resolved = resolvedPassword(password)
        let salt = randomBytes(saltLength)
        let nonceBytes = randomBytes(nonceLength)
        let keyData = try deriveKey(password: resolved, salt: salt)
        let key = SymmetricKey(data: keyData)
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let sealed: AES.GCM.SealedBox
        do {
            sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        } catch {
            throw ConfError.encryptionFailed
        }
        let cipherAndTag = sealed.ciphertext + sealed.tag
        let envelope: [String: Any] = [
            Field.version: envelopeVersion,
            Field.fmt: Field.fmtValue,
            Field.alg: Field.algValue,
            Field.kdf: Field.kdfValue,
            Field.iter: pbkdf2Iterations,
            Field.salt: salt.base64EncodedString(),
            Field.nonce: nonceBytes.base64EncodedString(),
            Field.data: cipherAndTag.base64EncodedString(),
        ]
        return try JSONSerialization.data(withJSONObject: envelope, options: [.sortedKeys, .prettyPrinted])
    }

    static func decryptFile(_ data: Data, password: String?) throws -> Payload {
        guard !data.isEmpty else { throw ConfError.empty }
        let object = try JSONSerialization.jsonObject(with: data)
        guard let json = object as? [String: Any] else { throw ConfError.invalidEnvelope }

        let fmt = stringValue(in: json, keys: [Field.fmt])
        guard fmt == Field.fmtValue else { throw ConfError.invalidEnvelope }

        guard let saltB64 = stringValue(in: json, keys: [Field.salt]),
              let nonceB64 = stringValue(in: json, keys: [Field.nonce]),
              let dataB64 = stringValue(in: json, keys: [Field.data]),
              let salt = Data(base64Encoded: saltB64),
              let nonceBytes = Data(base64Encoded: nonceB64),
              let cipherAndTag = Data(base64Encoded: dataB64),
              cipherAndTag.count > 16 else {
            throw ConfError.invalidEnvelope
        }

        let iterations = intValue(in: json, keys: [Field.iter]) ?? pbkdf2Iterations
        let defaultPassword = HopConstants.appDisplayName
        let provided = password?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        // Prefer the default password first; fall back to a user-provided password.
        if let payload = try? openCipher(
            cipherAndTag: cipherAndTag,
            salt: salt,
            nonceBytes: nonceBytes,
            iterations: iterations,
            password: defaultPassword
        ) {
            return payload
        }
        if !provided.isEmpty, provided != defaultPassword,
           let payload = try? openCipher(
            cipherAndTag: cipherAndTag,
            salt: salt,
            nonceBytes: nonceBytes,
            iterations: iterations,
            password: provided
           ) {
            return payload
        }
        throw ConfError.decryptionFailed
    }

    private static func openCipher(
        cipherAndTag: Data,
        salt: Data,
        nonceBytes: Data,
        iterations: Int,
        password: String
    ) throws -> Payload {
        let keyData = try deriveKey(password: password, salt: salt, iterations: iterations)
        let key = SymmetricKey(data: keyData)
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let ciphertext = cipherAndTag.prefix(cipherAndTag.count - 16)
        let tag = cipherAndTag.suffix(16)
        let sealed = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        let plaintext = try AES.GCM.open(sealed, using: key)
        guard let text = String(data: plaintext, encoding: .utf8) else {
            throw ConfError.invalidPayload
        }
        return try parsePayloadJSON(text)
    }

    static func isHopperConfFile(_ data: Data) -> Bool {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return false
        }
        return stringValue(in: object, keys: [Field.fmt]) == Field.fmtValue
    }

    // MARK: - Crypto helpers

    private static func deriveKey(password: String, salt: Data, iterations: Int = pbkdf2Iterations) throws -> Data {
        var derived = Data(count: keyLength)
        let passwordBytes = Array(password.utf8)
        let status: Int32 = derived.withUnsafeMutableBytes { derivedBytes in
            salt.withUnsafeBytes { saltBytes in
                passwordBytes.withUnsafeBufferPointer { passwordPtr in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordPtr.baseAddress.map { UnsafeRawPointer($0).assumingMemoryBound(to: Int8.self) },
                        passwordBytes.count,
                        saltBytes.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        derivedBytes.bindMemory(to: UInt8.self).baseAddress,
                        keyLength
                    )
                }
            }
        }
        guard status == kCCSuccess else { throw ConfError.encryptionFailed }
        return derived
    }

    private static func randomBytes(_ count: Int) -> Data {
        var data = Data(count: count)
        _ = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        return data
    }

    private static func stringValue(in dictionary: [String: Any], keys: [String]) -> String? {
        for key in keys {
            guard let value = dictionary.first(where: { $0.key.caseInsensitiveCompare(key) == .orderedSame })?.value else {
                continue
            }
            if let string = value as? String { return string }
            if let number = value as? NSNumber { return number.stringValue }
        }
        return nil
    }

    private static func intValue(in dictionary: [String: Any], keys: [String]) -> Int? {
        guard let raw = stringValue(in: dictionary, keys: keys) else { return nil }
        return Int(raw)
    }
}
