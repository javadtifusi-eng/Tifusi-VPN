package com.tifusi.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VlessLinkTest {

    @Test
    fun parsesPanelRealityLink() {
        val link = VlessLink.parse(REALITY_LINK)

        assertEquals("6e755007-0a65-4e12-859e-000000000000", link.uuid)
        assertEquals("80.240.21.83", link.address)
        assertEquals(8443, link.port)
        assertEquals("none", link.encryption)
        assertEquals(VlessLink.NETWORK_TCP, link.network)
        assertEquals("none", link.headerType)
        assertEquals(VlessLink.SECURITY_REALITY, link.security)
        assertEquals("www.spotify.com", link.sni)
        assertEquals("chrome", link.fingerprint)
        assertEquals("AAAAyqS6WJhZzaUbi61pyh5PEcoECKM_j8sDAZGW-Ao", link.publicKey)
        assertEquals("933a4982", link.shortId)
        assertEquals("Reality", link.remark)
        assertNull(link.flow)
        assertFalse(link.allowInsecure)
        assertEquals("www.spotify.com", link.serverName)
    }

    @Test
    fun parsesWebSocketTlsWithEncodedValues() {
        val link = VlessLink.parse(
            "vless://c1b2a3d4-0000-4000-8000-123456789abc@cdn.example.com:443" +
                "?type=ws&security=tls&path=%2Fws%3Fed%3D2048&host=front.example.com" +
                "&alpn=h2%2Chttp%2F1.1&fp=firefox&allowInsecure=1&flow=&extra=" +
                "#%D8%B3%D8%B1%D9%88%D8%B1+1",
        )

        assertEquals(VlessLink.NETWORK_WS, link.network)
        assertEquals(VlessLink.SECURITY_TLS, link.security)
        assertEquals("/ws?ed=2048", link.path)
        assertEquals("front.example.com", link.host)
        assertEquals(listOf("h2", "http/1.1"), link.alpn)
        assertTrue(link.allowInsecure)
        // Empty parameters count as absent.
        assertNull(link.flow)
        // '+' is kept literally, only percent-escapes are decoded.
        assertEquals("سرور+1", link.remark)
        // No sni: the host header is the TLS server name.
        assertEquals("front.example.com", link.serverName)
    }

    @Test
    fun normalisesTransportNames() {
        assertEquals(VlessLink.NETWORK_XHTTP, VlessLink.parse(base("type=splithttp&path=/x&mode=packet-up")).network)
        assertEquals(VlessLink.NETWORK_TCP, VlessLink.parse(base("type=raw")).network)
        assertEquals(VlessLink.NETWORK_TCP, VlessLink.parse(base("")).network)
        val grpc = VlessLink.parse(base("type=grpc&serviceName=my%20svc&mode=multi"))
        assertEquals(VlessLink.NETWORK_GRPC, grpc.network)
        assertEquals("my svc", grpc.serviceName)
        assertEquals("multi", grpc.mode)
        assertEquals(VlessLink.NETWORK_HTTPUPGRADE, VlessLink.parse(base("type=httpupgrade")).network)
    }

    @Test
    fun parsesBracketedIpv6AndVisionFlow() {
        val link = VlessLink.parse(
            "vless://6e755007-0a65-4e12-859e-000000000000@[2001:db8::1]:443" +
                "?security=reality&sni=www.microsoft.com&pbk=key&flow=xtls-rprx-vision",
        )
        assertEquals("2001:db8::1", link.address)
        assertEquals(443, link.port)
        assertEquals("xtls-rprx-vision", link.flow)
        assertNull(link.remark)
    }

    @Test
    fun acceptsShortCustomIdsLikeXray() {
        assertEquals("my-user", VlessLink.parse("vless://my-user@example.com:443?security=tls").uuid)
    }

    @Test
    fun rejectsMalformedLinks() {
        expect(VlessLinkProblem.NotAVlessLink, "vmess://abc")
        expect(VlessLinkProblem.NotAVlessLink, "")
        expect(VlessLinkProblem.MissingUuid, "vless://example.com:443")
        expect(VlessLinkProblem.MissingUuid, "vless://@example.com:443")
        expect(VlessLinkProblem.BadUuid, "vless://this-id-is-far-too-long-to-be-a-custom-xray-id@example.com:443?security=tls")
        expect(VlessLinkProblem.BadUuid, "vless://has%20space@example.com:443?security=tls")
        expect(VlessLinkProblem.MissingAddress, "vless://6e755007-0a65-4e12-859e-000000000000@:443")
        expect(VlessLinkProblem.BadPort, "vless://6e755007-0a65-4e12-859e-000000000000@example.com")
        expect(VlessLinkProblem.BadPort, "vless://6e755007-0a65-4e12-859e-000000000000@example.com:0")
        expect(VlessLinkProblem.BadPort, "vless://6e755007-0a65-4e12-859e-000000000000@example.com:70000")
        expect(VlessLinkProblem.BadPort, "vless://6e755007-0a65-4e12-859e-000000000000@example.com:https")
        expect(VlessLinkProblem.UnsupportedNetwork("kcp"), base("type=kcp"))
        expect(VlessLinkProblem.UnsupportedSecurity("xtls"), base("security=xtls"))
        expect(VlessLinkProblem.UnsupportedHeaderType("http"), base("type=tcp&headerType=http"))
        expect(VlessLinkProblem.MissingRealityPublicKey, base("security=reality&sni=www.spotify.com"))
        // An IP address cannot stand in for the REALITY server name.
        expect(
            VlessLinkProblem.MissingRealityServerName,
            "vless://6e755007-0a65-4e12-859e-000000000000@80.240.21.83:8443?security=reality&pbk=key",
        )
    }

    @Test
    fun plaintextOnlyToPrivateAddressesOrWithVlessEncryption() {
        expect(VlessLinkProblem.PlaintextNotAllowed, base("security=none&type=ws"))
        expect(VlessLinkProblem.PlaintextNotAllowed, "vless://6e755007-0a65-4e12-859e-000000000000@80.240.21.83:80")
        VlessLink.parse("vless://6e755007-0a65-4e12-859e-000000000000@192.168.1.10:80")
        VlessLink.parse(base("security=none&encryption=mlkem768x25519plus.native.600s.key"))
    }

    @Test
    fun recognisesIpLiterals() {
        assertTrue(VlessLink.isIpLiteral("80.240.21.83"))
        assertTrue(VlessLink.isIpLiteral("2001:db8::1"))
        assertFalse(VlessLink.isIpLiteral("999.1.1.1"))
        assertFalse(VlessLink.isIpLiteral("www.spotify.com"))
    }

    /** TLS unless [query] says otherwise: the core refuses plaintext VLESS to a public server. */
    private fun base(query: String) =
        "vless://6e755007-0a65-4e12-859e-000000000000@example.com:443?" +
            (if ("security=" in query) query else "security=tls&$query")

    private fun expect(problem: VlessLinkProblem, raw: String) {
        try {
            VlessLink.parse(raw)
            fail("Expected $problem for $raw")
        } catch (e: VlessLinkProblem) {
            assertEquals("for $raw", problem, e)
        }
    }

    companion object {
        /** The panel's live app.json format, with the user id and key replaced. */
        const val REALITY_LINK =
            "vless://6e755007-0a65-4e12-859e-000000000000@80.240.21.83:8443?type=tcp&security=reality" +
                "&encryption=none&headerType=none&sni=www.spotify.com&fp=chrome" +
                "&pbk=AAAAyqS6WJhZzaUbi61pyh5PEcoECKM_j8sDAZGW-Ao&sid=933a4982#Reality"
    }
}
