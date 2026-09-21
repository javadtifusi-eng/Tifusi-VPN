package com.tifusi.vpn.vpn

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Runs the official Hysteria2 client as a local SOCKS5 proxy for the embedded Xray core.
 *
 * Xray has no Hysteria2 of its own, but it already owns everything else a tunnel needs here: the
 * `tun` inbound, DNS, per-outbound traffic counters and the delay test. So rather than a second tunnel
 * stack, the Hysteria2 client listens on the loopback and Xray's `proxy` outbound is pointed at it.
 * The client's own connection to the server does not loop back through the tunnel, because the app
 * excludes itself from its own VPN (see XrayVpnService.establishInterface) and this process shares the
 * app's uid.
 *
 * The binary ships as `libhysteria.so` because Android only lets an app execute files from its native
 * library directory.
 */
object HysteriaClient {

    private const val TAG = "HysteriaClient"
    private const val READY_TIMEOUT_MS = 15_000L
    private const val LOG_TAIL = 20

    @Volatile private var process: Process? = null
    private val logTail = ArrayDeque<String>()

    /**
     * Starts the client and returns the SOCKS5 port once it is accepting connections, which with
     * `lazy: false` means it has already authenticated to the server. Throws with the client's own
     * last log line when it does not get that far — usually the most useful message available.
     *
     * [serverIp] is the server already resolved by the caller: a Go binary on Android has no
     * /etc/resolv.conf and cannot reliably resolve names itself. The link's host stays the TLS SNI.
     */
    fun start(context: Context, link: Hysteria2Link, serverIp: String): Int {
        stop()
        val binary = File(context.applicationInfo.nativeLibraryDir, "libhysteria.so")
        if (!binary.canExecute()) throw IllegalStateException("Hysteria2 client is missing from this build")

        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val config = File(context.noBackupFilesDir, "hysteria-client.json")
        config.writeText(buildConfig(link, serverIp, port).toString())

        synchronized(logTail) { logTail.clear() }
        val started = ProcessBuilder(binary.path, "client", "-c", config.path)
            .redirectErrorStream(true)
            .apply {
                environment()["HYSTERIA_DISABLE_UPDATE_CHECK"] = "1"
                environment()["HYSTERIA_LOG_LEVEL"] = "info"
            }
            .start()
        process = started
        drain(started)

        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) {
                throw IllegalStateException(lastLogLine() ?: "Hysteria2 client exited (${started.exitValue()})")
            }
            if (listening(port)) return port
            Thread.sleep(150)
        }
        stop()
        throw IllegalStateException(lastLogLine() ?: "Hysteria2 client did not connect within ${READY_TIMEOUT_MS / 1000} s")
    }

    fun stop() {
        val running = process ?: return
        process = null
        running.destroy()
        if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
    }

    fun isRunning(): Boolean = process?.isAlive == true

    internal fun buildConfig(link: Hysteria2Link, serverIp: String, socksPort: Int): JSONObject = JSONObject().apply {
        val host = if (serverIp.contains(':')) "[$serverIp]" else serverIp
        put("server", "$host:${link.port}")
        put("auth", link.auth)
        put("tls", JSONObject().apply {
            put("sni", link.sni ?: link.address)
            put("insecure", link.insecure)
            link.pinSha256?.let { put("pinSHA256", it) }
        })
        link.obfsPassword?.let {
            put("obfs", JSONObject().put("type", "salamander").put("salamander", JSONObject().put("password", it)))
        }
        // The fix a subscription link cannot carry. Hysteria's default idle timeout is 30 s, and on MCI
        // connections died at almost exactly that on a path that was still alive, each one costing a
        // fresh handshake. QUIC uses the lower of the two ends' values, and only a client config can
        // raise its side and send keepalives — which this one, being ours, can.
        put("quic", JSONObject().put("maxIdleTimeout", "60s").put("keepAlivePeriod", "10s"))
        put("fastOpen", true)
        put("lazy", false)
        put("socks5", JSONObject().put("listen", "127.0.0.1:$socksPort").put("disableUDP", false))
    }

    private fun listening(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200) }
    }.isSuccess

    /** The client's output must be read or it blocks once the pipe fills. */
    private fun drain(started: Process) {
        Thread({
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, line)
                    synchronized(logTail) {
                        logTail.addLast(line)
                        while (logTail.size > LOG_TAIL) logTail.removeFirst()
                    }
                }
            }
        }, "hysteria-log").apply { isDaemon = true }.start()
    }

    /** The client logs errors as structured JSON-ish lines; the message field reads best. */
    private fun lastLogLine(): String? = synchronized(logTail) {
        logTail.lastOrNull { it.contains("ERROR") || it.contains("FATAL") } ?: logTail.lastOrNull()
    }?.let { line ->
        Regex(""""error":\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: line.take(200)
    }
}
