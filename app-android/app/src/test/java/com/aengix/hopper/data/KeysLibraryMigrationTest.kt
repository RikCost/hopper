package com.aengix.hopper.data

import com.aengix.hopper.model.AppState
import com.aengix.hopper.model.DeploySSHKey
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.ssh.SSHKeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysLibraryMigrationTest {
    @Test
    fun parseDeployName_extractsUserAndHost() {
        val parsed = KeysLibraryMigration.parseDeployName("Deploy root@203.0.113.10")
        assertEquals("root", parsed!!.first)
        assertEquals("203.0.113.10", parsed.second)
    }

    @Test
    fun migrate_seedsAssignmentFromAutoName() {
        val generated = SSHKeyGenerator.generateEd25519("hopper-deploy@203.0.113.10")
        val server = HopNodeProfile(
            id = "srv-1",
            name = "ams",
            host = "203.0.113.10",
            user = "root",
            privateKey = "hop-daemon-key",
        )
        val state = AppState(
            servers = listOf(server),
            deployKeys = listOf(
                DeploySSHKey(
                    name = "Deploy root@203.0.113.10",
                    privateKey = generated.privateKeyPem,
                ),
            ),
        )
        val migrated = KeysLibraryMigration.migrate(state)
        assertEquals(1, migrated.deployKeys.size)
        assertEquals(1, migrated.deployKeys[0].assignments.size)
        assertEquals("srv-1", migrated.deployKeys[0].assignments[0].serverID)
        assertEquals("root", migrated.deployKeys[0].assignments[0].user)
        assertEquals("203.0.113.10", migrated.deployKeys[0].assignments[0].host)
        assertEquals("hop-daemon-key", migrated.servers[0].privateKey)
    }

    @Test
    fun migrate_deduplicatesSameFingerprint() {
        val generated = SSHKeyGenerator.generateEd25519("hopper")
        val state = AppState(
            deployKeys = listOf(
                DeploySSHKey(name = "First", privateKey = generated.privateKeyPem),
                DeploySSHKey(name = "", privateKey = generated.privateKeyPem),
            ),
        )
        val migrated = KeysLibraryMigration.migrate(state)
        assertEquals(1, migrated.deployKeys.size)
        assertEquals("First", migrated.deployKeys[0].name)
    }

    @Test
    fun migrate_doesNotImportHopPrivateKeys() {
        val state = AppState(
            servers = listOf(
                HopNodeProfile(
                    name = "ams",
                    host = "203.0.113.10",
                    user = "root",
                    privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nHOP\n-----END OPENSSH PRIVATE KEY-----",
                ),
            ),
        )
        val migrated = KeysLibraryMigration.migrate(state)
        assertTrue(migrated.deployKeys.isEmpty())
    }
}
