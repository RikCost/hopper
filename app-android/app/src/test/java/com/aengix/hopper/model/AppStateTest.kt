package com.aengix.hopper.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateTest {
    @Test
    fun removeDeployKeys_deletesUnassignedKey() {
        val key = DeploySSHKey(id = "k1", name = "solo", privateKey = "x")
        val next = AppState(deployKeys = listOf(key)).removeDeployKeys(setOf("k1"))
        assertTrue(next.deployKeys.isEmpty())
    }

    @Test
    fun removeDeployKeys_keepsAssignedKey() {
        val server = HopNodeProfile(id = "s1", host = "1.1.1.1", user = "root")
        val key = DeploySSHKey(
            id = "k1",
            name = "used",
            privateKey = "x",
            assignments = listOf(
                DeployKeyAssignment(serverID = "s1", host = "1.1.1.1", user = "root"),
            ),
        )
        val next = AppState(
            servers = listOf(server),
            deployKeys = listOf(key),
        ).removeDeployKeys(setOf("k1"))
        assertEquals(1, next.deployKeys.size)
        assertEquals("k1", next.deployKeys[0].id)
    }

    @Test
    fun removeServers_dropsServerAndChainHops() {
        val server = HopNodeProfile(id = "s1", host = "1.1.1.1", user = "root")
        val chain = HopChain(id = "c1", name = "main", hopIDs = listOf("s1", "s2"))
        val next = AppState(
            servers = listOf(server),
            chains = listOf(chain),
            selectedChainID = "c1",
        ).removeServers(setOf("s1"))
        assertTrue(next.servers.isEmpty())
        assertEquals(listOf("s2"), next.chains[0].hopIDs)
        assertEquals("c1", next.selectedChainID)
    }

    @Test
    fun removeChains_dropsSelectedChain() {
        val chain = HopChain(id = "c1", name = "main")
        val next = AppState(
            chains = listOf(chain, HopChain(id = "c2", name = "other")),
            selectedChainID = "c1",
        ).removeChains(setOf("c1"))
        assertEquals(listOf("c2"), next.chains.map { it.id })
        assertEquals("c2", next.selectedChainID)
    }
}
