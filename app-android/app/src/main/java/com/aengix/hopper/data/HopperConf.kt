package com.aengix.hopper.data

import com.aengix.hopper.model.DeploySSHKey
import com.aengix.hopper.model.HopConstants
import com.aengix.hopper.model.HopNodeProfile
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypted `.hopperconf` file format + plaintext QR payload kinds. */
object HopperConf {
    const val FILE_EXTENSION = "hopperconf"
    const val MIME_TYPE = "application/x-hopperconf"
    const val ENVELOPE_VERSION = 1
    const val PAYLOAD_VERSION = 1
    const val PBKDF2_ITERATIONS = 210_000
    const val SALT_LENGTH = 16
    const val NONCE_LENGTH = 12
    const val KEY_LENGTH = 32
    const val GCM_TAG_BITS = 128

    object Field {
        const val VERSION = "v"
        const val FMT = "fmt"
        const val FMT_VALUE = "hopperconf"
        const val ALG = "alg"
        const val ALG_VALUE = "aes-256-gcm"
        const val KDF = "kdf"
        const val KDF_VALUE = "pbkdf2-sha256"
        const val ITER = "iter"
        const val SALT = "salt"
        const val NONCE = "nonce"
        const val DATA = "data"
        const val KIND = "kind"
        const val NAME = "name"
        const val SERVER = "server"
        const val HOPS = "hops"
        const val KEY = "key"
        const val PRIVATE_KEY = "private_key"
    }

    sealed class Payload {
        data class Server(val profile: HopNodeProfile) : Payload()
        data class Chain(val name: String, val hops: List<HopNodeProfile>) : Payload()
        data class Key(val key: DeploySSHKey) : Payload()
    }

    sealed class ConfError(message: String) : Exception(message) {
        data object Empty : ConfError("The file is empty.")
        data object InvalidEnvelope : ConfError("Not a valid .hopperconf file.")
        data object InvalidPayload : ConfError("The file contents are invalid.")
        data object DecryptionFailed : ConfError("Could not decrypt — check the password.")
        data object EncryptionFailed : ConfError("Could not encrypt the configuration.")
        data object EmptyChain : ConfError("The chain has no servers to share.")
        data object EmptyKey : ConfError("The key has no private key material to share.")
    }

    fun resolvedPassword(password: String?): String {
        val trimmed = password?.trim().orEmpty()
        return trimmed.ifEmpty { HopConstants.APP_DISPLAY_NAME }
    }

    fun suggestedFileName(payload: Payload): String {
        val raw = when (payload) {
            is Payload.Server -> payload.profile.displayName
            is Payload.Chain -> payload.name.trim().ifEmpty { "chain" }
            is Payload.Key -> payload.key.trimmedName.ifEmpty { "key" }
        }
        val safe = raw.replace('/', '-').replace(':', '-').trim().ifEmpty { "hopper" }
        return "${safe.take(40)}.$FILE_EXTENSION"
    }

    fun exportPayloadObject(payload: Payload): JSONObject {
        return when (payload) {
            is Payload.Server -> JSONObject().apply {
                put(Field.VERSION, PAYLOAD_VERSION)
                put(Field.KIND, "server")
                put(Field.SERVER, HopProfileCodec.exportJson(payload.profile))
            }
            is Payload.Chain -> {
                if (payload.hops.isEmpty()) throw ConfError.EmptyChain
                JSONObject().apply {
                    put(Field.VERSION, PAYLOAD_VERSION)
                    put(Field.KIND, "chain")
                    put(Field.NAME, payload.name)
                    put(Field.HOPS, JSONArray().apply {
                        payload.hops.forEach { put(HopProfileCodec.exportJson(it)) }
                    })
                }
            }
            is Payload.Key -> {
                if (payload.key.privateKey.trim().isEmpty()) throw ConfError.EmptyKey
                JSONObject().apply {
                    put(Field.VERSION, PAYLOAD_VERSION)
                    put(Field.KIND, "key")
                    put(Field.KEY, JSONObject().apply {
                        put(Field.NAME, payload.key.name)
                        put(Field.PRIVATE_KEY, payload.key.privateKey)
                    })
                }
            }
        }
    }

    fun exportPayloadJson(payload: Payload): String =
        exportPayloadObject(payload).toString(2)

    /** QR uses unencrypted payload. Servers stay as legacy hop-profile v2. */
    fun qrPayloadJson(payload: Payload): String = when (payload) {
        is Payload.Server -> HopQRExporter.exportJson(payload.profile)
        is Payload.Chain, is Payload.Key -> exportPayloadJson(payload)
    }

    fun parsePayloadJson(text: String): Payload {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw ConfError.Empty
        val json = runCatching { JSONObject(trimmed) }.getOrElse { throw ConfError.InvalidPayload }
        return parsePayloadObject(json)
    }

