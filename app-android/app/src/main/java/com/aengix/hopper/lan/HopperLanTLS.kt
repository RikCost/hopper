package com.aengix.hopper.lan

import com.aengix.hopper.data.HopperConf
import com.aengix.hopper.data.HopperLanInvite
import com.aengix.hopper.data.HopperLanWire
import com.aengix.hopper.ssh.HopSecurityProviders
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HopperLanCertificate {
    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    fun generate(): Pair<KeyPair, X509Certificate> {
        HopSecurityProviders.ensureRegistered()
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        val now = Date()
        val name = X500Name("CN=hopper-lan")
        val serial = BigInteger(128, SecureRandom()).abs()
        val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            Date(now.time - 3_600_000),
            Date(now.time + 86_400_000),
            name,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
        return keyPair to cert
    }
}

class HopperLanFramedSocket(socket: SSLSocket) : AutoCloseable {
    private val socket = socket
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)

    fun send(obj: JSONObject) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..HopperLanInvite.MAX_FRAME_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(): JSONObject {
        val size = input.readInt()
        require(size in 1..HopperLanInvite.MAX_FRAME_BYTES) { "invalid frame" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

object HopperLanTLS {
    private fun sslContext(): SSLContext =
        runCatching { SSLContext.getInstance("TLS", "AndroidOpenSSL") }
            .getOrElse { SSLContext.getInstance("TLS") }

    fun serverContext(keyPair: KeyPair, cert: X509Certificate): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setKeyEntry("lan", keyPair.private, CharArray(0), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, CharArray(0))
        val ctx = sslContext()
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }

    fun clientContext(expectedFingerprint: String): SSLContext {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val presented = chain.firstOrNull() ?: error("no server certificate")
                val fp = HopperLanCertificate.fingerprint(presented.encoded)
                if (fp != expectedFingerprint.lowercase()) {
                    error("TLS certificate did not match the QR code.")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = sslContext()
        ctx.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        return ctx
    }

    fun bindServer(ctx: SSLContext): SSLServerSocket {
        val server = ctx.serverSocketFactory.createServerSocket(
            0,
            1,
            InetAddress.getByName("0.0.0.0"),
        ) as SSLServerSocket
        server.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        return server
    }
}

fun hopperLanHello(): JSONObject = JSONObject()
    .put(HopperLanWire.TYPE, HopperLanWire.HELLO)
    .put(HopperLanWire.VERSION, HopperLanInvite.CURRENT_VERSION)

fun hopperLanReady(): JSONObject = JSONObject().put(HopperLanWire.TYPE, HopperLanWire.READY)

fun hopperLanBye(): JSONObject = JSONObject().put(HopperLanWire.TYPE, HopperLanWire.BYE)

fun hopperLanItem(payload: HopperConf.Payload): JSONObject = JSONObject()
    .put(HopperLanWire.TYPE, HopperLanWire.ITEM)
    .put(HopperLanWire.PAYLOAD, HopperConf.exportPayloadObject(payload))
