package com.tifusi.vpn.vpn

import android.content.Context
import com.tifusi.vpn.data.ConnectionReport
import com.tifusi.vpn.data.ConnectionReporter
import java.io.File

/**
 * Sends the telling part of the built-in engine's log (files/charon.log) to the panel after a
 * failure or disconnect, as "engine_log" reports, so a problem on a customer's phone can be read
 * from the panel instead of asked about. Reports hold 500 characters, so it goes in a few pieces.
 */
object CharonLog {
    private const val PIECES = 4
    private const val PIECE_LENGTH = 480
    private val INTERESTING = Regex(
        "error|fail|unable|establish|delet|DELETE|TUN|tun device|retransmit|timeout|giving up|" +
            "closing|authentication|virtual IP|received .*notify|no route|revoked|trust|constraint|" +
            "public key|signature|verif|received end entity|received issuer|identity|expired|not valid",
        RegexOption.IGNORE_CASE,
    )
    // Noise that would crowd the telling lines out of the 2 KB that reach the panel.
    private val NOISE = Regex("sending cert request|establishing CHILD_SA", RegexOption.IGNORE_CASE)

    fun report(context: Context, reason: String) {
        val app = context.applicationContext
        // charon writes the last lines while it shuts down.
        Thread {
            Thread.sleep(3000)
            val file = File(app.filesDir, "charon.log")
            if (!file.exists()) return@Thread
            val lines = runCatching { file.readLines() }.getOrNull() ?: return@Thread
            // Timestamps and thread ids only cost space: "Sep 27 17:48:04 06[IKE] x" -> "[IKE] x".
            val picked = lines.takeLast(400)
                .filter { INTERESTING.containsMatchIn(it) && !NOISE.containsMatchIn(it) }
                .map { it.replace(Regex("^.*?\\d\\d\\[(\\w+)]"), "[$1]").trim() }
                .takeLast(40)
            val text = picked.joinToString(" | ")
            if (text.isEmpty()) return@Thread
            text.takeLast(PIECES * PIECE_LENGTH).chunked(PIECE_LENGTH).forEachIndexed { i, piece ->
                ConnectionReporter.record(
                    app,
                    ConnectionReport(
                        at = System.currentTimeMillis() + i,
                        event = "engine_log",
                        result = reason.take(32),
                        detail = piece,
                        protocol = VpnProtocol.IKEV2.name,
                        durationMs = null,
                        network = "",
                        carrier = "",
                        simCarrier = "",
                    ),
                    listOf(ConnectionReporter.UPLOAD_SOON_MS),
                )
            }
        }.start()
    }
}
