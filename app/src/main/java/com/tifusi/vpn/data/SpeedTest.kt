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

/** A download speed test. Blocking; run it off the main thread. */
object SpeedTest {
    private const val URL_DOWN = "https://speed.cloudflare.com/__down?bytes=50000000"
    private const val MAX_MS = 8_000L

    class Result(val mbps: Double, val throughVpn: Boolean)

    /**
     * Downloads for up to [MAX_MS] and reports megabits per second. With a REALITY/VLESS or
     * Hysteria2 tunnel up, the download goes through the core's loopback SOCKS inbound (the app is
     * excluded from its own VpnService); otherwise it runs on whatever network the app has, which
     * for IKEv2 is the tunnel too.
     */
    fun run(onProgress: (Double) -> Unit = {}): Result? = runCatching {
        val viaCore = XrayVpnService.status.value is XrayStatus.Running
        val connection = if (viaCore) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? =
                    if (requestingHost == "127.0.0.1" || requestingSite?.hostAddress == "127.0.0.1") {
                        PasswordAuthentication(XrayConfig.localSocksUser, XrayConfig.localSocksPass.toCharArray())
                    } else null
            })
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.LOCAL_SOCKS_PORT))
            URL(URL_DOWN).openConnection(proxy) as HttpURLConnection
        } else {
            URL(URL_DOWN).openConnection() as HttpURLConnection
        }
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            val start = System.nanoTime()
            var total = 0L
            var lastReport = start
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    val now = System.nanoTime()
                    if (now - lastReport > 300_000_000L) {
                        lastReport = now
                        onProgress(mbps(total, now - start))
                    }
                    if ((now - start) / 1_000_000 > MAX_MS) break
                }
            }
            val elapsed = System.nanoTime() - start
            if (total < 100_000) null else Result(mbps(total, elapsed), throughVpn = viaCore || vpnActiveElsewhere())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun mbps(bytes: Long, nanos: Long) = bytes * 8.0 / (nanos / 1e9) / 1e6

    /** IKEv2 runs as the platform's VPN, which covers this app as well. */
    private fun vpnActiveElsewhere(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && (it.name.startsWith("ipsec") || it.name.startsWith("tun")) }
    }.getOrDefault(false)
}
