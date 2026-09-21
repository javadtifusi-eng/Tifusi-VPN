package com.tifusi.vpn.data

import com.tifusi.vpn.vpn.VpnProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WireGuard, L2TP and PPTP were removed. A phone updating from an older build can still have
 * profiles of those types saved, and decoding one used to throw — which took every other saved
 * profile down with it, a working IKEv2 server included.
 */
class SavedProfileUpgradeTest {

    private fun saved(protocol: String) = JSONObject()
        .put("id", "sub:$protocol:1.2.3.4")
        .put("name", protocol)
        .put("protocol", protocol)
        .put("serverAddress", "1.2.3.4")

    @Test
    fun removedProtocolsDecodeToNullInsteadOfThrowing() {
        for (gone in listOf("WIREGUARD", "L2TP", "PPTP")) {
            assertNull(gone, saved(gone).toVpnProfileOrNull())
        }
    }

    @Test
    fun survivingProtocolsStillDecode() {
        val profile = saved("IKEV2").toVpnProfileOrNull()
        assertNotNull(profile)
        assertEquals(VpnProtocol.IKEV2, profile!!.protocol)
    }

    @Test
    fun oneStaleEntryDoesNotCostTheRest() {
        val stored = listOf(saved("L2TP"), saved("IKEV2"), saved("WIREGUARD"))
        val loaded = stored.mapNotNull { it.toVpnProfileOrNull() }
        assertEquals(listOf(VpnProtocol.IKEV2), loaded.map { it.protocol })
    }
}
