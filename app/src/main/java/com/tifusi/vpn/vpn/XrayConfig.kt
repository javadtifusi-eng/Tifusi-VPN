package com.tifusi.vpn.vpn

import com.tifusi.vpn.data.TunnelSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the Xray-core JSON for one VLESS server, in the shape v2rayNG uses when it hands the
 * VpnService file descriptor straight to the core (its `v2ray_config_with_tun.json`): a `tun`
 * inbound reads the device's packets, everything goes to the `proxy` outbound except private
 * addresses, and DNS is answered by the core itself through the proxy.
 *
 * Only features this app's pinned core accepts are emitted. In particular `allowInsecure` is not:
 * this Xray version refuses to load any config that sets it.
 */
object XrayConfig {

    const val TAG_TUN = "tun"
    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_DNS_OUT = "dns-out"

    const val MTU = 1500

    /** The resolvers apps are told to use; their queries never leave the device unproxied. */
    val DNS_SERVERS = listOf("8.8.8.8", "1.1.1.1")

    /** Sent direct: LAN, loopback, link-local and CGNAT ranges, so printers and routers stay reachable. */
    val PRIVATE_CIDRS = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
        "::1/128", "fc00::/7", "fe80::/10",
    )

    /**
     * A SOCKS inbound on loopback, for the Speed Test only: this app is excluded from its own tunnel,
     * so its test download goes through the core here. Password-protected with a per-process secret,
     * so other apps cannot use it or tell it apart from a closed port by trying it.
     */
    const val LOCAL_SOCKS_PORT = 10853
    val localSocksUser: String = randomToken()
    val localSocksPass: String = randomToken()

    private fun randomToken(): String {
        val bytes = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // v2rayNG's user level for the tun inbound, with its handshake and idle timeouts.
    private const val USER_LEVEL = 8

    /**
     * [serverAddress] replaces the link's host in the outbound when the caller already resolved a
     * domain: the core's own resolver would send that lookup through the tunnel it is building.
     * TLS and REALITY keep the domain as server name either way.
     */
    fun build(link: VlessLink, serverAddress: String = link.address, settings: TunnelSettings = TunnelSettings()): String =
        assemble(vlessOutbound(link, serverAddress), settings)

    /**
     * The same tunnel with the local Hysteria2 client (HysteriaClient) as the proxy: tun, DNS,
     * routing and counters stay exactly as they are for VLESS, only where `proxy` leads changes.
     */
    fun buildForSocks(socksPort: Int, settings: TunnelSettings = TunnelSettings()): String = assemble(
        JSONObject().put("tag", TAG_PROXY).put("protocol", "socks").put(
            "settings",
            JSONObject().put(
                "servers",
                JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", socksPort)),
            ),
        ),
        settings,
    )

    private fun assemble(proxy: JSONObject, settings: TunnelSettings): String = JSONObject().apply {
        put("log", JSONObject().put("loglevel", settings.logLevel))
        // Per-outbound counters, read by CoreController.queryAllOutboundTrafficStats for the speed readout.
        put("stats", JSONObject())
        put("policy", policy())
        put("inbounds", JSONArray().put(tunInbound(settings.mtu)).put(localSocksInbound()))
        put("outbounds", JSONArray().apply {
            // The first outbound is the default for anything no rule matches, the core's DNS included.
            put(proxy)
            put(JSONObject().put("tag", TAG_DIRECT).put("protocol", "freedom").put("settings", JSONObject()))
            put(
                JSONObject().put("tag", TAG_BLOCK).put("protocol", "blackhole")
                    .put("settings", JSONObject().put("response", JSONObject().put("type", "http"))),
            )
            put(JSONObject().put("tag", TAG_DNS_OUT).put("protocol", "dns"))
        })
        put("dns", dns(settings.dnsServers))
        put("routing", routing(settings.bypassIran))
    }.toString()

    private fun policy() = JSONObject().apply {
        put(
            "levels",
            JSONObject().put(
                USER_LEVEL.toString(),
                JSONObject().put("handshake", 4).put("connIdle", 300).put("uplinkOnly", 1).put("downlinkOnly", 1),
            ),
        )
        put("system", JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true))
    }

    /**
     * The core reads the descriptor from the `xray.tun.fd` variable that CoreController.startLoop
     * sets (Xray proxy/tun/tun_android.go), so the inbound needs no device name or addresses.
     */
    private fun tunInbound(mtu: Int) = JSONObject().apply {
        put("tag", TAG_TUN)
        put("protocol", "tun")
        put("settings", JSONObject().put("name", "xray0").put("mtu", mtu).put("userLevel", USER_LEVEL))
        // routeOnly: the sniffed domain is used for routing, but connections keep their IP target, so
        // the private-address rule still matches and the server does not re-resolve every name.
        put(
            "sniffing",
            JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                .put("routeOnly", true),
        )
    }

    private fun localSocksInbound() = JSONObject().apply {
        put("tag", "local-socks")
        put("listen", "127.0.0.1")
        put("port", LOCAL_SOCKS_PORT)
        put("protocol", "socks")
        put(
            "settings",
            JSONObject().put("auth", "password").put("udp", false).put(
                "accounts",
                JSONArray().put(JSONObject().put("user", localSocksUser).put("pass", localSocksPass)),
            ),
        )
    }

    private fun vlessOutbound(link: VlessLink, serverAddress: String) = JSONObject().apply {
        put("tag", TAG_PROXY)
        put("protocol", "vless")
        val user = JSONObject()
            .put("id", link.uuid)
            .put("encryption", link.encryption)
            .put("level", USER_LEVEL)
        link.flow?.let { user.put("flow", it) }
        put(
            "settings",
            JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject()
                        .put("address", serverAddress)
                        .put("port", link.port)
                        .put("users", JSONArray().put(user)),
                ),
            ),
        )
        put("streamSettings", streamSettings(link))
        put("mux", JSONObject().put("enabled", false))
    }

    internal fun streamSettings(link: VlessLink) = JSONObject().apply {
        put("network", link.network)
        when (link.network) {
            VlessLink.NETWORK_TCP ->
                put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "none")))
            VlessLink.NETWORK_WS ->
                put("wsSettings", hostAndPath(link))
            VlessLink.NETWORK_HTTPUPGRADE ->
                put("httpupgradeSettings", hostAndPath(link))
            VlessLink.NETWORK_XHTTP ->
                put("xhttpSettings", hostAndPath(link).put("mode", link.mode ?: "auto"))
            VlessLink.NETWORK_GRPC -> put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", link.serviceName.orEmpty())
                    .put("multiMode", link.mode == "multi")
                    .put("idle_timeout", 60)
                    .put("health_check_timeout", 20),
            )
        }

        put("security", link.security)
        when (link.security) {
            VlessLink.SECURITY_TLS -> put("tlsSettings", JSONObject().apply {
                link.serverName?.let { put("serverName", it) }
                link.fingerprint?.let { put("fingerprint", it) }
                if (link.alpn.isNotEmpty()) put("alpn", JSONArray(link.alpn))
            })
            VlessLink.SECURITY_REALITY -> put("realitySettings", JSONObject().apply {
                link.serverName?.let { put("serverName", it) }
                // REALITY needs a uTLS fingerprint; chrome is what panels and v2rayNG default to.
                put("fingerprint", link.fingerprint ?: "chrome")
                put("publicKey", link.publicKey.orEmpty())
                link.shortId?.let { put("shortId", it) }
                link.spiderX?.let { put("spiderX", it) }
            })
        }
    }

    private fun hostAndPath(link: VlessLink) = JSONObject()
        .put("host", link.host.orEmpty())
        .put("path", link.path ?: "/")

    /**
     * IPv4 answers only: the tunnel carries IPv6 too (so nothing leaks around it), but most VLESS
     * servers have no IPv6 route, and an AAAA answer would make apps try a path that cannot work.
     */
    private fun dns(servers: List<String>) = JSONObject()
        .put("servers", JSONArray(servers))
        .put("queryStrategy", "UseIPv4")

    private fun routing(bypassIran: Boolean) = JSONObject().apply {
        put("domainStrategy", "AsIs")
        put("rules", JSONArray().apply {
            // Apps' plain DNS reaches the tun like any packet; the core answers it with its own DNS,
            // whose upstream queries take the default (proxy) outbound.
            put(
                JSONObject().put("type", "field").put("inboundTag", JSONArray().put(TAG_TUN))
                    .put("port", "53").put("outboundTag", TAG_DNS_OUT),
            )
            put(JSONObject().put("type", "field").put("ip", JSONArray(PRIVATE_CIDRS)).put("outboundTag", TAG_DIRECT))
            // Route Settings: Iranian sites (.ir) go straight out on the phone's own network.
            if (bypassIran) {
                put(JSONObject().put("type", "field").put("domain", JSONArray().put("domain:ir")).put("outboundTag", TAG_DIRECT))
            }
        })
    }
}
