import Citadel
import Foundation

struct HopReadyReport: Codable, Equatable {
    let ready: Bool
    let mode: String
    let addr: String
    let index: Int
    let overlay: String
    let port: Int
    let listenPort: Int?
    let chainID: String?
    let nat: Bool?

    enum CodingKeys: String, CodingKey {
        case ready, mode, addr, index, overlay, port, nat
        case listenPort = "listen_port"
        case chainID = "chain_id"
    }

    static func parse(from output: String) throws -> HopReadyReport {
        let lines = output
            .split(whereSeparator: \.isNewline)
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        for line in lines.reversed() where line.hasPrefix("{") {
            guard let data = line.data(using: .utf8) else { continue }
            if let report = try? JSONDecoder().decode(HopReadyReport.self, from: data), report.ready {
                return report
            }
        }
        throw ChainProvisionerError.invalidReadyJSON(output)
    }
}

struct ChainStatusReport: Codable, Equatable {
    let host: String?
    let serverVersion: String?
    let minAppVersion: String?
    let checkedAt: String?
    let chains: [ChainStatusEntry]

    enum CodingKeys: String, CodingKey {
        case host, chains
        case serverVersion = "server_version"
        case minAppVersion = "min_app_version"
        case checkedAt = "checked_at"
    }
}

struct ChainStatusEntry: Codable, Equatable, Identifiable {
    var id: String { chainID ?? hopAddr ?? UUID().uuidString }
    let chainID: String?
    let overlay: String?
    let listenPort: Int?
    let role: String?
    let hopAddr: String?
    let startedAt: String?
    let running: Bool?
    let lastActivity: String?
    let sessions: [ChainSessionStatus]

    enum CodingKeys: String, CodingKey {
        case overlay, role, running, sessions
        case chainID = "chain_id"
        case listenPort = "listen_port"
        case hopAddr = "hop_addr"
        case startedAt = "started_at"
        case lastActivity = "last_activity"
    }
}

struct ChainSessionStatus: Codable, Equatable, Identifiable {
    var id: String { clientAddr ?? UUID().uuidString }
    let clientAddr: String?
    let deviceID: String?
    let connectedAt: String?
    let lastSeen: String?
    let remote: String?

    enum CodingKeys: String, CodingKey {
        case remote
        case clientAddr = "client_addr"
        case deviceID = "device_id"
        case connectedAt = "connected_at"
        case lastSeen = "last_seen"
    }
}

enum ChainStatusService {
    static func fetch(on client: SSHClient, hop: HopNodeProfile, chainID: UUID) async throws -> ChainStatusReport {
        let install = hop.resolvedInstallDir
        let cmd = "cd \(ShellQuote.bashRemotePath(install)) && ./hopperctl status --chain-id \(shellQuote(chainID.uuidString))"
        let output = try await HopSSH.runCommand(on: client, cmd)
        guard let data = output.data(using: .utf8) else {
            throw ChainStatusServiceError.invalidResponse
        }
        return try JSONDecoder().decode(ChainStatusReport.self, from: data)
    }

    private static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}

enum ChainStatusServiceError: LocalizedError {
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .invalidResponse: return "Server returned invalid status JSON."
        }
    }
}

private extension HopNodeProfile {
    var resolvedInstallDir: String {
        let trimmed = installDir.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? HopConstants.defaultInstallDir : trimmed
    }
}
