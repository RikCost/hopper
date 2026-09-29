import Citadel
import Crypto
import Foundation
import NIOSSH

enum DeploySSH {
    static func runInstall(
        host: String,
        port: Int,
        user: String,
        password: String?,
        privateKeyPEM: String,
        publicKeyLine: String,
        installDir: String,
        onLog: (@Sendable (String) -> Void)? = nil
    ) async throws -> String {
        let log = onLog ?? { _ in }
        let client = try await connectAndAuthorize(
            host: host,
            port: port,
            user: user,
            password: password,
            privateKeyPEM: privateKeyPEM,
            publicKeyLine: publicKeyLine,
            onLog: log
        )
        defer { Task { try? await client.close() } }

        log("Running remote install…")
        let installCmd = installCommand(
            host: host,
            port: port,
            installDir: installDir
        )
        return try await HopSSH.runCommand(on: client, installCmd, onLine: log)
    }

    /// Connects (password or key), ensures the public key is in authorized_keys, then disconnects.
    static func authorizeKey(
        host: String,
        port: Int,
        user: String,
        password: String?,
        privateKeyPEM: String,
        publicKeyLine: String,
        onLog: (@Sendable (String) -> Void)? = nil
    ) async throws {
        let log = onLog ?? { _ in }
        let client = try await connectAndAuthorize(
            host: host,
            port: port,
            user: user,
            password: password,
            privateKeyPEM: privateKeyPEM,
            publicKeyLine: publicKeyLine,
            onLog: log
        )
        try? await client.close()
    }

    private static func connectAndAuthorize(
        host: String,
        port: Int,
        user: String,
        password: String?,
        privateKeyPEM: String,
        publicKeyLine: String,
        onLog: @Sendable (String) -> Void
    ) async throws -> SSHClient {
        onLog("Connecting to \(user)@\(host):\(port)…")

        let client: SSHClient
        if let password {
            client = try await connect(host: host, port: port, user: user, password: password)
        } else {
            client = try await connect(host: host, port: port, user: user, privateKeyPEM: privateKeyPEM)
        }

        onLog("Ensuring deploy key is in authorized_keys…")
        let authCmd = authorizedKeysCommand(publicKeyLine: publicKeyLine, host: host)
        do {
            _ = try await HopSSH.runCommand(on: client, authCmd)
        } catch {
            try? await client.close()
            throw error
        }
        onLog("Deploy key authorized.")
        return client
    }

    private static func connect(host: String, port: Int, user: String, password: String) async throws -> SSHClient {
        var settings = SSHClientSettings(
            host: host,
            port: port,
            authenticationMethod: { .passwordBased(username: user, password: password) },
            hostKeyValidator: .acceptAnything()
        )
        settings.group = HopSSH.sharedEventLoop
        settings.connectTimeout = .seconds(60)
        settings.loginTimeout = .seconds(60)
        return try await SSHClient.connect(to: settings)
    }

    private static func connect(host: String, port: Int, user: String, privateKeyPEM: String) async throws -> SSHClient {
        let privateKey = try Curve25519.Signing.PrivateKey(sshEd25519: privateKeyPEM)
        var settings = SSHClientSettings(
            host: host,
            port: port,
            authenticationMethod: { .ed25519(username: user, privateKey: privateKey) },
            hostKeyValidator: .acceptAnything()
        )
        settings.group = HopSSH.sharedEventLoop
        settings.connectTimeout = .seconds(60)
        settings.loginTimeout = .seconds(60)
        return try await SSHClient.connect(to: settings)
    }

    private static func authorizedKeysCommand(publicKeyLine: String, host: String) -> String {
        let pub = ShellQuote.bashSingle(publicKeyLine)
        return """
        mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && grep -qF \(pub) ~/.ssh/authorized_keys 2>/dev/null || echo \(pub) deploy-\(ShellQuote.bashSingle(host)) >>~/.ssh/authorized_keys
        """
    }

    private static func installCommand(host: String, port: Int, installDir: String) -> String {
        let url = ShellQuote.bashSingle(HopConstants.serverInstallURL)
        let ref = ShellQuote.bashSingle(HopConstants.serverInstallRef)
        let dir = ShellQuote.bashSingle(installDir)
        let hostQ = ShellQuote.bashSingle(host)
        let portQ = ShellQuote.bashSingle(String(port))
        return "curl -fsSL \(url) | HOPPER_REF=\(ref) HOPPER_INSTALL_DIR=\(dir) bash -s -- --configure --host \(hostQ) --port \(portQ)"
    }
}
