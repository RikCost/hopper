package com.aengix.hopper.data

import android.net.Uri
import java.net.Inet4Address
import java.net.NetworkInterface

data class HopperLanInvite(
    val ip: String,
    val port: Int,
    val fingerprint: String,
    val url: String,
) {
    companion object {
        const val SCHEME = "hopperconf"
        const val HOST = "recv"
        const val CURRENT_VERSION = 1
        const val MAX_FRAME_BYTES = 2_000_000

        fun make(ip: String, port: Int, fingerprint: String): HopperLanInvite {
            val fp = fingerprint.lowercase()
            val url = "$SCHEME://$HOST?ip=$ip&port=$port&fp=$fp&v=$CURRENT_VERSION"
            return HopperLanInvite(ip, port, fp, url)
        }

        fun parse(text: String): HopperLanInvite? {
            val trimmed = text.trim()
            val schemeSep = trimmed.indexOf("://")
            if (schemeSep < 0) return null
            if (trimmed.substring(0, schemeSep).lowercase() != SCHEME) return null
            val rest = trimmed.substring(schemeSep + 3)
            val queryIndex = rest.indexOf('?')
            val hostPart = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
            if (hostPart.lowercase() != HOST) return null
            if (queryIndex < 0) return null
            val params = rest.substring(queryIndex + 1).split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = pair.substring(0, eq)
                val value = runCatching {
                    java.net.URLDecoder.decode(pair.substring(eq + 1), Charsets.UTF_8.name())
                }.getOrNull() ?: return@mapNotNull null
                key to value
            }.toMap()
            val ip = params["ip"]?.trim().orEmpty()
            if (!isIPv4(ip)) return null
            val port = params["port"]?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val fp = params["fp"]?.trim()?.lowercase().orEmpty()
            if (!isFingerprint(fp)) return null
            val version = params["v"]?.toIntOrNull() ?: 0
            if (version != CURRENT_VERSION) return null
            return HopperLanInvite(ip, port, fp, trimmed)
        }

        fun parse(uri: Uri): HopperLanInvite? = parse(uri.toString())

        private fun isIPv4(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                val n = part.toIntOrNull() ?: return@all false
                n in 0..255 && part == n.toString()
            }
        }

        private fun isFingerprint(fp: String): Boolean =
            fp.length == 64 && fp.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

object HopperLanWire {
    const val TYPE = "t"
    const val PAYLOAD = "p"
    const val SUMMARY = "s"
    const val ERROR = "e"
    const val VERSION = "v"

    const val HELLO = "hello"
    const val READY = "ready"
    const val ITEM = "item"
    const val OK = "ok"
    const val ERR = "err"
    const val BYE = "bye"
}

object HopperLanAddress {
    fun advertisedIPv4(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        var best: Pair<Int, String>? = null
        for (iface in ifaces) {
            val usable = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!usable) continue
            val name = iface.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("utun") || name.startsWith("ppp")
                || name.startsWith("ipsec") || name.startsWith("rmnet")
            ) {
                continue
            }
            for (addr in iface.inetAddresses) {
                val ipv4 = addr as? Inet4Address ?: continue
                if (ipv4.isLoopbackAddress || ipv4.isAnyLocalAddress || ipv4.isMulticastAddress) continue
                val ip = ipv4.hostAddress ?: continue
                if (ip.startsWith("127.")) continue
                var score = 0
                if (name.startsWith("wlan") || name.startsWith("en") || name.startsWith("eth")) score += 50
                if (name.startsWith("ap") || name.startsWith("swlan") || name.contains("softap")) score += 40
                if (ip.startsWith("192.168.") || ip.startsWith("10.")) score += 30
                val second = ip.split('.').getOrNull(1)?.toIntOrNull()
                if (ip.startsWith("172.") && second != null && second in 16..31) score += 25
                if (ip.startsWith("169.254.")) score -= 50
                if (best == null || score > best.first) best = score to ip
            }
        }
        return best?.second
    }
}
