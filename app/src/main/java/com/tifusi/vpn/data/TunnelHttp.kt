package com.tifusi.vpn.data

import com.tifusi.vpn.vpn.XrayConfig
import com.tifusi.vpn.vpn.XrayStatus
import com.tifusi.vpn.vpn.XrayVpnService
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

/**
 * Opens HTTP connections that go through the VPN. The app is excluded from its own VpnService, so
 * with a REALITY/VLESS tunnel up its requests would otherwise bypass it: they are sent
 * through the core's loopback SOCKS inbound instead. With IKEv2 the platform VPN covers this app
 * too, and a plain connection already goes through the tunnel.
 */
object TunnelHttp {
    private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.LOCAL_SOCKS_PORT))

    init {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                if (requestingHost == "127.0.0.1" || requestingSite?.hostAddress == "127.0.0.1") {
                    PasswordAuthentication(XrayConfig.localSocksUser, XrayConfig.localSocksPass.toCharArray())
                } else null
        })
    }

    /** True while the core carries the tunnel, so requests have to go through its SOCKS inbound. */
    val viaCore: Boolean get() = XrayVpnService.status.value is XrayStatus.Running

    fun open(url: String): HttpURLConnection =
        (if (viaCore) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
}
