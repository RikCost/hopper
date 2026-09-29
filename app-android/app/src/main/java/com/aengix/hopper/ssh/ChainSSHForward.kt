package com.aengix.hopper.ssh

import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.util.HopErrorDetails
import com.aengix.hopper.util.TunnelLog
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

sealed class ChainSSHForwardException(message: String) : Exception(message) {
    data object EmptyChain : ChainSSHForwardException("Add at least one hop (entry → exit order).")
    data class ConnectFailed(val hop: String, val detail: String) :
        ChainSSHForwardException("Could not connect to $hop: $detail")
    data class ForwardFailed(val hop: String, val via: String, val detail: String) :
        ChainSSHForwardException("Could not forward from $via to $hop: $detail")
}

/**
 * SSH sessions for one chain. Index 0 is a direct login to the entry hop.
 * Each later session is an SSH login carried by a direct-tcpip channel on the previous hop.
 */
class ChainSSHForward private constructor(
    val hops: List<HopNodeProfile>,
    private val clients: MutableList<SSHClient>,
) {
    @Volatile
    private var closed = false

    fun client(at: Int): SSHClient = clients[at]

    /** Closes SSH sessions from the exit back to the entry. */
    fun close() {
        if (closed) return
        closed = true
        val snapshot = clients.toList()
        clients.clear()
        closeAll(snapshot)
    }

    companion object {
        fun <T> withChain(
            hops: List<HopNodeProfile>,
            onHop: ((Int, HopNodeProfile) -> Unit)? = null,
            body: (ChainSSHForward) -> T,
        ): T {
            val forward = open(hops, onHop)
            return try {
                body(forward)
            } finally {
                forward.close()
            }
        }

        fun open(
            hops: List<HopNodeProfile>,
            onHop: ((Int, HopNodeProfile) -> Unit)? = null,
        ): ChainSSHForward {
            val entry = hops.firstOrNull() ?: throw ChainSSHForwardException.EmptyChain
            val clients = mutableListOf<SSHClient>()
            try {
                onHop?.invoke(0, entry)
                TunnelLog.info("Chain SSH connect ${entry.trimmedUser}@${entry.trimmedHost}:${entry.port}")
                val first = try {
                    HopSSH.connect(entry)
                } catch (error: Throwable) {
                    throw ChainSSHForwardException.ConnectFailed(
                        hop = entry.displayName,
                        detail = HopErrorDetails.describe(error),
                    )
                }
                clients += first

                for (index in 1 until hops.size) {
                    val hop = hops[index]
                    val via = hops[index - 1]
                    onHop?.invoke(index, hop)
                    TunnelLog.info(
                        "Chain SSH forward ${via.displayName} -> ${hop.trimmedUser}@${hop.trimmedHost}:${hop.port}",
                    )
                    try {
                        clients += jump(from = clients[index - 1], to = hop)
                    } catch (error: Throwable) {
                        throw ChainSSHForwardException.ForwardFailed(
                            hop = hop.displayName,
                            via = via.displayName,
                            detail = HopErrorDetails.describe(error),
                        )
                    }
                }
            } catch (error: Throwable) {
                closeAll(clients)
                throw error
            }
            return ChainSSHForward(hops, clients)
        }

        private fun jump(from: SSHClient, to: HopNodeProfile): SSHClient {
            HopSecurityProviders.ensureRegistered()
            val host = to.trimmedHost
            val port = to.port
            val user = to.trimmedUser.ifEmpty { "root" }
            val tunnel = from.newDirectConnection(host, port)
            val next = SSHClient()
            next.addHostKeyVerifier(PromiscuousVerifier())
            next.connectVia(tunnel)
            next.timeout = 60_000
            val keyProvider = HopKeyProviders.keyProviderFor(to.privateKey)
            next.authPublickey(user, keyProvider)
            return next
        }

        private fun closeAll(clients: List<SSHClient>) {
            if (clients.isEmpty()) return
            TunnelLog.info("Closing ${clients.size} chain SSH session(s), innermost first")
            clients.asReversed().forEach { client ->
                runCatching { client.disconnect() }
                    .onFailure { TunnelLog.info("Chain SSH close: ${HopErrorDetails.describe(it)}") }
            }
        }
    }
}
