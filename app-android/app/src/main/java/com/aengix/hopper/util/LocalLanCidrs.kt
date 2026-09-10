package com.aengix.hopper.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object LocalLanCidrs {
    private const val MIN_PREFIX = 8
    private const val MAX_PREFIX = 30

    fun detect(context: Context): List<IPv4Cidr> {
        val seen = LinkedHashSet<IPv4Cidr>()
        detectFromConnectivity(context, seen)
        if (seen.isEmpty()) {
            detectFromInterfaces(seen)
        }
        return seen.toList()
    }

    private fun detectFromConnectivity(context: Context, out: MutableSet<IPv4Cidr>) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val isLan =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLan) continue
            val links = cm.getLinkProperties(network)?.linkAddresses ?: continue
            for (link in links) {
                val ipv4 = link.address as? Inet4Address ?: continue
                addIfLan(ipv4, link.prefixLength, out)
            }
        }
    }

    private fun detectFromInterfaces(out: MutableSet<IPv4Cidr>) {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return
        for (iface in ifaces) {
            val usable = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (addr in iface.interfaceAddresses) {
                val ipv4 = addr.address as? Inet4Address ?: continue
                addIfLan(ipv4, addr.networkPrefixLength.toInt(), out)
            }
        }
    }

    private fun addIfLan(ipv4: Inet4Address, prefixLength: Int, out: MutableSet<IPv4Cidr>) {
        if (ipv4.isLoopbackAddress || ipv4.isAnyLocalAddress || ipv4.isMulticastAddress) return
        val host = ipv4.hostAddress ?: return
        val prefix = when {
            prefixLength in MIN_PREFIX..MAX_PREFIX -> prefixLength
            prefixLength == 32 && ipv4.isSiteLocalAddress -> 24
            else -> return
        }
        out += IPv4Cidr.parse(host, prefix)
    }
}
