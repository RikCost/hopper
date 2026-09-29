package com.aengix.hopper.ssh

import com.aengix.hopper.model.HopConstants
import com.aengix.hopper.util.IPv4Only
import com.aengix.hopper.util.ShellQuote
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

object DeploySSH {
    fun runInstall(
        host: String,
        port: Int,
        user: String,
        password: String?,
        privateKeyPem: String,
        publicKeyLine: String,
        installDir: String,
        onLog: ((String) -> Unit)? = null,
    ): String {
        val log = onLog ?: {}
        val sshClient = connectAndAuthorize(
            host = host,
            port = port,
            user = user,
            password = password,
            privateKeyPem = privateKeyPem,
            publicKeyLine = publicKeyLine,
            onLog = log,
        )
        try {
            log("Running remote install…")
            return HopSSH.runCommand(sshClient, installCommand(host, port, installDir), onLine = log)
        } finally {
            runCatching { sshClient.disconnect() }
        }
    }

    /** Connects (password or key) and ensures the public key is in authorized_keys. */
    fun authorizeKey(
        host: String,
        port: Int,
        user: String,
        password: String?,
        privateKeyPem: String,
        publicKeyLine: String,
        onLog: ((String) -> Unit)? = null,
    ) {
        val sshClient = connectAndAuthorize(
            host = host,
            port = port,
            user = user,
            password = password,
            privateKeyPem = privateKeyPem,
            publicKeyLine = publicKeyLine,
            onLog = onLog ?: {},
        )
        runCatching { sshClient.disconnect() }
    }

    private fun connectAndAuthorize(
        host: String,
        port: Int,
        user: String,
        password: String?,
        privateKeyPem: String,
        publicKeyLine: String,
        onLog: (String) -> Unit,
    ): SSHClient {
        onLog("Connecting to $user@$host:$port…")

        HopSecurityProviders.ensureRegistered()
        val resolvedHost = IPv4Only.resolveHost(host)
        val sshClient = SSHClient()
        sshClient.addHostKeyVerifier(PromiscuousVerifier())
        sshClient.timeout = 120_000
        try {
            sshClient.connect(resolvedHost, port)
            if (password != null) {
                sshClient.authPassword(user, password)
            } else {
                val keyProvider = HopKeyProviders.keyProviderFor(privateKeyPem)
                sshClient.authPublickey(user, keyProvider)
            }

            onLog("Ensuring deploy key is in authorized_keys…")
            HopSSH.runCommand(sshClient, authorizedKeysCommand(publicKeyLine, host))
            onLog("Deploy key authorized.")
            return sshClient
        } catch (error: Throwable) {
            runCatching { sshClient.disconnect() }
            throw error
        }
    }

    private fun authorizedKeysCommand(publicKeyLine: String, host: String): String {
        val pub = ShellQuote.bashSingle(publicKeyLine)
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
            "grep -qF $pub ~/.ssh/authorized_keys 2>/dev/null || echo $pub deploy-${ShellQuote.bashSingle(host)} >>~/.ssh/authorized_keys"
    }

    private fun installCommand(host: String, port: Int, installDir: String): String {
        val url = ShellQuote.bashSingle(HopConstants.SERVER_INSTALL_URL)
        val ref = ShellQuote.bashSingle(HopConstants.SERVER_INSTALL_REF)
        val dir = ShellQuote.bashSingle(installDir)
        val hostQ = ShellQuote.bashSingle(host)
        val portQ = ShellQuote.bashSingle(port.toString())
        return "curl -fsSL $url | HOPPER_REF=$ref HOPPER_INSTALL_DIR=$dir bash -s -- --configure --host $hostQ --port $portQ"
    }
}
