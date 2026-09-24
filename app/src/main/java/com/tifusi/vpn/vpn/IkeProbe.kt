package com.tifusi.vpn.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Delay of an IKEv2 server: the time its IKE daemon takes to answer an IKE_SA_INIT, sent to
 * port 500 and, NAT-T style, to 4500 at once; the first answer wins. Any reply carrying our
 * SPI counts (a NO_PROPOSAL_CHOSEN notify too), since it can only come from the daemon itself,
 * so a host that answers ICMP but has no IKE service shows as unreachable.
 */
object IkeProbe {
    private val random = SecureRandom()

    /** Milliseconds, or null when nothing answers. Blocking. */
    fun delayMs(host: String, timeoutMs: Int = 2_500, attempts: Int = 2): Long? = runCatching {
        val address = InetAddress.getByName(host)
        val spi = ByteArray(8).also(random::nextBytes)
        val packet = saInit(spi)
        val natT = ByteArray(4) + packet
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val buffer = ByteArray(2048)
            repeat(attempts) {
                val start = System.nanoTime()
                socket.send(DatagramPacket(packet, packet.size, address, 500))
                socket.send(DatagramPacket(natT, natT.size, address, 4500))
                val deadline = start + timeoutMs * 1_000_000L
                while (System.nanoTime() < deadline) {
                    val reply = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(reply)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val offset = if (reply.port == 4500) 4 else 0
                    if (reply.length >= offset + 28 && (0 until 8).all { buffer[offset + it] == spi[it] }) {
                        return@runCatching ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
                    }
                }
            }
            null
        }
    }.getOrNull()

    /** A plain IKE_SA_INIT: AES-CBC-256, SHA2-256, ECP-256, with a real key share and nonce. */
    private fun saInit(spi: ByteArray): ByteArray {
        val transforms = transform(1, 12, keyBits = 256) + transform(2, 5) + transform(3, 12) + transform(4, 19, last = true)
        val proposal = ByteBuffer.allocate(8 + transforms.size)
            .put(0).put(0).putShort((8 + transforms.size).toShort())
            .put(1).put(1).put(0).put(4).put(transforms).array()
        val sa = payload(next = 34, body = proposal)
        val ke = payload(next = 40, body = ByteBuffer.allocate(4 + 64).putShort(19).putShort(0).put(ecp256Share()).array())
        val nonce = payload(next = 0, body = ByteArray(32).also(random::nextBytes))
        val body = sa + ke + nonce
        return ByteBuffer.allocate(28 + body.size)
            .put(spi).put(ByteArray(8))
            .put(33).put(0x20).put(34).put(0x08).putInt(0).putInt(28 + body.size)
            .put(body).array()
    }

    private fun transform(type: Int, id: Int, keyBits: Int? = null, last: Boolean = false): ByteArray {
        val length = if (keyBits != null) 12 else 8
        val buffer = ByteBuffer.allocate(length)
            .put(if (last) 0 else 3).put(0).putShort(length.toShort())
            .put(type.toByte()).put(0).putShort(id.toShort())
        keyBits?.let { buffer.putShort(0x800E.toShort()).putShort(it.toShort()) }
        return buffer.array()
    }

    private fun payload(next: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + body.size).put(next.toByte()).put(0).putShort((4 + body.size).toShort()).put(body).array()

    private fun ecp256Share(): ByteArray {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public as ECPublicKey
        return fixed32(key.w.affineX.toByteArray()) + fixed32(key.w.affineY.toByteArray())
    }

    private fun fixed32(bytes: ByteArray): ByteArray =
        if (bytes.size >= 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else ByteArray(32 - bytes.size) + bytes
}
