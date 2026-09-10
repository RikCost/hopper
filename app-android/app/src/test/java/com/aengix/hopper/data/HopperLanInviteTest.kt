package com.aengix.hopper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HopperLanInviteTest {
    @Test
    fun roundTrip() {
        val fp = "a".repeat(64)
        val invite = HopperLanInvite.make("192.168.1.20", 49152, fp)
        val parsed = HopperLanInvite.parse(invite.url)
        assertNotNull(parsed)
        assertEquals("192.168.1.20", parsed!!.ip)
        assertEquals(49152, parsed.port)
        assertEquals(fp, parsed.fingerprint)
        assertEquals("hopperconf://recv?ip=192.168.1.20&port=49152&fp=$fp&v=1", invite.url)
    }

    @Test
    fun rejectsBadFingerprint() {
        val invite = HopperLanInvite.make("192.168.1.20", 49152, "a".repeat(64))
        val bad = invite.url.replace(invite.fingerprint, "zz")
        assertNull(HopperLanInvite.parse(bad))
    }

    @Test
    fun rejectsWrongScheme() {
        assertNull(HopperLanInvite.parse("hopper://recv?ip=192.168.1.20&port=1&fp=${"a".repeat(64)}&v=1"))
    }
}
