package com.aengix.hopper.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

object IPv4Only {
    private val IPV4_LITERAL = Regex("""\d+\.\d+\.\d+\.\d+""")

    fun resolveHost(host: String): String {
        if (IPV4_LITERAL.matches(host)) return host
        val ipv4 = InetAddress.getAllByName(host)
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?: throw IllegalArgumentException("No IPv4 address for $host")
        return ipv4.hostAddress ?: throw IllegalArgumentException("No IPv4 address for $host")
    }

    fun resolveAddress(host: String): Inet4Address {
        val address = InetAddress.getByName(resolveHost(host))
        require(address is Inet4Address) { "No IPv4 address for $host" }
        return address
    }

    fun socketAddress(host: String, port: Int): InetSocketAddress =
        InetSocketAddress(resolveAddress(host), port)

    fun bindAny(socket: Socket) {
        socket.bind(InetSocketAddress(Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)), 0))
    }

    fun connectSocket(socket: Socket, host: String, port: Int, timeoutMs: Int = 60_000) {
        if (!socket.isBound) bindAny(socket)
        socket.connect(socketAddress(host, port), timeoutMs)
    }

    fun socketFactory(onProtect: ((Socket) -> Boolean)? = null): SocketFactory {
        return object : SocketFactory() {
            private fun openSocket(): Socket =
                if (onProtect != null) ProtectedSocket(onProtect) else Socket()

            override fun createSocket(): Socket = openSocket()

            override fun createSocket(host: String, port: Int): Socket =
                openSocket().also { connectSocket(it, host, port) }

            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                openSocket().also {
                    val local = localHost as? Inet4Address ?: resolveAddress("0.0.0.0")
                    it.bind(InetSocketAddress(local, localPort))
                    connectSocket(it, host, port)
                }

            override fun createSocket(address: InetAddress, port: Int): Socket {
                val host = when (address) {
                    is Inet4Address -> address.hostAddress ?: address.hostName
                    else -> resolveHost(address.hostName)
                }
                return createSocket(host, port)
            }

            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int,
            ): Socket {
                val host = when (address) {
                    is Inet4Address -> address.hostAddress ?: address.hostName
                    else -> resolveHost(address.hostName)
                }
                val local = localAddress as? Inet4Address ?: resolveAddress("0.0.0.0")
                return openSocket().also {
                    it.bind(InetSocketAddress(local, localPort))
                    connectSocket(it, host, port)
                }
            }
        }
    }

    fun isIPv4Packet(packet: ByteArray, length: Int = packet.size): Boolean {
        if (length < 1) return false
        return (packet[0].toInt() ushr 4) == 4
    }

    private class ProtectedSocket(
        private val onProtect: (Socket) -> Boolean,
    ) : Socket() {
        init {
            applyProtect()
        }

        private fun applyProtect() {
            onProtect(this)
        }

        override fun bind(bindpoint: SocketAddress?) {
            super.bind(bindpoint)
            applyProtect()
        }

        override fun connect(endpoint: SocketAddress) {
            connect(endpoint, 0)
        }

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if (!isBound) {
                bindAny(this)
            }
            applyProtect()
            super.connect(endpoint, timeout)
            applyProtect()
        }
    }
}
