package com.aengix.hopper.provision

import com.aengix.hopper.model.ChainTopology
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.model.HopReadyReport
import com.aengix.hopper.ssh.ChainSSHForward
import com.aengix.hopper.ssh.HopSSH
import com.aengix.hopper.util.HopErrorDetails
import com.aengix.hopper.util.ShellQuote
import com.aengix.hopper.util.TunnelLog
import net.schmizz.sshj.SSHClient

sealed class ChainProvisionerException(message: String) : Exception(message) {
    data object EmptyChain : ChainProvisionerException("Add at least one hop (entry → exit order).")
    data class InvalidReadyJson(val output: String) :
        ChainProvisionerException("Server did not return ready JSON. Output: ${output.takeLast(200)}")
    data class MissingPubkey(val hop: String) :
        ChainProvisionerException("Could not read SSH public key on $hop.")
    data class ProvisionFailed(val detail: String) : ChainProvisionerException(detail)
}

object ChainProvisioner {
    /**
     * Opens SSH only to the entry hop, then reaches each later hop through a direct-tcpip forward.
     * Each hop is configured with upstream/downstream for reverse dialing. Forwards close when done.
     */
    fun provision(
        chainId: String,
        chain: List<HopNodeProfile>,
        restartHopperd: Boolean = false,
        onProgress: ((index: Int, total: Int, message: String) -> Unit)? = null,
    ): List<HopReadyReport> {
        if (chain.isEmpty()) throw ChainProvisionerException.EmptyChain

        val total = chain.size
        val overlay = ChainTopology.overlayCIDR(chainId)
        val skipIfRunning = !restartHopperd
        val listenPort = ChainTopology.listenPort(chainId)

        return ChainSSHForward.withChain(
            hops = chain,
            onHop = { index, hop ->
                if (index == 0) {
                    onProgress?.invoke(index, total, "Connecting to entry ${hop.displayName}…")
                } else {
                    val via = chain[index - 1].displayName
                    onProgress?.invoke(index, total, "Forwarding through $via to ${hop.displayName}…")
                }
            },
        ) { forward ->
            if (restartHopperd) {
                onProgress?.invoke(total - 1, total, "Stopping previous hopperd on all hops…")
                for (index in chain.indices) {
                    stopNode(forward.client(index), chain[index], chainId)
                }
            }

            val reports = mutableListOf<HopReadyReport>()

            // Exit → entry so reverse dialers can retry until upstream is listening.
            for (i in (total - 1 downTo 0)) {
                val hop = chain[i]
                val label = hop.displayName
                onProgress?.invoke(i, total, "Configuring $label…")
                TunnelLog.info("Chain provision hop[$i] $label")

                // Trust both directions so either hop can dial (sticky dialer).
                if (i < total - 1) {
                    val downstream = chain[i + 1]
                    val downPub = fetchPubkey(forward.client(i + 1), downstream)
                    trustPubkey(downPub, forward.client(i), hop, chainId)
                    val upPub = fetchPubkey(forward.client(i), hop)
                    trustPubkey(upPub, forward.client(i + 1), downstream, chainId)
                }

                val report = startNode(
                    client = forward.client(i),
                    chainId = chainId,
                    hop = hop,
                    index = i,
                    isExit = i == total - 1,
                    upstream = chain.getOrNull(i - 1),
                    downstream = chain.getOrNull(i + 1),
                    tunnelPort = listenPort,
                    overlay = overlay,
                    skipIfRunning = skipIfRunning,
                )
                reports += report
                onProgress?.invoke(i, total, "$label ready (${report.mode} ${report.addr})")
            }

            reports.asReversed()
        }
    }

    private fun stopNode(client: SSHClient, hop: HopNodeProfile, chainId: String) {
        val install = hop.resolvedInstallDir
        val cmd = "cd ${ShellQuote.bashRemotePath(install)} && ./hopperctl start --chain-id ${shellQuote(chainId)} --stop-only"
        runCatching {
            HopSSH.runCommand(client, cmd)
            TunnelLog.info("Stopped previous hopperd on ${hop.displayName}")
        }.onFailure {
            TunnelLog.info("Stop hopperd on ${hop.displayName} (non-fatal): ${HopErrorDetails.describe(it)}")
        }
    }

    private fun fetchPubkey(client: SSHClient, hop: HopNodeProfile): String {
        val output = HopSSH.runCommand(client, "cat ~/.hopper/id_ed25519.pub").trim()
        if (!output.startsWith("ssh-")) {
            throw ChainProvisionerException.MissingPubkey(hop.displayName)
        }
        return output
    }

    private fun trustPubkey(pubkey: String, client: SSHClient, hop: HopNodeProfile, chainId: String) {
        val install = hop.resolvedInstallDir
        val cmd =
            "cd ${ShellQuote.bashRemotePath(install)} && ./hopperctl start --chain-id ${shellQuote(chainId)} " +
                "--trust-pubkey ${shellQuote(pubkey)} --trust-only"
        HopSSH.runCommand(client, cmd)
        TunnelLog.info("Trusted reverse-dial key on ${hop.displayName}")
    }

    private fun startNode(
        client: SSHClient,
        chainId: String,
        hop: HopNodeProfile,
        index: Int,
        isExit: Boolean,
        upstream: HopNodeProfile?,
        downstream: HopNodeProfile?,
        tunnelPort: Int,
        overlay: String,
        skipIfRunning: Boolean,
    ): HopReadyReport {
        val install = hop.resolvedInstallDir
        val addr = ChainTopology.overlayAddr(chainId, index)
        val args = buildList {
            add("--chain-id"); add(chainId)
            add("--role"); add(if (isExit) "exit" else "relay")
            add("--addr"); add(addr)
            add("--index"); add(index.toString())
            add("--overlay"); add(overlay)
            if (skipIfRunning) {
                add("--if-running"); add("skip")
            }
            if (upstream != null) {
                add("--upstream-host"); add(upstream.trimmedHost)
                add("--upstream-port"); add(upstream.port.toString())
                add("--upstream-user"); add(upstream.trimmedUser.ifEmpty { "root" })
                add("--upstream-tunnel-port"); add(tunnelPort.toString())
            }
            if (downstream != null) {
                add("--downstream-host"); add(downstream.trimmedHost)
                add("--downstream-port"); add(downstream.port.toString())
                add("--downstream-user"); add(downstream.trimmedUser.ifEmpty { "root" })
                add("--downstream-tunnel-port"); add(tunnelPort.toString())
            }
        }

        val argString = args.joinToString(" ") { shellQuote(it) }
        val cmd = "cd ${ShellQuote.bashRemotePath(install)} && ./hopperctl start $argString"

        val output = HopSSH.runCommand(client, cmd)
        return HopReadyReport.parse(output)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

private val HopNodeProfile.resolvedInstallDir: String
    get() {
        val trimmed = installDir.trim()
        return trimmed.ifEmpty { com.aengix.hopper.model.HopConstants.DEFAULT_INSTALL_DIR }
    }
