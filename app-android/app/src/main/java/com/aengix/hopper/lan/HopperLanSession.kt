package com.aengix.hopper.lan

import com.aengix.hopper.data.HopperConf
import com.aengix.hopper.data.HopperLanAddress
import com.aengix.hopper.data.HopperLanInvite
import com.aengix.hopper.data.HopperLanWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

sealed class HopperLanReceivePhase {
    data object Starting : HopperLanReceivePhase()
    data object Listening : HopperLanReceivePhase()
    data object Connected : HopperLanReceivePhase()
    data class Failed(val message: String) : HopperLanReceivePhase()
}

class HopperLanReceiveSession(
    private val onImport: (HopperConf.Payload) -> String,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val _phase = MutableStateFlow<HopperLanReceivePhase>(HopperLanReceivePhase.Starting)
    val phase = _phase.asStateFlow()
    private val _invite = MutableStateFlow<HopperLanInvite?>(null)
    val invite = _invite.asStateFlow()
    private val _received = MutableStateFlow<List<String>>(emptyList())
    val received = _received.asStateFlow()

    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var framed: HopperLanFramedSocket? = null

    fun start() {
        scope.launch { run() }
    }

    fun close() {
        job.cancel()
        runCatching { server?.close() }
        runCatching { framed?.close() }
        server = null
        framed = null
    }

    private fun run() {
        try {
            val ip = HopperLanAddress.advertisedIPv4()
                ?: error("Connect this device to Wi-Fi (or a hotspot) so the other phone can reach it.")
            val (keyPair, cert) = HopperLanCertificate.generate()
            val ctx = HopperLanTLS.serverContext(keyPair, cert)
            val server = HopperLanTLS.bindServer(ctx)
            this.server = server
            val invite = HopperLanInvite.make(
                ip = ip,
                port = server.localPort,
                fingerprint = HopperLanCertificate.fingerprint(cert.encoded),
            )
            _invite.value = invite
            _phase.value = HopperLanReceivePhase.Listening
            val socket = server.accept() as SSLSocket
            runCatching { server.close() }
            this.server = null
            socket.startHandshake()
            val framed = HopperLanFramedSocket(socket)
            this.framed = framed
            _phase.value = HopperLanReceivePhase.Connected
            val hello = framed.receive()
            if (hello.optString(HopperLanWire.TYPE) != HopperLanWire.HELLO) {
                error("The other device sent an invalid message.")
            }
            framed.send(hopperLanReady())
            while (job.isActive) {
                val message = framed.receive()
                when (message.optString(HopperLanWire.TYPE)) {
                    HopperLanWire.BYE -> return
                    HopperLanWire.ITEM -> {
                        val payloadObj = message.optJSONObject(HopperLanWire.PAYLOAD)
                            ?: error("The other device sent an invalid message.")
                        try {
                            val payload = HopperConf.parsePayloadObject(payloadObj)
                            val summary = onImport(payload)
                            _received.update { it + summary }
                            framed.send(
                                JSONObject()
                                    .put(HopperLanWire.TYPE, HopperLanWire.OK)
                                    .put(HopperLanWire.SUMMARY, summary),
                            )
                        } catch (e: Exception) {
                            framed.send(
                                JSONObject()
                                    .put(HopperLanWire.TYPE, HopperLanWire.ERR)
                                    .put(HopperLanWire.ERROR, e.message ?: "Import failed"),
                            )
                        }
                    }
                    else -> framed.send(
                        JSONObject()
                            .put(HopperLanWire.TYPE, HopperLanWire.ERR)
                            .put(HopperLanWire.ERROR, "The other device sent an invalid message."),
                    )
                }
            }
        } catch (e: Exception) {
            if (job.isActive) {
                _phase.value = HopperLanReceivePhase.Failed(e.message ?: "Receive failed")
            }
        }
    }
}

class HopperLanSendSession(
    private val invite: HopperLanInvite,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val _status = MutableStateFlow("Connecting…")
    val status = _status.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()

    @Volatile private var framed: HopperLanFramedSocket? = null

    fun start() {
        scope.launch { connect() }
    }

    fun close() {
        runCatching { framed?.send(hopperLanBye()) }
        job.cancel()
        runCatching { framed?.close() }
        framed = null
    }

    fun share(payload: HopperConf.Payload) {
        scope.launch {
            val socket = framed ?: return@launch
            try {
                socket.send(hopperLanItem(payload))
                val reply = socket.receive()
                val line = when (reply.optString(HopperLanWire.TYPE)) {
                    HopperLanWire.OK -> reply.optString(HopperLanWire.SUMMARY, "Shared.")
                    else -> reply.optString(HopperLanWire.ERROR, "Share failed.")
                }
                _log.update { it + line }
                _status.value = line
            } catch (e: Exception) {
                val line = e.message ?: "Share failed."
                _log.update { it + line }
                _status.value = line
            }
        }
    }

    private fun connect() {
        try {
            val ctx = HopperLanTLS.clientContext(invite.fingerprint)
            val socket = ctx.socketFactory.createSocket(invite.ip, invite.port) as SSLSocket
            socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            socket.startHandshake()
            val framed = HopperLanFramedSocket(socket)
            this.framed = framed
            framed.send(hopperLanHello())
            val reply = framed.receive()
            if (reply.optString(HopperLanWire.TYPE) != HopperLanWire.READY) {
                error("The other device sent an invalid message.")
            }
            _ready.value = true
            _status.value = "Connected. Choose a chain, server, or key to share."
        } catch (e: Exception) {
            _ready.value = false
            _status.value = e.message ?: "Could not connect to the other device."
        }
    }
}
