package com.tifusi.vpn.vpn

data class VpnProfile(
    val id: String,
    val name: String,
    val protocol: VpnProtocol,
    val serverAddress: String,
    val countryName: String? = null,
    val countryFlagEmoji: String? = null,

    // IKEv2
    val ikev2AuthType: Ikev2AuthType = Ikev2AuthType.PSK,
    val remoteIdentifier: String? = null,
    val localIdentifier: String? = null,
    val presharedKey: String? = null,

    // IKEv2 certificates. Server CA is required whenever the server uses a self-signed
    // or private CA; without it Android rejects the server certificate and the tunnel
    // never comes up. Stored PEM-encoded; the PKCS#12 bundle is stored base64-encoded.
    val serverRootCaCertPem: String? = null,
    val userCertPem: String? = null,
    val userPrivateKeyPem: String? = null,
    val pkcs12Base64: String? = null,
    val pkcs12Password: String? = null,

    // Shared by IKEv2 (username/password auth), L2TP, PPTP
    val username: String? = null,
    val password: String? = null,

    // L2TP (adds an outer IPsec PSK on top of username/password)
    val l2tpIpsecPresharedKey: String? = null,

    // WireGuard
    val wireGuardPrivateKey: String? = null,
    val wireGuardPeerPublicKey: String? = null,
    val wireGuardPresharedKey: String? = null,
    val wireGuardAddress: String? = null,
    val wireGuardDnsServers: String? = null,
    val wireGuardEndpointPort: Int? = null,
    val wireGuardAllowedIps: String = "0.0.0.0/0, ::/0",

    // VLESS: the raw vless:// share link, parsed by VlessLink on each connect. [serverAddress] only
    // mirrors its host for display.
    val vlessLink: String? = null,
)
