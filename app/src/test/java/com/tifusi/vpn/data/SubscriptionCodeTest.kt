package com.tifusi.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionCodeTest {
    // What the panel hands out for username "abolfazl" on the host tifusi.gilangard.ir.
    private val code = "abolfazlMRU22SDY-ORUWM5LTNEXGO2LMMFXGOYLSMQXGS4Q"

    @Test
    fun codeCarriesItsOwnPanel() {
        assertEquals("https://tifusi.gilangard.ir/code/abolfazlMRU22SDY", SubscriptionClient.normalize(code))
    }

    @Test
    fun caseDoesNotMatter() {
        assertEquals(
            "https://tifusi.gilangard.ir/code/abolfazlmru22sdy",
            SubscriptionClient.normalize(code.lowercase()),
        )
    }

    @Test
    fun explicitHostStillWorks() {
        assertEquals(
            "https://panel.example.com/code/abolfazlMRU22SDY",
            SubscriptionClient.normalize("abolfazlMRU22SDY@panel.example.com"),
        )
    }

    @Test
    fun aDashInTheUsernameIsNotAHost() {
        assertNull(SubscriptionClient.splitEmbeddedHost("ali-bobMRU22SDY"))
    }

    @Test
    fun aHostWithAPortRoundTrips() {
        // base32("a.b.example:8443")
        assertEquals("a.b.example:8443", SubscriptionClient.decodeBase32("MEXGELTFPBQW24DMMU5DQNBUGM"))
    }

    private val vless = "vless://6e755007-0a65-4e12-859e-000000000000@example.com:443?type=tcp#one"
    private val hysteria2 = "hysteria2://1aa9268a-4c1f-48e0-90be-31c9df02dd6e@example.com:8801?sni=example.com#two"

    @Test
    fun aStandardSubscriptionFromAnyPanelIsRead() {
        val plain = SubscriptionClient.standardSubscriptionJson("$vless\n$hysteria2\nss://ignored@x:1#no", null)
        assertEquals(2, SubscriptionClient.parseProfiles(plain).size)

        val encoded = java.util.Base64.getEncoder().encodeToString("$vless\n$hysteria2\n".toByteArray())
        assertEquals(2, SubscriptionClient.parseProfiles(SubscriptionClient.standardSubscriptionJson(encoded, null)).size)
    }

    @Test
    fun usageComesFromTheSubscriptionUserinfoHeader() {
        val json = SubscriptionClient.standardSubscriptionJson(vless, "upload=100; download=200; total=1000; expire=1893456000")
        assertEquals(300L, json.getLong("used_traffic"))
        assertEquals(1000L, json.getLong("data_limit"))
        assertEquals(1893456000L, json.getLong("expire"))
    }

    @Test
    fun otherPanelLinksAreKeptAsTheyAre() {
        val pasarguard = "https://talalinks.example.ir/blog/djMsOTY2NSwxNzkw.Huvq147LRr6_nNOXj3gT"
        assertEquals(pasarguard, SubscriptionClient.normalize(pasarguard))
        assertEquals("https://p.example.com/sub/abcdefghijklmnop", SubscriptionClient.normalize("https://p.example.com/sub/abcdefghijklmnop/"))
        assertNull(SubscriptionClient.normalize("https://example.com"))
    }
}
