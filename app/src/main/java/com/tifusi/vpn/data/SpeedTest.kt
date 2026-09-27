package com.tifusi.vpn.data

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A download and upload speed test through the tunnel. Blocking; run it off the main thread.
 *
 * One connection for a few seconds reads well below the link: on a path with a long round trip a
 * single TCP stream is still growing its window when the test ends, and the slow first second is
 * averaged in. So each direction runs [STREAMS] connections at once and only counts what moves
 * after [WARMUP_MS], the way speed test sites do.
 */
object SpeedTest {
    private const val URL_DOWN = "https://speed.cloudflare.com/__down?bytes=100000000"
    private const val URL_UP = "https://speed.cloudflare.com/__up"
    private const val STREAMS = 4
    private const val WARMUP_MS = 1_500L
    private const val PHASE_MS = 10_000L
    private const val MIN_BYTES = 100_000L

    enum class Phase { DOWNLOAD, UPLOAD }

    /** Megabits per second each way; null for a direction that moved too little to measure. */
    class Result(val downMbps: Double?, val upMbps: Double?, val throughVpn: Boolean)

    fun run(onProgress: (Phase, Double) -> Unit = { _, _ -> }): Result? {
        val throughVpn = vpnActiveElsewhere()
        val down = measure(Phase.DOWNLOAD, onProgress, ::downloadStream)
        val up = measure(Phase.UPLOAD, onProgress, ::uploadStream)
        if (down == null && up == null) return null
        return Result(down, up, throughVpn)
    }

    /**
     * Runs [STREAMS] copies of [stream] until [PHASE_MS] is up. Each adds what it moves to the
     * shared counter; the rate is taken from the counter's value at the end of the warm-up to its
     * value at the end.
     */
    private fun measure(phase: Phase, onProgress: (Phase, Double) -> Unit, stream: (AtomicLong, Long) -> Unit): Double? {
        val moved = AtomicLong()
        val start = System.nanoTime()
        val deadline = start + PHASE_MS * 1_000_000
        val workers = List(STREAMS) { thread(isDaemon = true) { runCatching { stream(moved, deadline) } } }

        val warmEnd = start + WARMUP_MS * 1_000_000
        var baseBytes = -1L
        var baseAt = 0L
        while (System.nanoTime() < deadline && workers.any { it.isAlive }) {
            Thread.sleep(300)
            val now = System.nanoTime()
            if (baseBytes < 0 && now >= warmEnd) {
                baseBytes = moved.get()
                baseAt = now
            }
            if (baseBytes >= 0 && now > baseAt) onProgress(phase, mbps(moved.get() - baseBytes, now - baseAt))
        }
        val end = System.nanoTime()
        workers.forEach { it.join(2_000) }
        // A path so slow the warm-up never ended still gets a number, from the whole run.
        val bytes = if (baseBytes >= 0) moved.get() - baseBytes else moved.get()
        val nanos = if (baseBytes >= 0) end - baseAt else end - start
        return if (bytes < MIN_BYTES || nanos <= 0) null else mbps(bytes, nanos)
    }

    private fun downloadStream(moved: AtomicLong, deadline: Long) {
        val connection = TunnelHttp.open(URL_DOWN).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            useCaches = false
        }
        try {
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (System.nanoTime() < deadline) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    moved.addAndGet(n.toLong())
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadStream(moved: AtomicLong, deadline: Long) {
        val connection = TunnelHttp.open(URL_UP).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/octet-stream")
            // Streamed, so each write goes out as it is made instead of being buffered whole.
            setChunkedStreamingMode(64 * 1024)
        }
        try {
            // Random bytes, so nothing on the path can shrink them by compressing.
            val chunk = Random.nextBytes(64 * 1024)
            connection.outputStream.use { out ->
                while (System.nanoTime() < deadline) {
                    out.write(chunk)
                    moved.addAndGet(chunk.size.toLong())
                }
            }
            runCatching { connection.responseCode }
        } finally {
            connection.disconnect()
        }
    }

    private fun mbps(bytes: Long, nanos: Long) = bytes * 8.0 / (nanos / 1e9) / 1e6

    /** IKEv2 runs as the platform's VPN, which covers this app as well. */
    private fun vpnActiveElsewhere(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && (it.name.startsWith("ipsec") || it.name.startsWith("tun")) }
    }.getOrDefault(false)
}
