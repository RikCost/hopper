package com.aengix.hopper.model

import com.aengix.hopper.ssh.SSHKeyGenerator
import kotlinx.serialization.Serializable

@Serializable
data class AppState(
    val servers: List<HopNodeProfile> = emptyList(),
    val chains: List<HopChain> = emptyList(),
    val selectedChainID: String? = null,
    val deployKeys: List<DeploySSHKey> = emptyList(),
) {
    val selectedChain: HopChain?
        get() = selectedChainID?.let { id -> chains.firstOrNull { it.id == id } }

    val activeHops: List<HopNodeProfile>
        get() = selectedChain?.let { resolveHops(it) } ?: emptyList()

    val entryHop: HopNodeProfile? get() = activeHops.firstOrNull()

    fun server(id: String): HopNodeProfile? = servers.firstOrNull { it.id == id }

    fun resolveHops(chain: HopChain): List<HopNodeProfile> =
        chain.hopIDs.mapNotNull { server(it) }

    fun addServer(server: HopNodeProfile): AppState = copy(servers = servers + server)

    fun addDeployKey(key: DeploySSHKey): AppState = copy(deployKeys = deployKeys + key)

    fun importDeployKey(key: DeploySSHKey): AppState {
        val fingerprint = SSHKeyGenerator.normalizedPublicKeyLine(key.privateKey)
        if (fingerprint != null && deployKeys.any {
                SSHKeyGenerator.normalizedPublicKeyLine(it.privateKey) == fingerprint
            }
        ) {
            return this
        }
        val stored = key.copy(
            id = java.util.UUID.randomUUID().toString(),
            createdAt = if (key.createdAt == 0L) System.currentTimeMillis() else key.createdAt,
        )
        return copy(deployKeys = deployKeys + stored)
    }

    fun renameDeployKey(id: String, name: String): AppState =
        copy(deployKeys = deployKeys.map { if (it.id == id) it.copy(name = name) else it })

    fun recordDeployKeyUse(id: String, server: HopNodeProfile): AppState =
        copy(deployKeys = deployKeys.map { if (it.id == id) it.recordAssignment(server) else it })

    fun removeDeployKeys(ids: Set<String>): AppState =
        copy(deployKeys = deployKeys.filterNot { it.id in ids && it.canDelete(servers) })

    fun deployKey(id: String): DeploySSHKey? = deployKeys.firstOrNull { it.id == id }

    fun renameServer(id: String, name: String): AppState =
        copy(servers = servers.map { if (it.id == id) it.copy(name = name) else it })

    fun removeServers(ids: Set<String>): AppState {
        val nextServers = servers.filter { it.id !in ids }
        val nextChains = chains.map { chain ->
            chain.copy(hopIDs = chain.hopIDs.filter { it !in ids })
        }
        val nextSelected = selectedChainID?.takeIf { id ->
            nextChains.any { it.id == id }
        } ?: nextChains.firstOrNull()?.id
        return copy(servers = nextServers, chains = nextChains, selectedChainID = nextSelected)
    }

    fun addChain(name: String = ""): Pair<AppState, String> {
        val chain = HopChain(name = name)
        val next = copy(
            chains = chains + chain,
            selectedChainID = selectedChainID ?: chain.id,
        )
        return next to chain.id
    }

    fun removeChains(ids: Set<String>): AppState {
        val nextChains = chains.filter { it.id !in ids }
        val nextSelected = when {
            selectedChainID in ids -> nextChains.firstOrNull()?.id
            else -> selectedChainID
        }
        return copy(chains = nextChains, selectedChainID = nextSelected)
    }

    fun selectChain(id: String?): AppState = copy(selectedChainID = id)

    fun renameChain(id: String, name: String): AppState =
        copy(chains = chains.map { if (it.id == id) it.copy(name = name) else it })

    fun moveHopInChain(chainID: String, fromIndex: Int, toIndex: Int): AppState {
        val chainIndex = chains.indexOfFirst { it.id == chainID }
        if (chainIndex < 0) return this
        val hopIDs = chains[chainIndex].hopIDs.toMutableList()
        if (fromIndex !in hopIDs.indices || toIndex !in 0..hopIDs.size) return this
        val item = hopIDs.removeAt(fromIndex)
        hopIDs.add(toIndex, item)
        val nextChains = chains.toMutableList()
        nextChains[chainIndex] = chains[chainIndex].copy(hopIDs = hopIDs)
        return copy(chains = nextChains)
    }

    fun addServerToChain(chainID: String, serverID: String): AppState {
        if (servers.none { it.id == serverID }) return this
        return copy(chains = chains.map { chain ->
            if (chain.id != chainID || serverID in chain.hopIDs) chain
            else chain.copy(hopIDs = chain.hopIDs + serverID)
        })
    }

    fun removeHopFromChain(chainID: String, indices: Set<Int>): AppState =
        copy(chains = chains.map { chain ->
            if (chain.id != chainID) chain
            else chain.copy(hopIDs = chain.hopIDs.filterIndexed { index, _ -> index !in indices })
        })

    companion object {
        fun fromLegacyHops(hops: List<HopNodeProfile>): AppState {
            var state = AppState(servers = hops)
            if (hops.isEmpty()) return state
            val (next, chainId) = state.addChain(name = "Default")
            state = next.copy(
                chains = next.chains.map { chain ->
                    if (chain.id == chainId) chain.copy(hopIDs = hops.map { it.id }) else chain
                },
                selectedChainID = chainId,
            )
            return state
        }
    }
}
