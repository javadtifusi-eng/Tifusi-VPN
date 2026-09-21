package com.tifusi.vpn.vpn

/** Bytes received and sent through the current tunnel, for the traffic and speed readouts. */
data class TrafficStats(val rxBytes: Long, val txBytes: Long)