    fun parsePayloadObject(json: JSONObject): Payload {
        val kind = json.optString(Field.KIND, "").lowercase()
        when (kind) {
            "chain" -> {
                val name = json.optString(Field.NAME, "")
                val hopsArray = json.optJSONArray(Field.HOPS)
                    ?: throw ConfError.EmptyChain
                if (hopsArray.length() == 0) throw ConfError.EmptyChain
                val hops = buildList {
                    for (i in 0 until hopsArray.length()) {
                        val hopObj = hopsArray.optJSONObject(i) ?: throw ConfError.InvalidPayload
                        add(HopProfileCodec.parseObject(hopObj))
                    }
                }
                return Payload.Chain(name = name, hops = hops)
            }
            "server" -> {
                val serverObj = json.optJSONObject(Field.SERVER) ?: throw ConfError.InvalidPayload
                return Payload.Server(HopProfileCodec.parseObject(serverObj))
            }
            "key" -> {
                val keyObj = json.optJSONObject(Field.KEY) ?: throw ConfError.InvalidPayload
                val pem = keyObj.optString(Field.PRIVATE_KEY).ifEmpty {
                    keyObj.optString("privateKey")
                }
                if (pem.trim().isEmpty()) throw ConfError.EmptyKey
                return Payload.Key(
                    DeploySSHKey(
                        name = keyObj.optString(Field.NAME, ""),
                        privateKey = pem,
                    ),
                )
            }
            else -> return Payload.Server(HopProfileCodec.parseObject(json))
        }
    }

    fun encryptFile(payload: Payload, password: String?): ByteArray {
        val plaintext = exportPayloadJson(payload).toByteArray(Charsets.UTF_8)
        val resolved = resolvedPassword(password)
        val salt = randomBytes(SALT_LENGTH)
        val nonce = randomBytes(NONCE_LENGTH)
        val key = deriveKey(resolved, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val cipherAndTag = try {
            cipher.doFinal(plaintext)
        } catch (_: Exception) {
            throw ConfError.EncryptionFailed
        }
        val envelope = JSONObject().apply {
            put(Field.VERSION, ENVELOPE_VERSION)
            put(Field.FMT, Field.FMT_VALUE)
            put(Field.ALG, Field.ALG_VALUE)
            put(Field.KDF, Field.KDF_VALUE)
            put(Field.ITER, PBKDF2_ITERATIONS)
            put(Field.SALT, Base64.getEncoder().encodeToString(salt))
            put(Field.NONCE, Base64.getEncoder().encodeToString(nonce))
            put(Field.DATA, Base64.getEncoder().encodeToString(cipherAndTag))
        }
        return envelope.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun decryptFile(data: ByteArray, password: String?): Payload {
        if (data.isEmpty()) throw ConfError.Empty
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }
            .getOrElse { throw ConfError.InvalidEnvelope }
        if (json.optString(Field.FMT) != Field.FMT_VALUE) throw ConfError.InvalidEnvelope

        val salt = decodeB64(json.optString(Field.SALT)) ?: throw ConfError.InvalidEnvelope
        val nonce = decodeB64(json.optString(Field.NONCE)) ?: throw ConfError.InvalidEnvelope
        val cipherAndTag = decodeB64(json.optString(Field.DATA)) ?: throw ConfError.InvalidEnvelope
        if (cipherAndTag.size <= 16) throw ConfError.InvalidEnvelope

        val iterations = json.optInt(Field.ITER, PBKDF2_ITERATIONS)
        val defaultPassword = HopConstants.APP_DISPLAY_NAME
        val provided = password?.trim().orEmpty()

        // Prefer the default password first; fall back to a user-provided password.
        openCipher(cipherAndTag, salt, nonce, iterations, defaultPassword)?.let { return it }
        if (provided.isNotEmpty() && provided != defaultPassword) {
            openCipher(cipherAndTag, salt, nonce, iterations, provided)?.let { return it }
        }
        throw ConfError.DecryptionFailed
    }

    private fun openCipher(
        cipherAndTag: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        iterations: Int,
        password: String,
    ): Payload? {
        return try {
            val key = deriveKey(password, salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val plaintext = cipher.doFinal(cipherAndTag)
            parsePayloadJson(String(plaintext, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun isHopperConfFile(data: ByteArray): Boolean {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return false
        return json.optString(Field.FMT) == Field.FMT_VALUE
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        // UTF-8 password bytes — must match Apple CommonCrypto PBKDF2.
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(password.toByteArray(Charsets.UTF_8), salt, iterations)
        return (gen.generateDerivedParameters(KEY_LENGTH * 8) as KeyParameter).key
    }

    private fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun decodeB64(value: String?): ByteArray? {
        if (value.isNullOrEmpty()) return null
        return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
    }
}
