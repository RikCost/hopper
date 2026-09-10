#!/usr/bin/env swift
/**
 * Decrypts Python golden .hopperconf vectors with the same crypto stack as the iOS app
 * (CommonCrypto PBKDF2-HMAC-SHA256 + CryptoKit AES-GCM).
 *
 * Usage: swift tests/hopperconf/test_swift_interop.swift
 */
import Foundation
import CommonCrypto
import CryptoKit

let defaultPassword = "ɹǝddoH"
let customPassword = "test-password-🔐"

struct Envelope: Decodable {
    let v: Int
    let fmt: String
    let iter: Int?
    let salt: String
    let nonce: String
    let data: String
}

enum Fail: Error { case message(String) }

func deriveKey(password: String, salt: Data, iterations: Int) throws -> Data {
    var derived = Data(count: 32)
    let pw = Array(password.utf8)
    let status: Int32 = derived.withUnsafeMutableBytes { d in
        salt.withUnsafeBytes { s in
            pw.withUnsafeBufferPointer { p in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    UnsafeRawPointer(p.baseAddress!).assumingMemoryBound(to: Int8.self),
                    pw.count,
                    s.bindMemory(to: UInt8.self).baseAddress,
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    d.bindMemory(to: UInt8.self).baseAddress,
                    32
                )
            }
        }
    }
    guard status == kCCSuccess else { throw Fail.message("PBKDF2 failed") }
    return derived
}

/// Matches app behavior: try default password first, then user-provided.
func decryptFile(_ data: Data, password: String?) throws -> [String: Any] {
    let envelope = try JSONDecoder().decode(Envelope.self, from: data)
    guard envelope.fmt == "hopperconf" else { throw Fail.message("bad fmt") }
    guard let salt = Data(base64Encoded: envelope.salt),
          let nonceBytes = Data(base64Encoded: envelope.nonce),
          let cipherAndTag = Data(base64Encoded: envelope.data),
          cipherAndTag.count > 16 else {
        throw Fail.message("bad envelope fields")
    }
    let iterations = envelope.iter ?? 210_000
    var candidates = [defaultPassword]
    let provided = password?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if !provided.isEmpty, provided != defaultPassword {
        candidates.append(provided)
    }
    for candidate in candidates {
        guard let keyData = try? deriveKey(password: candidate, salt: salt, iterations: iterations),
              let nonce = try? AES.GCM.Nonce(data: nonceBytes),
              let box = try? AES.GCM.SealedBox(
                nonce: nonce,
                ciphertext: cipherAndTag.prefix(cipherAndTag.count - 16),
                tag: cipherAndTag.suffix(16)
              ),
              let plaintext = try? AES.GCM.open(box, using: SymmetricKey(data: keyData)),
              let obj = try? JSONSerialization.jsonObject(with: plaintext) as? [String: Any] else {
            continue
        }
        return obj
    }
    throw Fail.message("Could not decrypt — check the password.")
}

func assertEqual<T: Equatable>(_ a: T, _ b: T, _ label: String) throws {
    guard a == b else { throw Fail.message("\(label): expected \(b), got \(a)") }
}

func run() throws {
    let scriptDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    let vectors = scriptDir.appendingPathComponent("vectors")

    let cases: [(String, String?, String)] = [
        ("server_default.hopperconf", nil, "server"),
        ("server_custom.hopperconf", customPassword, "server"),
        ("chain_default.hopperconf", nil, "chain"),
        ("chain_custom.hopperconf", customPassword, "chain"),
        ("key_default.hopperconf", nil, "key"),
        ("key_custom.hopperconf", customPassword, "key"),
    ]

    for (name, password, kind) in cases {
        let url = vectors.appendingPathComponent(name)
        let data = try Data(contentsOf: url)
        let payload = try decryptFile(data, password: password)
        try assertEqual(payload["kind"] as? String, kind, "\(name) kind")
        if kind == "server" {
            let server = payload["server"] as? [String: Any]
            try assertEqual(server?["host"] as? String, "203.0.113.10", "\(name) host")
        } else if kind == "chain" {
            let hops = payload["hops"] as? [[String: Any]]
            try assertEqual(hops?.count, 2, "\(name) hop count")
            try assertEqual(payload["name"] as? String, "Interop Chain", "\(name) name")
        } else {
            let key = payload["key"] as? [String: Any]
            try assertEqual(key?["name"] as? String, "interop-key", "\(name) key name")
        }
        print("ok  \(name)")
    }

    // Custom-encrypted: wrong password fails after default also fails
    let custom = try Data(contentsOf: vectors.appendingPathComponent("server_custom.hopperconf"))
    do {
        _ = try decryptFile(custom, password: "wrong")
        throw Fail.message("expected wrong-password failure")
    } catch Fail.message(let m) where m == "expected wrong-password failure" {
        throw Fail.message(m)
    } catch {
        print("ok  wrong-password rejected")
    }

    // Default-encrypted opens even when a wrong custom password is supplied
    let def = try Data(contentsOf: vectors.appendingPathComponent("server_default.hopperconf"))
    let opened = try decryptFile(def, password: "wrong-custom")
    try assertEqual(opened["kind"] as? String, "server", "default-first kind")
    print("ok  default-first fallback")

    print("All Swift interop tests passed.")
}

do {
    try run()
} catch {
    fputs("FAIL: \(error)\n", stderr)
    exit(1)
}
