package com.tifusi.vpn.vpn

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Direct probes of a server, outside any tunnel: a TCP handshake or an ICMP echo. Blocking. */
object Pinger {
    /** Time to complete a TCP handshake with host:port, or null when it doesn't. */
    fun tcpMs(host: String, port: Int, timeoutMs: Int): Long? = runCatching {
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        }
    }.getOrNull()

    private val TIME = Regex("""time[=<]([0-9.]+) ?ms""")

    /**
     * One ICMP echo through the system's ping binary, which needs no root on Android, unlike a raw
     * socket. The time is the one ping reports; null when there is no reply in [timeoutMs].
     */
    fun icmpMs(host: String, timeoutMs: Int): Long? = runCatching {
        val seconds = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        val process = ProcessBuilder("ping", "-c", "1", "-W", seconds.toString(), host)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(seconds + 2L, TimeUnit.SECONDS)) process.destroy()
        TIME.find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.let { maxOf(1L, Math.round(it)) }
    }.getOrNull()
}
