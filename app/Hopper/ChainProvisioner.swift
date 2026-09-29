import Citadel
import Foundation

enum ChainProvisionerError: LocalizedError {
    case emptyChain
    case invalidReadyJSON(String)
    case missingPubkey(String)
    case provisionFailed(String)
    case missingChainID

    var errorDescription: String? {
        switch self {
        case .emptyChain:
            return "Add at least one hop (entry → exit order)."
        case .invalidReadyJSON(let output):
            let tail = output.suffix(200)
            return "Server did not return ready JSON. Output: \(tail)"
        case .missingPubkey(let hop):
            return "Could not read SSH public key on \(hop)."
        case .provisionFailed(let detail):
            return detail
        case .missingChainID:
            return "Chain ID is missing."
        }
    }
}

enum ChainProvisioner {
    typealias ProgressHandler = (_ index: Int, _ total: Int, _ message: String) -> Void

    /// Opens SSH only to the entry hop, then reaches each later hop through a direct-tcpip forward.
    /// Each downstream hop is configured to reverse-dial its upstream. Forwards are closed when this returns.
    static func provision(
        chainID: UUID,
        chain: [HopNodeProfile],
        restartHopperd: Bool = false,
        onProgress: ProgressHandler? = nil
    ) async throws -> [HopReadyReport] {
        guard !chain.isEmpty else { throw ChainProvisionerError.emptyChain }

        let total = chain.count
        let overlay = ChainTopology.overlayCIDR(chainID: chainID)
        let skipIfRunning = !restartHopperd
        let listenPort = ChainTopology.listenPort(chainID: chainID)

        return try await ChainSSHForward.withChain(chain, onHop: { index, hop in
            if index == 0 {
                onProgress?(index, total, "Connecting to entry \(hop.displayName)…")
            } else {
                let via = chain[index - 1].displayName
                onProgress?(index, total, "Forwarding through \(via) to \(hop.displayName)…")
            }
        }) { forward in
            if restartHopperd {
                onProgress?(total - 1, total, "Stopping previous hopperd on all hops…")
                for index in chain.indices {
                    await stopNode(on: forward.client(at: index), hop: chain[index], chainID: chainID)
                }
            }

            var reports: [HopReadyReport] = []

            // Exit → entry so reverse dialers can retry until upstream is listening.
            for i in stride(from: total - 1, through: 0, by: -1) {
                let hop = chain[i]
                let label = hop.displayName
                onProgress?(i, total, "Configuring \(label)…")
                TunnelLog.info("Chain provision hop[\(i)] \(label)")

                // Trust both directions so either hop can dial (sticky dialer).
                if i < total - 1 {
                    let downstream = chain[i + 1]
                    let downPub = try await fetchPubkey(on: forward.client(at: i + 1), hop: downstream)
                    try await trustPubkey(
                        downPub,
                        on: forward.client(at: i),
                        hop: hop,
                        chainID: chainID
                    )
                    let upPub = try await fetchPubkey(on: forward.client(at: i), hop: hop)
                    try await trustPubkey(
                        upPub,
                        on: forward.client(at: i + 1),
                        hop: downstream,
                        chainID: chainID
                    )
                }

                let report = try await startNode(
                    on: forward.client(at: i),
                    chainID: chainID,
                    hop: hop,
                    index: i,
                    isExit: i == total - 1,
                    upstream: i > 0 ? chain[i - 1] : nil,
                    downstream: i < total - 1 ? chain[i + 1] : nil,
                    tunnelPort: listenPort,
                    overlay: overlay,
                    skipIfRunning: skipIfRunning
                )
                reports.append(report)
                onProgress?(i, total, "\(label) ready (\(report.mode) \(report.addr))")
            }

            return reports.reversed()
        }
    }

    private static func stopNode(on client: SSHClient, hop: HopNodeProfile, chainID: UUID) async {
        let install = hop.resolvedInstallDir
        let cmd = "cd \(ShellQuote.bashRemotePath(install)) && ./hopperctl start --chain-id \(shellQuote(chainID.uuidString)) --stop-only"
        do {
            _ = try await HopSSH.runCommand(on: client, cmd)
            TunnelLog.info("Stopped previous hopperd on \(hop.displayName)")
        } catch {
            TunnelLog.info("Stop hopperd on \(hop.displayName) (non-fatal): \(HopErrorDetails.describe(error))")
        }
    }

    private static func fetchPubkey(on client: SSHClient, hop: HopNodeProfile) async throws -> String {
        let output = try await HopSSH.runCommand(on: client, "cat ~/.hopper/id_ed25519.pub")
        let key = output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard key.hasPrefix("ssh-") else {
            throw ChainProvisionerError.missingPubkey(hop.displayName)
        }
        return key
    }

    private static func trustPubkey(
        _ pubkey: String,
        on client: SSHClient,
        hop: HopNodeProfile,
        chainID: UUID
    ) async throws {
        let install = hop.resolvedInstallDir
        let cmd = "cd \(ShellQuote.bashRemotePath(install)) && ./hopperctl start --chain-id \(shellQuote(chainID.uuidString)) --trust-pubkey \(shellQuote(pubkey)) --trust-only"
        _ = try await HopSSH.runCommand(on: client, cmd)
        TunnelLog.info("Trusted reverse-dial key on \(hop.displayName)")
    }

    private static func startNode(
        on client: SSHClient,
        chainID: UUID,
        hop: HopNodeProfile,
        index: Int,
        isExit: Bool,
        upstream: HopNodeProfile?,
        downstream: HopNodeProfile?,
        tunnelPort: Int,
        overlay: String,
        skipIfRunning: Bool
    ) async throws -> HopReadyReport {
        let install = hop.resolvedInstallDir
        let addr = ChainTopology.overlayAddr(chainID: chainID, index: index)
        var args = [
            "--chain-id", chainID.uuidString,
            "--role", isExit ? "exit" : "relay",
            "--addr", addr,
            "--index", String(index),
            "--overlay", overlay,
        ]
        if skipIfRunning {
            args += ["--if-running", "skip"]
        }
        if let upstream {
            let user = upstream.trimmedUser.isEmpty ? "root" : upstream.trimmedUser
            args += [
                "--upstream-host", upstream.trimmedHost,
                "--upstream-port", String(upstream.port),
                "--upstream-user", user,
                "--upstream-tunnel-port", String(tunnelPort),
            ]
        }
        if let downstream {
            let user = downstream.trimmedUser.isEmpty ? "root" : downstream.trimmedUser
            args += [
                "--downstream-host", downstream.trimmedHost,
                "--downstream-port", String(downstream.port),
                "--downstream-user", user,
                "--downstream-tunnel-port", String(tunnelPort),
            ]
        }

        let argString = args.map(shellQuote).joined(separator: " ")
        let cmd = "cd \(ShellQuote.bashRemotePath(install)) && ./hopperctl start \(argString)"

        let output = try await HopSSH.runCommand(on: client, cmd)
        return try HopReadyReport.parse(from: output)
    }

    private static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}

private extension HopNodeProfile {
    var resolvedInstallDir: String {
        let trimmed = installDir.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? HopConstants.defaultInstallDir : trimmed
    }
}
