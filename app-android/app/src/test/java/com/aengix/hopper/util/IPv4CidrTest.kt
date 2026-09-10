package com.aengix.hopper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IPv4CidrTest {
    @Test
    fun parse_masksHostBits() {
        assertEquals("192.168.1.0/24", IPv4Cidr.parse("192.168.1.50", 24).toString())
        assertEquals("10.0.0.0/8", IPv4Cidr.parse("10.9.8.7", 8).toString())
    }

    @Test
    fun complement_leavesLanUnrouted() {
        val routes = IPv4Cidr.complement(listOf(IPv4Cidr.parse("192.168.1.50", 24)))
        assertFalse(routes.any { it.containsIp("192.168.1.1") })
        assertFalse(routes.any { it.containsIp("192.168.1.254") })
        assertTrue(routes.any { it.containsIp("192.168.0.1") })
        assertTrue(routes.any { it.containsIp("192.168.2.1") })
        assertTrue(routes.any { it.containsIp("8.8.8.8") })
        assertTrue(routes.any { it.containsIp("1.1.1.1") })
        assertTrue(routes.any { it.containsIp("10.0.0.1") })
        assertTrue(routes.size < IPv4Cidr.MAX_COMPLEMENT_ROUTES)
        assertTrue(routes.any { it == IPv4Cidr.parse("0.0.0.0", 1) })
    }

    @Test
    fun complement_leavesMulticastUnrouted() {
        val routes = IPv4Cidr.complement(IPv4Cidr.lanBypassHoles(listOf(IPv4Cidr.parse("192.168.1.50", 24))))
        assertFalse(routes.any { it.containsIp("192.168.1.1") })
        assertFalse(routes.any { it.containsIp("224.0.0.251") })
        assertFalse(routes.any { it.containsIp("239.255.255.250") })
        assertFalse(routes.any { it.containsIp("255.255.255.255") })
        assertTrue(routes.any { it.containsIp("8.8.8.8") })
        assertTrue(routes.any { it.containsIp("1.1.1.1") })
    }

    @Test
    fun complement_multipleLans() {
        val routes = IPv4Cidr.complement(
            listOf(
                IPv4Cidr.parse("192.168.1.0", 24),
                IPv4Cidr.parse("10.0.0.1", 8),
            ),
        )
        assertFalse(routes.any { it.containsIp("192.168.1.10") })
        assertFalse(routes.any { it.containsIp("10.1.2.3") })
        assertTrue(routes.any { it.containsIp("8.8.8.8") })
        assertTrue(routes.any { it.containsIp("172.16.0.1") })
    }
}
