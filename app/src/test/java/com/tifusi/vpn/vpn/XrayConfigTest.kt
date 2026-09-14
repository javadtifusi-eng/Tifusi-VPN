package com.tifusi.vpn.vpn

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigTest {

    @Test
    fun realityConfigHasTunInboundProxyAndRouting() {
        val json = JSONObject(build("reality", VlessLinkTest.REALITY_LINK))

        assertEquals("warning", json.getJSONObject("log").getString("loglevel"))

        val inbounds = json.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        val tun = inbounds.getJSONObject(0)
        assertEquals("tun", tun.getString("protocol"))
        assertEquals(XrayConfig.TAG_TUN, tun.getString("tag"))
        assertEquals(1500, tun.getJSONObject("settings").getInt("mtu"))

        val outbounds = json.getJSONArray("outbounds")
        assertEquals(
            listOf(XrayConfig.TAG_PROXY, XrayConfig.TAG_DIRECT, XrayConfig.TAG_BLOCK, XrayConfig.TAG_DNS_OUT),
            (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") },
        )
        assertEquals("freedom", outbounds.getJSONObject(1).getString("protocol"))
        assertEquals("blackhole", outbounds.getJSONObject(2).getString("protocol"))

        val proxy = outbounds.getJSONObject(0)
        assertEquals("vless", proxy.getString("protocol"))
        val server = proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("80.240.21.83", server.getString("address"))
        assertEquals(8443, server.getInt("port"))
        val user = server.getJSONArray("users").getJSONObject(0)
        assertEquals("6e755007-0a65-4e12-859e-000000000000", user.getString("id"))
        assertEquals("none", user.getString("encryption"))
        assertFalse(user.has("flow"))

        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("tcp", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))
        val reality = stream.getJSONObject("realitySettings")
        assertEquals("www.spotify.com", reality.getString("serverName"))
        assertEquals("chrome", reality.getString("fingerprint"))
        assertEquals("AAAAyqS6WJhZzaUbi61pyh5PEcoECKM_j8sDAZGW-Ao", reality.getString("publicKey"))
        assertEquals("933a4982", reality.getString("shortId"))
        assertFalse(stream.has("tlsSettings"))

        val dns = json.getJSONObject("dns")
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), dns.getJSONArray("servers").strings())

        val rules = json.getJSONObject("routing").getJSONArray("rules")
        val dnsRule = rules.getJSONObject(0)
        assertEquals(XrayConfig.TAG_DNS_OUT, dnsRule.getString("outboundTag"))
        assertEquals("53", dnsRule.getString("port"))
        val privateRule = rules.getJSONObject(1)
        assertEquals(XrayConfig.TAG_DIRECT, privateRule.getString("outboundTag"))
        assertTrue(privateRule.getJSONArray("ip").strings().containsAll(listOf("10.0.0.0/8", "192.168.0.0/16", "fc00::/7")))
    }

    @Test
    fun resolvedAddressReplacesHostButKeepsServerName() {
        val link = VlessLink.parse(
            "vless://6e755007-0a65-4e12-859e-000000000000@vpn.example.com:443?security=tls&type=ws&path=/ws&alpn=h2,http/1.1&allowInsecure=1",
        )
        val json = JSONObject(XrayConfig.build(link, serverAddress = "203.0.113.7"))
        writeSample("ws-tls", json.toString())

        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals(
            "203.0.113.7",
            proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"),
        )
        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("/ws", stream.getJSONObject("wsSettings").getString("path"))
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("vpn.example.com", tls.getString("serverName"))
        assertEquals(listOf("h2", "http/1.1"), tls.getJSONArray("alpn").strings())
        // The bundled core refuses to load a config that sets it at all.
        assertFalse(json.toString().contains("allowInsecure"))
    }

    @Test
    fun otherTransportsUseTheirSettingsBlocks() {
        val grpc = streamOf("grpc", "type=grpc&serviceName=tun&mode=multi&security=tls&sni=g.example.com")
        assertTrue(grpc.getJSONObject("grpcSettings").getBoolean("multiMode"))
        assertEquals("tun", grpc.getJSONObject("grpcSettings").getString("serviceName"))

        val upgrade = streamOf("httpupgrade", "type=httpupgrade&host=h.example.com&path=%2Fup&security=tls")
        assertEquals("/up", upgrade.getJSONObject("httpupgradeSettings").getString("path"))
        assertEquals("tls", upgrade.getString("security"))

        // A real-format key, so the sample also loads in `xray run -test`.
        val xhttp = streamOf(
            "xhttp",
            "type=xhttp&path=/x&security=reality&sni=www.spotify.com" +
                "&pbk=AAAAyqS6WJhZzaUbi61pyh5PEcoECKM_j8sDAZGW-Ao&flow=xtls-rprx-vision",
        )
        assertEquals("auto", xhttp.getJSONObject("xhttpSettings").getString("mode"))
        assertEquals("xhttp", xhttp.getString("network"))
    }

    private fun streamOf(name: String, query: String): JSONObject {
        val config = build(name, "vless://6e755007-0a65-4e12-859e-000000000000@example.com:443?$query")
        return JSONObject(config).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
    }

    private fun build(name: String, raw: String): String =
        XrayConfig.build(VlessLink.parse(raw)).also { writeSample(name, it) }

    /** Kept for checking against a real Xray binary (`xray run -test`); the build dir is not in git. */
    private fun writeSample(name: String, config: String) {
        val dir = File("build/xray-configs").apply { mkdirs() }
        File(dir, "$name.json").writeText(config)
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
