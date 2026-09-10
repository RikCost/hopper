import Foundation

struct VersionManifest: Codable, Equatable {
    let version: String
    let minAppVersion: String
    let minServerVersion: String
    let protocolVersion: Int

    enum CodingKeys: String, CodingKey {
        case version
        case minAppVersion = "min_app_version"
        case minServerVersion = "min_server_version"
        case protocolVersion = "protocol_version"
    }
}

struct ServerVersionInfo: Codable, Equatable {
    let version: String?
    let minAppVersion: String?

    enum CodingKeys: String, CodingKey {
        case version
        case minAppVersion = "min_app_version"
    }
}

enum HopVersion {
    static let minServerVersion = "2.0.0"
    static let protocolVersion = 2

    static var appVersion: String {
        HopConstants.appVersion
    }

    static var manifest: VersionManifest {
        VersionManifest(
            version: appVersion,
            minAppVersion: appVersion,
            minServerVersion: minServerVersion,
            protocolVersion: protocolVersion
        )
    }
}

enum SemVer {
    static func compare(_ lhs: String, _ rhs: String) -> ComparisonResult {
        let a = parts(lhs)
        let b = parts(rhs)
        for i in 0..<max(a.count, b.count) {
            let av = i < a.count ? a[i] : 0
            let bv = i < b.count ? b[i] : 0
            if av < bv { return .orderedAscending }
            if av > bv { return .orderedDescending }
        }
        return .orderedSame
    }

    private static func parts(_ value: String) -> [Int] {
        value.split(separator: ".").prefix(3).compactMap { Int($0) }
    }
}

enum VersionCheckOutcome: Equatable {
    case compatible
    case appTooOld(required: String)
    case serverTooOld(hops: [String])
}

enum VersionChecker {
    static func check(
        appVersion: String,
        manifest: VersionManifest,
        serverInfos: [(hop: String, info: ServerVersionInfo)]
    ) -> VersionCheckOutcome {
        for item in serverInfos {
            if let minApp = item.info.minAppVersion,
               SemVer.compare(appVersion, minApp) == .orderedAscending {
                return .appTooOld(required: minApp)
            }
        }

        let outdated = serverInfos.compactMap { item -> String? in
            guard let serverVersion = item.info.version else { return nil }
            if SemVer.compare(serverVersion, manifest.minServerVersion) == .orderedAscending {
                return item.hop
            }
            return nil
        }

        if !outdated.isEmpty {
            return .serverTooOld(hops: outdated)
        }
        return .compatible
    }
}

enum VersionService {
    static func fetchServerVersion(on hop: HopNodeProfile) async throws -> ServerVersionInfo {
        let install = hop.resolvedInstallDir
        let cmd = "cd \(shellQuote(install)) && ./hopperctl configure --version-json"
        let output = try await HopSSH.withSession(on: hop) { client in
            try await HopSSH.runCommand(on: client, cmd)
        }
        guard let jsonLine = extractJSONLine(from: output),
              let data = jsonLine.data(using: .utf8)
        else {
            throw VersionServiceError.invalidResponse
        }
        return try JSONDecoder().decode(ServerVersionInfo.self, from: data)
    }

    static func updateServer(on hop: HopNodeProfile, to version: String) async throws {
        let install = hop.resolvedInstallDir
        let cmd = "cd \(shellQuote(install)) && ./hopperctl update --update --to \(shellQuote(version)) --json-only"
        _ = try await HopSSH.withSession(on: hop) { client in
            try await HopSSH.runCommand(on: client, cmd)
        }
    }

    private static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    private static func extractJSONLine(from output: String) -> String? {
        for line in output.split(separator: "\n", omittingEmptySubsequences: false).reversed() {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("{") {
                return trimmed
            }
        }
        return nil
    }
}

enum VersionServiceError: LocalizedError {
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .invalidResponse: return "Server returned an invalid version response."
        }
    }
}

private extension HopNodeProfile {
    var resolvedInstallDir: String {
        let trimmed = installDir.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? HopConstants.defaultInstallDir : trimmed
    }
}
