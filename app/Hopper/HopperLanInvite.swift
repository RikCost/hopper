import Darwin
import Foundation

/// In-person LAN receive session advertised as a QR / camera deep link.
/// `hopperconf://recv?ip=&port=&fp=&v=1` — `fp` is SHA-256 of the TLS server cert DER.
struct HopperLanInvite: Identifiable, Hashable {
    static let scheme = "hopperconf"
    static let host = "recv"
    static let currentVersion = 1
    static let maxFrameBytes = 2_000_000

    var id: String { url.absoluteString }
    let ip: String
    let port: UInt16
    let fingerprint: String
    let url: URL

    static func make(ip: String, port: UInt16, fingerprint: String) -> HopperLanInvite? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.queryItems = [
            URLQueryItem(name: "ip", value: ip),
            URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "fp", value: fingerprint.lowercased()),
            URLQueryItem(name: "v", value: String(currentVersion)),
        ]
        guard let url = components.url else { return nil }
        return HopperLanInvite(ip: ip, port: port, fingerprint: fingerprint.lowercased(), url: url)
    }

    static func parse(_ text: String) -> HopperLanInvite? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed) else { return nil }
        return parse(url)
    }

    static func parse(_ url: URL) -> HopperLanInvite? {
        guard url.scheme?.lowercased() == scheme else { return nil }
        guard (url.host ?? "").lowercased() == host else { return nil }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? {
            items.first(where: { $0.name == name })?.value?
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard let ip = value("ip"), isIPv4(ip) else { return nil }
        guard let portText = value("port"), let port = UInt16(portText), port > 0 else { return nil }
        guard let fp = value("fp")?.lowercased(), isFingerprint(fp) else { return nil }
        let version = Int(value("v") ?? "") ?? 0
        guard version == currentVersion else { return nil }
        return HopperLanInvite(ip: ip, port: port, fingerprint: fp, url: url)
    }

    private static func isIPv4(_ ip: String) -> Bool {
        let parts = ip.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        return parts.allSatisfy { part in
            guard let n = Int(part), (0...255).contains(n), String(n) == part else { return false }
            return true
        }
    }

    private static func isFingerprint(_ fp: String) -> Bool {
        fp.count == 64 && fp.allSatisfy { $0.isHexDigit }
    }
}

enum HopperLanWire {
    static let type = "t"
    static let payload = "p"
    static let summary = "s"
    static let error = "e"
    static let version = "v"

    static let hello = "hello"
    static let ready = "ready"
    static let item = "item"
    static let ok = "ok"
    static let err = "err"
    static let bye = "bye"

    static func encode(_ object: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: object, options: [])
    }

    static func decode(_ data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw HopperLanError.badMessage
        }
        return object
    }

    static func frame(_ body: Data) -> Data {
        var length = UInt32(body.count).bigEndian
        var data = Data(bytes: &length, count: 4)
        data.append(body)
        return data
    }
}

enum HopperLanError: LocalizedError {
    case noLanAddress
    case listenFailed
    case identityFailed
    case connectFailed
    case tlsMismatch
    case badMessage
    case disconnected
    case timeout

    var errorDescription: String? {
        switch self {
        case .noLanAddress: return "Connect this device to Wi-Fi (or a hotspot) so the other phone can reach it."
        case .listenFailed: return "Could not start the local receive server."
        case .identityFailed: return "Could not create a TLS identity for this session."
        case .connectFailed: return "Could not connect to the other device."
        case .tlsMismatch: return "TLS certificate did not match the QR code."
        case .badMessage: return "The other device sent an invalid message."
        case .disconnected: return "The other device disconnected."
        case .timeout: return "Timed out waiting for the other device."
        }
    }
}

enum HopperLanAddress {
    /// Wi-Fi / Ethernet IPv4 for the QR. Bind is still 0.0.0.0.
    static func advertisedIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(first) }

        var best: (score: Int, ip: String)?
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = ptr?.pointee {
            defer { ptr = ifa.ifa_next }
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let flags = Int32(ifa.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0 else { continue }
            let name = String(cString: ifa.ifa_name)
            if name.hasPrefix("utun") || name.hasPrefix("ipsec") || name.hasPrefix("ppp")
                || name.hasPrefix("awdl") || name.hasPrefix("llw") {
                continue
            }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let len = socklen_t(MemoryLayout<sockaddr_in>.size)
            guard getnameinfo(addr, len, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else {
                continue
            }
            let ip = String(cString: host)
            if ip.hasPrefix("127.") { continue }
            var score = 0
            if name.hasPrefix("en") { score += 50 }
            if ip.hasPrefix("192.168.") || ip.hasPrefix("10.") { score += 30 }
            if let second = Int(ip.split(separator: ".").dropFirst().first ?? "") {
                if ip.hasPrefix("172.") && (16...31).contains(second) { score += 25 }
            }
            if ip.hasPrefix("169.254.") { score -= 50 }
            if best == nil || score > best!.score {
                best = (score, ip)
            }
        }
        return best?.ip
    }
}
