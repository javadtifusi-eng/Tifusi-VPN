package com.tifusi.vpn.vpn

enum class VpnProtocol {
    IKEV2,

    /** VLESS (REALITY first) through the embedded core, from a vless:// share link. */
    VLESS;

    val supportsInAppToggle: Boolean
        get() = true
}

enum class Ikev2AuthType {
    /** Pre-shared key. The only mode that cannot pin a server CA. */
    PSK,

    /** EAP username/password, optionally validated against a pinned server root CA. */
    USERNAME_PASSWORD,

    /** Machine certificate (RSA signature): client cert + private key, plus server root CA. */
    CERTIFICATE,
}
