package com.aengix.hopper.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.aengix.hopper.model.ChainTopology
import com.aengix.hopper.model.HopConstants
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.model.TunnelConnectContext
import com.aengix.hopper.ssh.SSHHopConnector
import com.aengix.hopper.ssh.SSHHopSession
import com.aengix.hopper.tunnel.IPTunnelAssignClient
import com.aengix.hopper.tunnel.IPTunnelEngine
import com.aengix.hopper.util.IPv4Cidr
import com.aengix.hopper.util.IPv4Only
import com.aengix.hopper.util.LocalLanCidrs
import com.aengix.hopper.util.TunnelLog
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.Socket

data class TunnelPrepareResult(
    val tunInterface: ParcelFileDescriptor,
    val sinkholeIPv6: Boolean,
)

class TunnelCoordinator(
    private val vpnService: VpnService,
) {
    var onSessionFailure: ((String) -> Unit)? = null

    private var sshSession: SSHHopSession? = null
    private var ipEngine: IPTunnelEngine? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var sinkholeIPv6 = false
    private val protectedSockets = mutableListOf<WeakReference<Socket>>()

    suspend fun prepare(hop: HopNodeProfile, context: TunnelConnectContext): TunnelPrepareResult {
        TunnelLog.info("SSH entry ${hop.trimmedUser}@${hop.trimmedHost}:${hop.port} chain=${context.chainId}")
        sshSession = SSHHopConnector.connect(entry = hop, hopperPort = context.hopperPort) { socket ->
            protectSocket(socket)
        }

        val clientIP = IPTunnelAssignClient.performAssign(
            stream = sshSession!!.chainStream,
            deviceId = context.deviceId,
            chainId = context.chainId,
        )
        TunnelLog.info("Assigned client overlay IP: $clientIP")

        val builder = buildVpnInterface(clientIP, hop, context)
        val iface = establishInterface(builder)
        tunInterface = iface
        reprotectSockets()
        delay(500)

        return TunnelPrepareResult(iface, sinkholeIPv6)
    }

    private fun buildVpnInterface(
        clientIP: String,
        hop: HopNodeProfile,
        context: TunnelConnectContext,
    ): VpnService.Builder {
        val builder = vpnService.Builder()
        builder.setSession(HopConstants.APP_DISPLAY_NAME)
        builder.setMtu(HopConstants.TUNNEL_MTU)
        builder.setBlocking(true)
        builder.addAddress(clientIP, HopConstants.TUNNEL_IPV4_MASK_BITS)
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")
        addInternetRoutes(builder, hop, context)

        sinkholeIPv6 = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (sinkholeIPv6) {
            builder.addAddress(HopConstants.TUNNEL_IPV6_SINKHOLE, 128)
            builder.addRoute("::", 1)
            builder.addRoute("8000::", 1)
            TunnelLog.info("IPv6 sinkhole enabled (pre-Android 10)")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            builder.allowFamily(OsConstants.AF_INET)
        }

        runCatching {
            builder.addDisallowedApplication(vpnService.packageName)
        }.onFailure {
            TunnelLog.error("addDisallowedApplication failed: ${it.message}")
        }

        return builder
    }

    private fun addInternetRoutes(
        builder: VpnService.Builder,
        hop: HopNodeProfile,
        context: TunnelConnectContext,
    ) {
        val lanCidrs = LocalLanCidrs.detect(vpnService)
        val bypass = IPv4Cidr.lanBypassHoles(lanCidrs)
        if (lanCidrs.isNotEmpty()) {
            TunnelLog.info("LAN bypass ${lanCidrs.joinToString()}")
        }
        TunnelLog.info("Bypass multicast ${IPv4Cidr.MULTICAST}")

        val overlaySubnet = ChainTopology.overlaySubnet(context.chainId)
        val entryIp = runCatching { IPv4Only.resolveHost(hop.trimmedHost) }.getOrNull()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addSplitDefaultIPv4(builder)
            for (cidr in bypass) {
                builder.excludeRoute(android.net.IpPrefix(Inet4Address.getByName(cidr.dotted), cidr.prefixLength))
            }
            entryIp?.let { ip ->
                builder.excludeRoute(android.net.IpPrefix(Inet4Address.getByName(ip), 32))
                TunnelLog.info("Entry hop excluded from routes: $ip")
            }
            builder.excludeRoute(android.net.IpPrefix(Inet4Address.getByName(overlaySubnet), 24))
            return
        }

        val routes = IPv4Cidr.complement(bypass)
        if (routes.isEmpty()) {
            TunnelLog.info("LAN bypass unavailable; using full-tunnel routes")
            addSplitDefaultIPv4(builder)
        } else {
            for (route in routes) {
                builder.addRoute(route.dotted, route.prefixLength)
            }
        }
    }

    private fun addSplitDefaultIPv4(builder: VpnService.Builder) {
        // 0.0.0.0/0 is ignored on some Android TV / kernel 4.19 netd builds.
        builder.addRoute("0.0.0.0", 1)
        builder.addRoute("128.0.0.0", 1)
    }

    private fun protectSocket(socket: Socket): Boolean {
        synchronized(protectedSockets) {
            protectedSockets.add(WeakReference(socket))
        }
        val protected = vpnService.protect(socket)
        if (!protected) {
            TunnelLog.error("VPN protect() failed for entry SSH socket")
        }
        return protected
    }

    private fun reprotectSockets() {
        val sockets = synchronized(protectedSockets) {
            protectedSockets.mapNotNull { it.get() }.filter { !it.isClosed }
        }
        for (socket in sockets) {
            if (!vpnService.protect(socket)) {
                TunnelLog.error("VPN protect() failed after establish")
            }
        }
    }

    private suspend fun establishInterface(builder: VpnService.Builder): ParcelFileDescriptor {
        val retryDelaysMs = listOf(0L, 300L, 700L, 1500L)
        for ((attempt, delayMs) in retryDelaysMs.withIndex()) {
            if (delayMs > 0) {
                TunnelLog.info("VPN establish retry ${attempt + 1}/${retryDelaysMs.size} after ${delayMs}ms")
                delay(delayMs)
            }
            builder.establish()?.let { return it }
        }
        throw TunnelCoordinatorException(VPN_INTERFACE_UNAVAILABLE)
    }

    fun startRelay(tunInterface: ParcelFileDescriptor) {
        val stream = sshSession?.chainStream
            ?: throw TunnelCoordinatorException("SSH chain stream is not available.")

        val input = FileInputStream(tunInterface.fileDescriptor)
        val output = FileOutputStream(tunInterface.fileDescriptor)
        val engine = IPTunnelEngine(stream, input, output, dropNonIPv4 = sinkholeIPv6)
        engine.onFailure = { message -> handleFailure(message) }
        ipEngine = engine
        engine.start()
        TunnelLog.info("L3 iptunnel engine running")
    }

    fun stop() {
        ipEngine?.stop()
        ipEngine = null

        runCatching { tunInterface?.close() }
        tunInterface = null

        synchronized(protectedSockets) { protectedSockets.clear() }

        val session = sshSession
        sshSession = null
        session?.chainStream?.close()
        runCatching { session?.client?.disconnect() }
    }

    private fun handleFailure(message: String) {
        TunnelLog.error(message)
        onSessionFailure?.invoke(message)
    }
}

const val VPN_INTERFACE_UNAVAILABLE =
    "Could not establish VPN interface. If another VPN is active, disconnect it or confirm the system prompt to switch, then try again."

class TunnelCoordinatorException(message: String) : Exception(message)

object TunnelBootstrap {
    const val HOP_KEY = "hop"
    const val CONTEXT_KEY = "context"

    fun hopJson(hop: HopNodeProfile): String = Json.encodeToString(hop)

    fun contextJson(context: TunnelConnectContext): String = Json.encodeToString(context)
}
