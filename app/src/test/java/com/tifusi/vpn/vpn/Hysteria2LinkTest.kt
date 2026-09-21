package com.tifusi.vpn.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Hysteria2LinkTest {

    // Exactly what the panel's subscription hands out.
    private val panelLink = "hysteria2://1aa9268a-4c1f-48e0-90be-31c9df02dd6e@tifusi.gilangard.ir:8801" +
        "?sni=tifusi.gilangard.ir&obfs=salamander&obfs-password=86af0ec44277b4867ca9e49e#Hysteria2"

    @Test
    fun parsesThePanelsLink() {
        val link = Hysteria2Link.parse(panelLink)
        assertEquals("1aa9268a-4c1f-48e0-90be-31c9df02dd6e", link.auth)
        assertEquals("tifusi.gilangard.ir", link.address)
        assertEquals("8801", link.port)
        assertEquals("tifusi.gilangard.ir", link.sni)
        assertEquals("86af0ec44277b4867ca9e49e", link.obfsPassword)
        assertFalse(link.insecure)
        assertEquals("Hysteria2", link.remark)
    }

    @Test
    fun acceptsTheShortSchemeAPortRangeAndIpv6() {
        val link = Hysteria2Link.parse("hy2://secret@[2001:db8::1]:5000-6000/?insecure=1")
        assertEquals("2001:db8::1", link.address)
        assertEquals("5000-6000", link.port)
        assertEquals("[2001:db8::1]:5000-6000", link.server)
        assertTrue(link.insecure)
        assertNull(link.obfsPassword)
    }

    @Test
    fun defaultsToPort443() {
        assertEquals("443", Hysteria2Link.parse("hysteria2://pw@example.com").port)
    }

    @Test
    fun refusesWhatTheClientCouldNotUse() {
        assertThrows(Hysteria2LinkProblem.NotAHysteria2Link::class.java) { Hysteria2Link.parse("vless://x@h:1") }
        assertThrows(Hysteria2LinkProblem.MissingAuth::class.java) { Hysteria2Link.parse("hysteria2://@h:443") }
        assertThrows(Hysteria2LinkProblem.BadPort::class.java) { Hysteria2Link.parse("hysteria2://pw@h:70000") }
        assertThrows(Hysteria2LinkProblem.UnsupportedObfs::class.java) { Hysteria2Link.parse("hysteria2://pw@h:443?obfs=gecko") }
        assertThrows(Hysteria2LinkProblem.MissingObfsPassword::class.java) { Hysteria2Link.parse("hysteria2://pw@h:443?obfs=salamander") }
    }

    @Test
    fun clientConfigDialsTheResolvedIpButKeepsTheNameAsSni() {
        val config = HysteriaClient.buildConfig(Hysteria2Link.parse(panelLink), "167.233.68.133", 10808)
        assertEquals("167.233.68.133:8801", config.getString("server"))
        assertEquals("tifusi.gilangard.ir", config.getJSONObject("tls").getString("sni"))
        assertEquals("86af0ec44277b4867ca9e49e", config.getJSONObject("obfs").getJSONObject("salamander").getString("password"))
        assertEquals("127.0.0.1:10808", config.getJSONObject("socks5").getString("listen"))
        // The keepalive a subscription link cannot carry, which is why the app writes this itself.
        assertEquals("10s", config.getJSONObject("quic").getString("keepAlivePeriod"))
        assertEquals("[2001:db8::1]:8801", HysteriaClient.buildConfig(Hysteria2Link.parse(panelLink), "2001:db8::1", 1).getString("server"))
    }

    @Test
    fun xrayConfigForHysteriaPointsTheProxyAtTheLocalClient() {
        val proxy = JSONObject(XrayConfig.buildForSocks(10808)).getJSONArray("outbounds").getJSONObject(0)
        assertEquals(XrayConfig.TAG_PROXY, proxy.getString("tag"))
        assertEquals("socks", proxy.getString("protocol"))
        val server = proxy.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("127.0.0.1", server.getString("address"))
        assertEquals(10808, server.getInt("port"))
    }
}
