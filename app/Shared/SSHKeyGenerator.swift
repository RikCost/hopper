import Citadel
import Crypto
import Foundation

enum SSHKeyGenerator {
    struct GeneratedKey {
        let privateKeyPEM: String
        let publicKeyLine: String
    }

    static func generateEd25519(comment: String) throws -> GeneratedKey {
        let privateKey = Curve25519.Signing.PrivateKey()
        let pem = privateKey.makeSSHRepresentation(comment: comment)
        let publicLine = openSSHPublicKeyLine(publicKey: privateKey.publicKey, comment: comment)
        return GeneratedKey(privateKeyPEM: pem, publicKeyLine: publicLine)
    }

    static func publicKeyLine(privateKeyPEM: String, comment: String = "") throws -> String {
        let privateKey = try Curve25519.Signing.PrivateKey(sshEd25519: privateKeyPEM)
        return openSSHPublicKeyLine(publicKey: privateKey.publicKey, comment: comment)
    }

    /// Type + base64 blob only (comment stripped). Nil if the PEM is not a valid ED25519 key.
    static func normalizedPublicKeyLine(privateKeyPEM: String) -> String? {
        guard let line = try? publicKeyLine(privateKeyPEM: privateKeyPEM) else { return nil }
        return stripPublicKeyComment(line)
    }

    static func isValidPrivateKey(_ privateKeyPEM: String) -> Bool {
        normalizedPublicKeyLine(privateKeyPEM: privateKeyPEM) != nil
    }

    static func stripPublicKeyComment(_ line: String) -> String {
        let parts = line.split(omittingEmptySubsequences: true, whereSeparator: { $0.isWhitespace })
        guard parts.count >= 2 else {
            return line.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return "\(parts[0]) \(parts[1])"
    }

    private static func openSSHPublicKeyLine(publicKey: Curve25519.Signing.PublicKey, comment: String) -> String {
        var blob = Data()
        appendSSHString("ssh-ed25519", to: &blob)
        appendSSHBytes(Array(publicKey.rawRepresentation), to: &blob)
        let encoded = blob.base64EncodedString()
        if comment.isEmpty {
            return "ssh-ed25519 \(encoded)"
        }
        return "ssh-ed25519 \(encoded) \(comment)"
    }

    private static func appendSSHString(_ value: String, to data: inout Data) {
        appendSSHBytes(Array(value.utf8), to: &data)
    }

    private static func appendSSHBytes(_ bytes: [UInt8], to data: inout Data) {
        var length = UInt32(bytes.count).bigEndian
        withUnsafeBytes(of: &length) { data.append(contentsOf: $0) }
        data.append(contentsOf: bytes)
    }
}
