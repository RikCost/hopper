import Citadel
import Crypto
import Foundation
import NIO
import NIOSSH

enum HopSSHError: LocalizedError {
    case invalidPrivateKey

    var errorDescription: String? {
        switch self {
        case .invalidPrivateKey:
            return "Could not parse the SSH private key from the hop profile."
        }
    }
}

enum HopSSH {
    static let sharedEventLoop = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    private static let eventLoop = sharedEventLoop

    static func settings(for node: HopNodeProfile) throws -> SSHClientSettings {
        let privateKey: Curve25519.Signing.PrivateKey
        do {
            privateKey = try Curve25519.Signing.PrivateKey(sshEd25519: node.privateKey)
        } catch {
            throw HopSSHError.invalidPrivateKey
        }
        let authMethod = SSHAuthenticationMethod.ed25519(username: node.trimmedUser, privateKey: privateKey)

        var settings = SSHClientSettings(
            host: node.trimmedHost,
            port: node.port,
            authenticationMethod: { authMethod },
            hostKeyValidator: .acceptAnything()
        )
        settings.group = eventLoop
        settings.connectTimeout = .seconds(60)
        settings.loginTimeout = .seconds(60)
        return settings
    }

    static func connect(_ node: HopNodeProfile) async throws -> SSHClient {
        try await SSHClient.connect(to: settings(for: node))
    }

    static func runCommand(on client: SSHClient, _ command: String) async throws -> String {
        try await runCommand(on: client, command, onLine: nil)
    }

    static func runCommand(
        on client: SSHClient,
        _ command: String,
        onLine: (@Sendable (String) -> Void)?
    ) async throws -> String {
        if let onLine {
            return try await runCommandStreaming(on: client, command, onLine: onLine)
        }
        let buffer = try await client.executeCommand(command, mergeStreams: true)
        return String(buffer: buffer)
    }

    private static func runCommandStreaming(
        on client: SSHClient,
        _ command: String,
        onLine: @escaping @Sendable (String) -> Void
    ) async throws -> String {
        var collected = ""
        var pending = ""
        let stream = try await client.executeCommandStream(command)

        func flushPending(asFinal: Bool = false) {
            while let newline = pending.firstIndex(of: "\n") {
                let line = String(pending[..<newline])
                pending = String(pending[pending.index(after: newline)...])
                onLine(line)
            }
            if asFinal, !pending.isEmpty {
                onLine(pending)
                pending = ""
            }
        }

        for try await output in stream {
            let chunk: String
            switch output {
            case .stdout(let buffer), .stderr(let buffer):
                chunk = String(buffer: buffer)
            }
            collected += chunk
            pending += chunk
            flushPending()
        }
        flushPending(asFinal: true)
        return collected
    }
}

enum ChainSSHForwardError: LocalizedError {
    case emptyChain
    case connectFailed(hop: String, detail: String)
    case forwardFailed(hop: String, via: String, detail: String)

    var errorDescription: String? {
        switch self {
        case .emptyChain:
            return "Add at least one hop (entry → exit order)."
        case .connectFailed(let hop, let detail):
            return "Could not connect to \(hop): \(detail)"
        case .forwardFailed(let hop, let via, let detail):
            return "Could not forward from \(via) to \(hop): \(detail)"
        }
    }
}

/// SSH sessions for one chain. Index 0 is a direct login to the entry hop.
/// Each later session is an SSH login carried by a direct-tcpip channel on the previous hop.
final class ChainSSHForward {
    let hops: [HopNodeProfile]
    private var clients: [SSHClient]
    private var closed = false

    private init(hops: [HopNodeProfile], clients: [SSHClient]) {
        self.hops = hops
        self.clients = clients
    }

    static func open(
        _ hops: [HopNodeProfile],
        onHop: ((Int, HopNodeProfile) -> Void)? = nil
    ) async throws -> ChainSSHForward {
        guard let entry = hops.first else { throw ChainSSHForwardError.emptyChain }

        var clients: [SSHClient] = []
        do {
            onHop?(0, entry)
            TunnelLog.info("Chain SSH connect \(entry.trimmedUser)@\(entry.trimmedHost):\(entry.port)")
            let first: SSHClient
            do {
                first = try await HopSSH.connect(entry)
            } catch {
                throw ChainSSHForwardError.connectFailed(
                    hop: entry.displayName,
                    detail: HopErrorDetails.describe(error)
                )
            }
            clients.append(first)

            for index in hops.indices.dropFirst() {
                let hop = hops[index]
                let via = hops[index - 1]
                onHop?(index, hop)
                TunnelLog.info("Chain SSH forward \(via.displayName) -> \(hop.trimmedUser)@\(hop.trimmedHost):\(hop.port)")
                do {
                    let settings = try HopSSH.settings(for: hop)
                    let next = try await clients[index - 1].jump(to: settings)
                    clients.append(next)
                } catch {
                    throw ChainSSHForwardError.forwardFailed(
                        hop: hop.displayName,
                        via: via.displayName,
                        detail: HopErrorDetails.describe(error)
                    )
                }
            }
        } catch {
            await Self.close(clients)
            throw error
        }

        return ChainSSHForward(hops: hops, clients: clients)
    }

    static func withChain<T>(
        _ hops: [HopNodeProfile],
        onHop: ((Int, HopNodeProfile) -> Void)? = nil,
        _ body: (ChainSSHForward) async throws -> T
    ) async throws -> T {
        let forward = try await open(hops, onHop: onHop)
        do {
            let value = try await body(forward)
            await forward.close()
            return value
        } catch {
            await forward.close()
            throw error
        }
    }

    func client(at index: Int) -> SSHClient {
        clients[index]
    }

    /// Closes SSH sessions from the exit back to the entry, which drops each direct-tcpip forward.
    func close() async {
        guard !closed else { return }
        closed = true
        let snapshot = clients
        clients.removeAll()
        await Self.close(snapshot)
    }

    private static func close(_ clients: [SSHClient]) async {
        guard !clients.isEmpty else { return }
        TunnelLog.info("Closing \(clients.count) chain SSH session(s), innermost first")
        for client in clients.reversed() {
            do {
                try await client.close()
            } catch {
                TunnelLog.info("Chain SSH close: \(HopErrorDetails.describe(error))")
            }
        }
    }
}
