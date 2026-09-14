package com.tifusi.vpn.data

import com.tifusi.vpn.vpn.VlessLinkTest
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionVlessTest {

    @Test
    fun importsVlessLinksAlongsideOtherArrays() {
        val json = JSONObject()
            .put("ikev2", org.json.JSONArray())
            .put("l2tp", org.json.JSONArray())
            .put(
                "vless",
                org.json.JSONArray()
                    .put(VlessLinkTest.REALITY_LINK)
                    // The same inbound twice must not produce two list entries with one id.
                    .put(VlessLinkTest.REALITY_LINK)
                    // Unusable links are skipped rather than failing the import.
                    .put("vless://broken")
                    .put("vless://6e755007-0a65-4e12-859e-000000000000@example.com:443?type=kcp#kcp"),
            )

        val profiles = SubscriptionClient.parseProfiles(json)

        assertEquals(1, profiles.size)
        val profile = profiles.single()
        assertEquals("sub:vless:80.240.21.83:8443:Reality", profile.id)
        assertTrue(profile.id.startsWith(SubscriptionClient.ID_PREFIX))
        assertEquals("Reality", profile.name)
        assertEquals(VpnProtocol.VLESS, profile.protocol)
        assertEquals("80.240.21.83", profile.serverAddress)
        assertEquals(VlessLinkTest.REALITY_LINK, profile.vlessLink)
    }

    @Test
    fun emptyArraysMeanNoServers() {
        val json = JSONObject().put("ikev2", org.json.JSONArray()).put("vless", org.json.JSONArray())
        assertTrue(SubscriptionClient.parseProfiles(json).isEmpty())
        // Panels from before VLESS send no vless key at all.
        assertTrue(SubscriptionClient.parseProfiles(JSONObject()).isEmpty())
    }

    @Test
    fun profileJsonKeepsLinkAndReadsOldData() {
        val profile = VpnProfile(
            id = "sub:vless:80.240.21.83:8443:Reality",
            name = "Reality",
            protocol = VpnProtocol.VLESS,
            serverAddress = "80.240.21.83",
            vlessLink = VlessLinkTest.REALITY_LINK,
        )
        assertEquals(profile, profile.toJson().toVpnProfile())

        // A profile saved by a version without VLESS support.
        val old = JSONObject()
            .put("id", "sub:ikev2:1.2.3.4")
            .put("name", "Old")
            .put("protocol", "IKEV2")
            .put("serverAddress", "1.2.3.4")
            .put("wireGuardEndpointPort", JSONObject.NULL)
        assertNull(old.toVpnProfile().vlessLink)
    }
}
