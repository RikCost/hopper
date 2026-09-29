package com.aengix.hopper.provision

import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.model.ServerVersionInfo
import com.aengix.hopper.ssh.HopSSH
import com.aengix.hopper.util.ShellQuote
import kotlinx.serialization.json.Json

object VersionService {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchServerVersion(hop: HopNodeProfile): ServerVersionInfo {
        val install = hop.resolvedInstallDir
        val cmd = "cd ${ShellQuote.bashRemotePath(install)} && ./hopperctl configure --version-json"
        val output = HopSSH.withSession(hop) { client ->
            HopSSH.runCommand(client, cmd)
        }
        val jsonLine = extractJsonLine(output)
            ?: throw IllegalStateException("Server returned invalid version JSON. Output: ${output.takeLast(200)}")
        return json.decodeFromString<ServerVersionInfo>(jsonLine)
    }

    fun updateServer(hop: HopNodeProfile, version: String) {
        val install = hop.resolvedInstallDir
        val cmd = "cd ${ShellQuote.bashRemotePath(install)} && ./hopperctl update --update --to ${shellQuote(version)} --json-only"
        HopSSH.withSession(hop) { client ->
            HopSSH.runCommand(client, cmd)
        }
    }

    fun fetchChainStatus(hop: HopNodeProfile, chainId: String): com.aengix.hopper.model.ChainStatusReport {
        val install = hop.resolvedInstallDir
        val cmd = "cd ${ShellQuote.bashRemotePath(install)} && ./hopperctl status --chain-id ${shellQuote(chainId)}"
        val output = HopSSH.withSession(hop) { client ->
            HopSSH.runCommand(client, cmd)
        }
        return json.decodeFromString(output.trim())
    }

    private fun shellQuote(value: String): String = ShellQuote.bashSingle(value)

    private fun extractJsonLine(output: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .asReversed()
            .firstOrNull { it.startsWith("{") }
}

private val HopNodeProfile.resolvedInstallDir: String
    get() {
        val trimmed = installDir.trim()
        return trimmed.ifEmpty { com.aengix.hopper.model.HopConstants.DEFAULT_INSTALL_DIR }
    }
