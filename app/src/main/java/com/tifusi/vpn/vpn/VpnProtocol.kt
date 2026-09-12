package com.tifusi.vpn.vpn

enum class VpnProtocol {
    IKEV2,
    WIREGUARD,
    L2TP,
    PPTP;

    val supportsInAppToggle: Boolean
        get() = this == IKEV2 || this == WIREGUARD
}

enum class Ikev2AuthType {
    /** Pre-shared key. The only mode that cannot pin a server CA. */
    PSK,

    /** EAP username/password, optionally validated against a pinned server root CA. */
    USERNAME_PASSWORD,

    /** Machine certificate (RSA signature): client cert + private key, plus server root CA. */
    CERTIFICATE,
}
