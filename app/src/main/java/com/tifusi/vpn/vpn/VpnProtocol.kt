package com.tifusi.vpn.vpn

enum class VpnProtocol {
    IKEV2,

    /** VLESS (REALITY first) through the embedded core, from a vless:// share link. */
    VLESS,

    /** Hysteria2 over UDP, from a hysteria2:// share link: the official client behind the same core. */
    HYSTERIA2;

    val supportsInAppToggle: Boolean
        get() = true

    /** Carried by this app's own VpnService and Xray core, rather than the platform's IKEv2. */
    val runsInCore: Boolean
        get() = this == VLESS || this == HYSTERIA2
}

enum class Ikev2AuthType {
    /** Pre-shared key. The only mode that cannot pin a server CA. */
    PSK,

    /** EAP username/password, optionally validated against a pinned server root CA. */
    USERNAME_PASSWORD,

    /** Machine certificate (RSA signature): client cert + private key, plus server root CA. */
    CERTIFICATE,
}
