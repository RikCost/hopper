package com.aengix.hopper.util

data class IPv4Cidr(val network: Int, val prefixLength: Int) {
    init {
        require(prefixLength in 0..32) { "prefixLength $prefixLength" }
    }

    val dotted: String get() = intToDotted(network)

    override fun toString(): String = "$dotted/$prefixLength"

    fun contains(ip: Int): Boolean {
        val shift = 32 - prefixLength
        if (shift >= 32) return true
        return (ip xor network) ushr shift == 0
    }

    fun contains(other: IPv4Cidr): Boolean =
        prefixLength <= other.prefixLength && contains(other.network)

    fun overlaps(other: IPv4Cidr): Boolean = contains(other) || other.contains(this)

    fun subtract(hole: IPv4Cidr): List<IPv4Cidr> {
        if (!overlaps(hole)) return listOf(this)
        if (hole.contains(this)) return emptyList()
        if (prefixLength == 32) return listOf(this)
        val (left, right) = split()
        return left.subtract(hole) + right.subtract(hole)
    }

    fun containsIp(ip: String): Boolean = contains(parseAddress(ip))

    private fun split(): Pair<IPv4Cidr, IPv4Cidr> {
        val next = prefixLength + 1
        val bit = 1 shl (32 - next)
        return IPv4Cidr(network, next) to IPv4Cidr(network or bit, next)
    }

    companion object {
        val ALL = IPv4Cidr(0, 0)
        val MULTICAST = parse("224.0.0.0", 4)
        val LIMITED_BROADCAST = parse("255.255.255.255", 32)
        const val MAX_COMPLEMENT_ROUTES = 100

        fun lanBypassHoles(local: List<IPv4Cidr>): List<IPv4Cidr> =
            (local + MULTICAST + LIMITED_BROADCAST).distinct()

        fun parse(address: String, prefixLength: Int): IPv4Cidr {
            val network = networkAddress(parseAddress(address), prefixLength)
            return IPv4Cidr(network, prefixLength)
        }

        fun complement(holes: List<IPv4Cidr>): List<IPv4Cidr> {
            var routes = listOf(ALL)
            for (hole in holes.distinct()) {
                routes = routes.flatMap { it.subtract(hole) }
                if (routes.size > MAX_COMPLEMENT_ROUTES) return emptyList()
            }
            return routes
        }

        fun parseAddress(address: String): Int {
            val parts = address.split('.')
            require(parts.size == 4) { "invalid IPv4 $address" }
            var value = 0
            for (part in parts) {
                val octet = part.toInt()
                require(octet in 0..255) { "invalid IPv4 $address" }
                value = (value shl 8) or octet
            }
            return value
        }

        fun intToDotted(value: Int): String =
            "${(value ushr 24) and 0xff}.${(value ushr 16) and 0xff}." +
                "${(value ushr 8) and 0xff}.${value and 0xff}"

        fun networkAddress(address: Int, prefixLength: Int): Int {
            if (prefixLength <= 0) return 0
            if (prefixLength >= 32) return address
            return address and (-1 shl (32 - prefixLength))
        }
    }
}
