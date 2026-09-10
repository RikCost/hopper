package com.aengix.hopper.ssh

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.KeyGenerationParameters
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64

object SSHKeyGenerator {
    data class GeneratedKey(
        val privateKeyPem: String,
        val publicKeyLine: String,
    )

    fun generateEd25519(comment: String): GeneratedKey {
        val generator = Ed25519KeyPairGenerator()
        generator.init(KeyGenerationParameters(SecureRandom(), 256))
        val pair = generator.generateKeyPair()
        val privateParams = pair.private as Ed25519PrivateKeyParameters
        val publicParams = pair.public as Ed25519PublicKeyParameters
        val pem = encodeOpenSSHPrivateKey(privateParams, publicParams, comment)
        val publicLine = openSSHPublicKeyLine(publicParams.encoded, comment)
        return GeneratedKey(privateKeyPem = pem, publicKeyLine = publicLine)
    }

    fun publicKeyLine(privateKeyPem: String, comment: String = ""): String {
        val publicBytes = readPublicKeyBytes(privateKeyPem)
        return openSSHPublicKeyLine(publicBytes, comment)
    }

    /** Type + base64 blob only (comment stripped). Null if the PEM is not a valid ED25519 key. */
    fun normalizedPublicKeyLine(privateKeyPem: String): String? {
        return runCatching { stripPublicKeyComment(publicKeyLine(privateKeyPem)) }.getOrNull()
    }

    fun isValidPrivateKey(privateKeyPem: String): Boolean =
        normalizedPublicKeyLine(privateKeyPem) != null

    fun stripPublicKeyComment(line: String): String {
        val parts = line.trim().split(Regex("\\s+"))
        return if (parts.size >= 2) "${parts[0]} ${parts[1]}" else line.trim()
    }

    private fun openSSHPublicKeyLine(publicKeyBytes: ByteArray, comment: String): String {
        val blob = sshWireBlob("ssh-ed25519", publicKeyBytes)
        val encoded = Base64.getEncoder().encodeToString(blob)
        return if (comment.isBlank()) {
            "ssh-ed25519 $encoded"
        } else {
            "ssh-ed25519 $encoded $comment"
        }
    }

    private fun sshWireBlob(type: String, keyBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeSshString(out, type)
        writeSshString(out, keyBytes)
        return out.toByteArray()
    }

    private fun encodeOpenSSHPrivateKey(
        privateKey: Ed25519PrivateKeyParameters,
        publicKey: Ed25519PublicKeyParameters,
        comment: String,
    ): String {
        val privateBytes = privateKey.encoded
        val publicBytes = publicKey.encoded
        val checksum = SecureRandom().nextInt()

        val privateSection = ByteArrayOutputStream()
        writeUint32(privateSection, checksum)
        writeUint32(privateSection, checksum)
        writeSshString(privateSection, "ssh-ed25519")
        writeSshString(privateSection, publicBytes)
        writeSshString(privateSection, privateBytes + publicBytes)
        writeSshString(privateSection, comment)
        val pad = 8 - (privateSection.size() % 8)
        val padding = if (pad == 8) 0 else pad
        for (i in 1..padding) {
            privateSection.write(i)
        }

        val payload = ByteArrayOutputStream()
        payload.write("openssh-key-v1".toByteArray(Charsets.US_ASCII))
        payload.write(0)
        writeSshString(payload, "none")
        writeSshString(payload, "none")
        writeSshString(payload, ByteArray(0))
        writeUint32(payload, 1)

        val publicSection = ByteArrayOutputStream()
        writeSshString(publicSection, "ssh-ed25519")
        writeSshString(publicSection, publicBytes)
        writeSshString(payload, publicSection.toByteArray())
        writeSshString(payload, privateSection.toByteArray())

        val b64 = Base64.getEncoder().encodeToString(payload.toByteArray())
        return buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            append(b64)
            append("\n-----END OPENSSH PRIVATE KEY-----\n")
        }
    }

    private fun readPublicKeyBytes(privateKeyPem: String): ByteArray {
        val body = privateKeyPem.lines()
            .filter { !it.startsWith("-----") && it.isNotBlank() }
            .joinToString("")
        val data = Base64.getDecoder().decode(body)
        var offset = "openssh-key-v1".length + 1
        offset = skipSshString(data, offset)
        offset = skipSshString(data, offset)
        offset = skipSshString(data, offset)
        offset += 4
        offset = skipSshString(data, offset)
        val privateWrapped = readSshString(data, offset).first
        var p = 8
        p = skipSshString(privateWrapped, p)
        p = skipSshString(privateWrapped, p)
        val keyPairBytes = readSshString(privateWrapped, p).first
        require(keyPairBytes.size >= 64) { "Invalid OpenSSH private key" }
        return keyPairBytes.copyOfRange(32, 64)
    }

    private fun readSshString(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
        val length = readUint32(data, offset)
        val start = offset + 4
        val end = start + length
        return data.copyOfRange(start, end) to end
    }

    private fun skipSshString(data: ByteArray, offset: Int): Int =
        readSshString(data, offset).second

    private fun readUint32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun writeUint32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeSshString(out: ByteArrayOutputStream, value: String) {
        writeSshString(out, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeUint32(out, bytes.size)
        out.write(bytes)
    }
}
