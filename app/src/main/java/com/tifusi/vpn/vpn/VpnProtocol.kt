package com.tifusi.vpn.vpn

enum class VpnProtocol {
    IKEV2,
    L2TP,
    PPTP,

    /** VLESS (REALITY first) through the embedded Xray core, from a vless:// share link. */
    VLESS;

    val supportsInAppToggle: Boolean
        get() = this == IKEV2 || this == VLESS
}

enum class Ikev2AuthType {
    /** Pre-shared key. The only mode that cannot pin a server CA. */
    PSK,

    /** EAP username/password, optionally validated against a pinned server root CA. */
    USERNAME_PASSWORD,

    /** Machine certificate (RSA signature): client cert + private key, plus server root CA. */
    CERTIFICATE,
}
