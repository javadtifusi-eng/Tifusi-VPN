package com.tifusi.vpn.vpn

import android.content.Context
import libv2ray.Libv2ray

/**
 * "Real delay" of a VLESS server, the way v2rayNG and V2Box measure it: a request made through a
 * throwaway Xray instance built from that server's own config, so a server that accepts TCP but
 * cannot carry traffic (wrong key, filtered SNI) shows as unreachable instead of fast.
 */
object XrayProbe {
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    @Volatile private var ready = false

    private fun ensureCore(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            // Same setup as XrayVpnService: Seq needs a context before any Go call.
            go.Seq.setContext(context.applicationContext)
            Libv2ray.initCoreEnv(context.getDir("xray", Context.MODE_PRIVATE).absolutePath, "")
            ready = true
        }
    }

    /** Milliseconds, or null when the server does not get the request through. Blocking. */
    fun delayMs(context: Context, vlessLink: String): Long? = runCatching {
        ensureCore(context)
        val config = XrayConfig.build(VlessLink.parse(vlessLink))
        Libv2ray.measureOutboundDelay(config, TEST_URL)
    }.getOrNull()?.takeIf { it > 0 }
}
