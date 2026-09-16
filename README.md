<p align="center">
  <img src="docs/brand/logo.png" width="180" alt="Tifusi logo" />
</p>

<h1 align="center">TIFUSI VPN</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 11+" />
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin Compose" />
  <a href="https://t.me/javadheydeari"><img src="https://img.shields.io/badge/Support-26A5E4?style=flat-square&logo=telegram&logoColor=white" alt="Telegram support" /></a>
  <a href="https://github.com/javadtifusi-eng/Tifusi-VPN/releases/latest"><img src="https://img.shields.io/github/v/release/javadtifusi-eng/Tifusi-VPN?style=flat-square&label=release&color=22C55E" alt="release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-source--available-DC2626?style=flat-square" alt="license" /></a>
</p>

<p align="center">
  <img src="docs/brand/gb.png" height="14" alt="" /> <b>English</b> &nbsp;·&nbsp; <img src="docs/brand/ir.png" height="14" alt="" /> <a href="README.fa.md">فارسی</a>
</p>

<hr>

An Android app for connecting to and disconnecting from [Tifusi Panel](https://github.com/javadtifusi-eng/Tifusi-Panel) VPN servers in one tap, without going through the phone's VPN settings.
The interface is bilingual (Persian/English) with a dark neon theme.

> **Status:** GitHub Actions compiles the app and builds the APK, but it **has not yet been tested on a real phone against a real server.**

---

## Getting the APK

1. Open the **Actions** tab of this repository and open the latest successful **Build APK** run.
2. Download `tifusi-vpn-debug` from **Artifacts**; it contains `app-debug.apk`.
3. Open the APK on the phone and allow installing from unknown sources.

You can also build with Android Studio: open the folder (Gradle 8.7, JDK 17) and run `Build > Build APK(s)`.

---

## Working with Tifusi Panel

The app follows the panel's own code (`node_agent/ipsec.py`, `subscription/info_page.py`, `links/generator.py`).

| Protocol | In Tifusi Panel | In the app |
|---|---|---|
| **IKEv2** | ✅ Two modes: "eap" (default) and "psk" | ✅ Automatic connect/disconnect (Android 11+) |
| **L2TP/IPsec** | ✅ Shared PSK + username/password | ⚠️ Shows the details and opens the phone's VPN settings |
| **WireGuard** | ❌ Removed from the panel | ✅ Only for WireGuard servers outside the panel |
| **PPTP** | ❌ Not in the panel | ⚠️ Manual entry for other servers only |

### Importing servers from the panel
On the servers screen, enter an **access code** or a **subscription link** and tap "Get servers". All of the user's IKEv2/L2TP servers are added automatically with username, password, PSK and Remote ID, and "Refresh" reads panel changes again.
- **Access code:** looks like `javad7KQ4MP9X` and is shown at the top of the user's subscription page. It is meant for cases where the link can't be sent and has to be read out. Letter case does not matter. A bare code goes to this build's default panel (`tifusi.panelUrl` in `gradle.properties`); for any other panel write `code@panel-domain`, for example `ali7KQ4MP9X@panel.example.com`.
- **Subscription link:** `https://<panel>/sub/<token>` works with any panel.
- **Server certificate** comes from the panel automatically and is placed in the "Server CA certificate" field (the issuer of the server certificate). If the server certificate was renewed and the app can't connect, tap "Refresh".
- **Contact:** the Telegram ID in `tifusi.supportTelegram` in `gradle.properties` is shown on the profile tab.

### Building the app for your own panel
Set `tifusi.panelUrl` in `gradle.properties` to your panel's address, optionally set `tifusi.supportTelegram` to your support ID, and build. Without this the app still works with any panel through a subscription link or `code@domain`.
- Resetting the access key in the panel invalidates both the old link and the old access code.
- Manual server entry remains available for servers outside the panel.

### How IKEv2 connects (matching the panel)
- **eap mode:** the server authenticates with its own certificate and each user signs in with username and password (EAP-MSCHAPv2).
  If the server certificate is self-signed, the panel QR code carries the CA certificate and the app pins it. A publicly trusted certificate (such as Let's Encrypt) needs no CA.
- **psk mode:** shared key only; the panel does not restrict the client identity.
- **Remote ID:** Android's API always uses the server address as the Remote ID. So when the panel's Remote ID differs from the server address, the app **connects to the Remote ID itself**, exactly what the panel's own iOS profile does (`RemoteAddress = remote_id`).
  ⚠️ If the Host address in the panel is a relay server (for example Tifusi Tunnel inside Iran) and the Remote ID is the foreign server's domain, the connection does not go through the relay.
- **Proposals:** the panel server accepts `aes256-sha256-modp2048`, `aes256gcm16-prfsha384-ecp384` and `default`, which overlap with Android's IKEv2 defaults.

### Contract with the panel
The endpoints are defined in the panel's `backend/app/routers/subscription.py`:

```
GET <panel>/sub/<secret token>/app.json?hwid=<device id>
GET <panel>/code/<access code>/app.json?hwid=<device id>
{"v":1, "username":"…", "status":"…", "expire":…, "used_traffic":…, "data_limit":…,
 "ikev2":[{"remark","server","remote_id","username","password","psk"?,"certificate"?}],
 "l2tp":[{"remark","server","username","password","psk"?}]}
```

- For IKEv2, `psk` is sent only in psk mode; the app detects the authentication mode from its presence.
- The app pins `certificate` only when the chain is self-signed; public certificates (such as Let's Encrypt) are checked against the system trust store.
- `hwid` keeps the panel's device limit from counting the same phone again when its mobile IP changes.
- If this contract changes in the panel, `app/src/main/java/com/tifusi/vpn/data/SubscriptionClient.kt` must change too.

---

## IKEv2 authentication and certificates

The form has three modes:

1. **Pre-shared key (PSK):** the panel's psk mode.
2. **Username/password (EAP-MSCHAPv2):** the panel's eap mode. The server CA certificate is required for self-signed certificates.
3. **Client certificate:** not used by the panel; kept for other servers.

### Accepted formats
- **Server CA certificate:** a `.crt` / `.cer` / `.pem` file (PEM or DER) or pasted PEM text.
  The panel's certificate field (server certificate followed by the CA) can be pasted directly; the app finds the CA inside it.
- **Client certificate:** a `.p12` / `.pfx` file with its password, **or** a certificate plus a PKCS#8 private key.

### Checks before saving
- The file really is an X.509 certificate
- **Expiry date** (expired / not yet valid)
- The CA really is a CA; selecting only the server's own certificate is an error
- The `.p12` password is correct and the file contains a private key and a certificate
- The private key can be read (RSA or EC)
- If no server CA was entered, the app warns and asks for confirmation before saving

### Common problems
| Message | Fix |
|---|---|
| Server rejected the connection | In eap mode, check the user's username/password in the panel and the CA certificate. The Remote ID must match the domain in the server certificate. |
| Not connected after 30 seconds | UDP ports 500 and 4500 must be open and the Remote ID must resolve to the server IP. |
| Key is in PKCS#1 format | `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key8.pem` |
| `.p12` password is wrong (but it is correct) | Rebuild it with `openssl pkcs12 -export -legacy …` |

On Android 13 and later, IKE negotiation failures are read from the system; on Android 11 and 12 only a timeout error is shown after 30 seconds.

---

## Project structure

```
app/src/main/java/com/tifusi/vpn/
├── vpn/
│   ├── VpnController.kt        # single connect/disconnect entry point for all protocols
│   ├── Ikev2VpnManager.kt      # VpnManager + Ikev2VpnProfile
│   ├── WireGuardVpnManager.kt  # WireGuard GoBackend
│   ├── LegacyVpnLauncher.kt    # L2TP/PPTP → Android settings
│   ├── CertificateStore.kt     # reading and checking certificates
│   ├── VpnProfileValidator.kt  # checks before saving/connecting
│   └── VpnProfile.kt, VpnProtocol.kt
├── qr/QrConfigParser.kt        # panel QR / panel copy text / WireGuard
├── data/VpnProfileRepository.kt
└── ui/                         # Compose: home, servers, profile, services
```

## Current limitations
- Traffic statistics only for WireGuard; "speed" is not calculated yet.
- Profiles (including passwords) are stored in the app's internal storage without separate encryption. Android backup is disabled for the app.
- On Android 11 and 12 the Remote ID can't be set separately from the address (see Working with Tifusi Panel).
- Newer One UI versions may not offer creating L2TP profiles in settings.
- The current icon is a simplified version of the logo.

---

## License

Tifusi VPN is **source-available, not open source**. You may read the code and install and use the official app releases. Copying any part of the code, modifying and republishing it, rebranding it or selling it requires written permission. See [LICENSE](LICENSE).
