package com.tifusi.vpn.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens HTTP connections for the tunnel checks (ping, speed test). The built-in IKEv2 engine does
 * not exclude this app from its tunnel, so with the VPN up a plain connection already goes through it.
 */
object TunnelHttp {
    fun open(url: String): HttpURLConnection = URL(url).openConnection() as HttpURLConnection
}
