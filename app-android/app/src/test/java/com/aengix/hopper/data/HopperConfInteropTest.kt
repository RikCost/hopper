package com.aengix.hopper.data

import com.aengix.hopper.model.HopNodeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cross-system .hopperconf tests: Android round-trip plus decrypt of golden
 * vectors from the Python reference (`tests/hopperconf/vectors/`, mirrored under
 * `src/test/resources/hopperconf/`).
 */
class HopperConfInteropTest {
    private val customPassword = "test-password-🔐"

    private fun sampleServer(): HopNodeProfile = HopNodeProfile(
        name = "interop-server",
        host = "203.0.113.10",
        port = 22,
        user = "root",
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nTESTKEY\n-----END OPENSSH PRIVATE KEY-----",
        hostKeys = listOf("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIInteropTestHostKey"),
        installDir = "~/hopper",
        serverVersion = "2.5.0",
        minAppVersion = "2.5.0",
    )

    private fun readVector(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("hopperconf/$name")
            ?: error("Missing test resource hopperconf/$name — run: python3 tests/hopperconf/test_hopperconf.py generate")
        return stream.use { it.readBytes() }
    }

    @Test
    fun resolvedPassword_defaultsToDisplayName() {
        assertEquals("ɹǝddoH", HopperConf.resolvedPassword(null))
        assertEquals("ɹǝddoH", HopperConf.resolvedPassword(""))
        assertEquals("ɹǝddoH", HopperConf.resolvedPassword("  "))
        assertEquals("secret", HopperConf.resolvedPassword("secret"))
    }

    @Test
    fun roundtrip_server_defaultPassword() {
        val payload = HopperConf.Payload.Server(sampleServer())
        val bytes = HopperConf.encryptFile(payload, null)
        val decoded = HopperConf.decryptFile(bytes, null)
        val server = decoded as HopperConf.Payload.Server
        assertEquals("203.0.113.10", server.profile.host)
        assertEquals("root", server.profile.user)
        assertTrue(server.profile.privateKey.contains("PRIVATE KEY"))
    }

    @Test
    fun roundtrip_chain_customPassword() {
        val hops = listOf(
            sampleServer(),
            sampleServer().copy(name = "interop-exit", host = "198.51.100.20"),
        )
        val payload = HopperConf.Payload.Chain("Interop Chain", hops)
        val bytes = HopperConf.encryptFile(payload, customPassword)
        val decoded = HopperConf.decryptFile(bytes, customPassword) as HopperConf.Payload.Chain
        assertEquals("Interop Chain", decoded.name)
        assertEquals(2, decoded.hops.size)
        assertEquals("198.51.100.20", decoded.hops[1].host)
    }

    @Test
    fun wrongPassword_fails() {
        val bytes = HopperConf.encryptFile(HopperConf.Payload.Server(sampleServer()), "a")
        try {
            HopperConf.decryptFile(bytes, "b")
            fail("expected decryption failure")
        } catch (_: HopperConf.ConfError) {
            // expected (DecryptionFailed)
        }
    }

    @Test
    fun defaultTriedBeforeCustom_opensDefaultEncryptedFile() {
        val bytes = HopperConf.encryptFile(HopperConf.Payload.Server(sampleServer()), null)
        val decoded = HopperConf.decryptFile(bytes, "wrong-custom") as HopperConf.Payload.Server
        assertEquals("interop-server", decoded.profile.name)
    }

    @Test
    fun customFallback_afterDefaultFails() {
        val bytes = HopperConf.encryptFile(HopperConf.Payload.Server(sampleServer()), customPassword)
        val decoded = HopperConf.decryptFile(bytes, customPassword) as HopperConf.Payload.Server
        assertEquals("203.0.113.10", decoded.profile.host)
        try {
            HopperConf.decryptFile(bytes, "not-the-custom-password")
            fail("expected decryption failure")
        } catch (_: HopperConf.ConfError) {
            // expected
        }
    }

    @Test
    fun emptyPassword_matchesDefaultString() {
        val bytes = HopperConf.encryptFile(HopperConf.Payload.Server(sampleServer()), "")
        val decoded = HopperConf.decryptFile(bytes, "ɹǝddoH") as HopperConf.Payload.Server
        assertEquals("interop-server", decoded.profile.name)
    }

    @Test
    fun decrypt_pythonGolden_serverDefault() {
        val bytes = readVector("server_default.hopperconf")
        assertTrue(HopperConf.isHopperConfFile(bytes))
        val decoded = HopperConf.decryptFile(bytes, null) as HopperConf.Payload.Server
        assertEquals("interop-server", decoded.profile.name)
        assertEquals("203.0.113.10", decoded.profile.host)
    }

    @Test
    fun decrypt_pythonGolden_serverCustom() {
        val decoded = HopperConf.decryptFile(readVector("server_custom.hopperconf"), customPassword)
            as HopperConf.Payload.Server
        assertEquals("root", decoded.profile.user)
    }

    @Test
    fun decrypt_pythonGolden_chainDefault() {
        val decoded = HopperConf.decryptFile(readVector("chain_default.hopperconf"), null)
            as HopperConf.Payload.Chain
        assertEquals("Interop Chain", decoded.name)
        assertEquals(2, decoded.hops.size)
    }

    @Test
    fun decrypt_pythonGolden_chainCustom() {
        val decoded = HopperConf.decryptFile(readVector("chain_custom.hopperconf"), customPassword)
            as HopperConf.Payload.Chain
        assertEquals("198.51.100.20", decoded.hops[1].host)
    }

    private fun sampleKey(): com.aengix.hopper.model.DeploySSHKey =
        com.aengix.hopper.model.DeploySSHKey(
            name = "interop-key",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nTESTKEY\n-----END OPENSSH PRIVATE KEY-----",
        )

    @Test
    fun roundtrip_key_defaultPassword() {
        val payload = HopperConf.Payload.Key(sampleKey())
        val bytes = HopperConf.encryptFile(payload, null)
        val decoded = HopperConf.decryptFile(bytes, null) as HopperConf.Payload.Key
        assertEquals("interop-key", decoded.key.name)
        assertTrue(decoded.key.privateKey.contains("PRIVATE KEY"))
    }

    @Test
    fun decrypt_pythonGolden_keyDefault() {
        val decoded = HopperConf.decryptFile(readVector("key_default.hopperconf"), null)
            as HopperConf.Payload.Key
        assertEquals("interop-key", decoded.key.name)
    }

    @Test
    fun decrypt_pythonGolden_keyCustom() {
        val decoded = HopperConf.decryptFile(readVector("key_custom.hopperconf"), customPassword)
            as HopperConf.Payload.Key
        assertTrue(decoded.key.privateKey.contains("PRIVATE KEY"))
    }
}
