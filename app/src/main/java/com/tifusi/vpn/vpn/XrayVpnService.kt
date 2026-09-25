package com.tifusi.vpn.vpn

import com.tifusi.vpn.data.AppSettings
import android.content.IntentFilter
import android.content.BroadcastReceiver
import com.tifusi.vpn.data.TunnelSettings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tifusi.vpn.MainActivity
import com.tifusi.vpn.R
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/** Where the current VLESS run is; [runId] ties each state to the [XrayVpnService.start] that caused it. */
sealed interface XrayStatus {
    val runId: Long

    data class Starting(override val runId: Long) : XrayStatus
    data class Running(override val runId: Long) : XrayStatus
    data class Stopped(override val runId: Long) : XrayStatus
    data class Failed(override val runId: Long, val detail: String) : XrayStatus

    /** Android took the VPN away: switched off in Settings, or another VPN app started. */
    data class Revoked(override val runId: Long) : XrayStatus
}

/**
 * Runs VLESS through the embedded Xray core (2dust/AndroidLibXrayLite), wired the way current
 * v2rayNG does it (CoreVpnService + CoreServiceManager.launchCore): this service establishes the
 * TUN interface and passes its descriptor to [CoreController.startLoop], whose `tun` inbound reads
 * packets straight from it. No tun2socks process is involved.
 *
 * The core's own sockets must not loop back into the tunnel. This AAR has no socket-protect
 * callback, so like v2rayNG the app excludes itself from the VPN with addDisallowedApplication;
 * the side effect is that the app's own HTTP requests also bypass the tunnel.
 *
 * Starting and stopping run on one background thread, in order: the core takes a moment to come
 * up, the server domain lookup is network I/O, and a stop queued behind a start must not overtake
 * it. [VpnController] follows progress through [status].
 */
class XrayVpnService : VpnService() {

    /**
     * The newest start command. Stops go through stopSelfResult with the id current when they were
     * requested, so a stop queued just before a new connect cannot end the service that connect needs.
     */
    @Volatile private var latestStartId = 0

    /**
     * Stop on sleep: with the screen off the tunnel is torn down to save battery, and rebuilt for
     * the same server when the screen comes back on. The service stays in the foreground meanwhile,
     * so the app keeps showing the connection and nothing else has to restart it.
     */
    @Volatile private var sleeping = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (AppSettings.load(context).stopOnSleep && tunnel != null) {
                    sleeping = true
                    worker.execute { if (sleeping) stopCore() }
                }
                Intent.ACTION_SCREEN_ON -> if (sleeping) {
                    sleeping = false
                    val link = lastSession(context)?.first ?: return
                    val runId = currentRunId
                    val startId = latestStartId
                    worker.execute { if (runId == currentRunId && tunnel == null) startTunnel(runId, link, startId) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val saved = lastSession(this)
        // Required within seconds of startForegroundService, whatever happens next.
        startInForeground(intent?.getStringExtra(EXTRA_NAME) ?: saved?.second.orEmpty())

        val link = intent?.getStringExtra(EXTRA_LINK)
        if (intent?.action != ACTION_START || link == null) {
            // A restart after Android killed the process (START_STICKY) or an always-on start carries
            // no profile: reconnect to the server the user left connected, never disturbing a live tunnel.
            if (saved != null && tunnel == null) {
                val runId = runIds.incrementAndGet()
                currentRunId = runId
                publish(XrayStatus.Starting(runId))
                worker.execute { startTunnel(runId, saved.first, startId) }
            } else {
                worker.execute { if (tunnel == null) stopTunnel(null, startId) }
            }
            return START_STICKY
        }
        val runId = intent.getLongExtra(EXTRA_RUN_ID, 0)
        worker.execute { startTunnel(runId, link, startId) }
        return START_STICKY
    }

    override fun onRevoke() {
        clearSession(this)
        val startId = latestStartId
        worker.execute { stopTunnel(XrayStatus.Revoked(currentRunId), startId) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (instance === this) instance = null
        // Destroyed without a stop (e.g. by the system): the core must not keep reading a descriptor
        // nobody owns, and the controller must stop showing "Connected".
        if (tunnel != null) {
            worker.execute {
                stopCore()
                publish(XrayStatus.Stopped(currentRunId))
            }
        }
        super.onDestroy()
    }

    /** Runs on [worker]. */
    private fun startTunnel(runId: Long, rawLink: String, startId: Int) {
        if (runId != currentRunId) {
            // A stop or a newer start came first. If this is still the newest start command, nothing
            // else will end the service, so it ends here instead of idling in the foreground.
            stopTunnel(null, startId)
            return
        }
        // Switching servers: the previous core and interface go first.
        stopCore()

        val settings = AppSettings.load(this)
        val failure: String? = try {
            // Either way the server is resolved now, on the phone's own network: once the tunnel is
            // up the lookup would have to go through the tunnel it is building.
            val config = if (Hysteria2Link.isHysteria2(rawLink)) {
                val link = Hysteria2Link.parse(rawLink)
                val serverIp = if (VlessLink.isIpLiteral(link.address)) link.address else resolve(link.address)
                // Started before the interface exists, so a server that refuses us never gets a
                // tunnel brought up only to be torn down again.
                XrayConfig.buildForSocks(HysteriaClient.start(this, link, serverIp), settings)
            } else {
                val link = VlessLink.parse(rawLink)
                val serverAddress = if (VlessLink.isIpLiteral(link.address)) link.address else resolve(link.address)
                XrayConfig.build(link, serverAddress, settings)
            }

            if (prepare(this) != null) {
                "VPN permission was withdrawn"
            } else {
                val fd = establishInterface(settings)
                if (fd == null) {
                    "Android refused to create the VPN interface (is another app set as always-on VPN?)"
                } else {
                    synchronized(coreLock) {
                        tunnel = fd
                        resetTraffic()
                        // The descriptor stays owned by [tunnel]; the core only borrows its number.
                        coreController().startLoop(config, fd.fd)
                        if (coreController().isRunning) null else "Xray core did not start"
                    }
                }
            }
        } catch (e: VlessLinkProblem) {
            "Invalid link: ${e.message}"
        } catch (e: Hysteria2LinkProblem) {
            "Invalid link: ${e.message}"
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }

        if (failure != null) {
            Log.w(TAG, "Tunnel start failed: $failure")
            stopTunnel(XrayStatus.Failed(runId, failure), startId)
        } else if (runId == currentRunId) {
            publish(XrayStatus.Running(runId))
        }
    }

    private fun resolve(host: String): String {
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw IllegalStateException("Could not resolve server $host")
        }
        // The config's DNS answers IPv4 only; prefer the same family for the server itself.
        val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        return chosen.hostAddress ?: throw IllegalStateException("Could not resolve server $host")
    }

    private fun establishInterface(settings: TunnelSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(settings.mtu)
            // v2rayNG's default interface addresses (VpnInterfaceAddressConfig.OPTION_1).
            .addAddress(TUN_ADDRESS_V4, 30)
            .addRoute("0.0.0.0", 0)
            // IPv6 is routed in too, so it cannot leak around the tunnel on dual-stack networks.
            .addAddress(TUN_ADDRESS_V6, 126)
            .addRoute("::", 0)
        settings.dnsServers.forEach { builder.addDnsServer(it) }
        builder.addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish()
    }

    /**
     * Runs on [worker]. [finalStatus] is published only if no newer run has started since. The
     * notification goes only when the service really ends, i.e. no start command newer than
     * [startId] arrived; otherwise that newer start still needs the service in the foreground.
     */
    private fun stopTunnel(finalStatus: XrayStatus?, startId: Int) {
        sleeping = false
        stopCore()
        if (finalStatus != null && finalStatus.runId == currentRunId) publish(finalStatus)
        if (stopSelfResult(startId)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startInForeground(serverName: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_vpn),
                    // Low: a persistent status entry, never a sound or a heads-up.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (serverName.isBlank()) getString(R.string.notification_vless_active)
                else getString(R.string.notification_vless_server, serverName),
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    /** Only [Libv2ray.newCoreController] calls these, from inside startLoop/stopLoop. */
    private class CoreCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long {
            Log.i(TAG, "core status $code: $message")
            return 0
        }
    }

    companion object {
        private const val TAG = "XrayVpnService"
        private const val ACTION_START = "com.tifusi.vpn.action.START_VLESS"
        private const val EXTRA_LINK = "link"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_RUN_ID = "run_id"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1001
        private const val TUN_ADDRESS_V4 = "10.10.14.1"
        private const val TUN_ADDRESS_V6 = "fc00::10:10:14:1"
        private const val SESSION_PREFS = "xray_session"
        private const val SESSION_LINK = "link"
        private const val SESSION_NAME = "name"

        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "xray-tunnel").apply { isDaemon = true }
        }

        /** Guards the core and [tunnel] between [worker] and the UI ticker reading traffic. */
        private val coreLock = Any()
        private val runIds = AtomicLong(0)

        @Volatile private var currentRunId = 0L
        @Volatile private var instance: XrayVpnService? = null
        @Volatile private var tunnel: ParcelFileDescriptor? = null
        private var controller: CoreController? = null
        private var rxTotal = 0L
        private var txTotal = 0L

        private val _status = MutableStateFlow<XrayStatus>(XrayStatus.Stopped(0))
        val status: StateFlow<XrayStatus> = _status.asStateFlow()

        private fun publish(status: XrayStatus) {
            _status.value = status
        }

        /**
         * Starts (or switches to) [profile]'s tunnel and returns the run id its [status] updates
         * carry. The caller must have VPN consent already (VpnService.prepare returned null).
         */
        fun start(context: Context, profile: VpnProfile): Long {
            val runId = runIds.incrementAndGet()
            currentRunId = runId
            publish(XrayStatus.Starting(runId))
            context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit()
                .putString(SESSION_LINK, profile.coreLink)
                .putString(SESSION_NAME, profile.name)
                .apply()
            val intent = Intent(context, XrayVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LINK, profile.coreLink)
                .putExtra(EXTRA_NAME, profile.name)
                .putExtra(EXTRA_RUN_ID, runId)
            ContextCompat.startForegroundService(context, intent)
            return runId
        }

        /** The server of the last run the user started and has not stopped: link and display name. */
        private fun lastSession(context: Context): Pair<String, String>? {
            val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            val link = prefs.getString(SESSION_LINK, null) ?: return null
            return link to prefs.getString(SESSION_NAME, null).orEmpty()
        }

        private fun clearSession(context: Context) {
            context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }

        /** Returns at once; the teardown finishes on the worker thread. */
        fun stop(context: Context) {
            clearSession(context)
            val runId = runIds.incrementAndGet()
            currentRunId = runId
            val service = instance
            if (service != null) {
                val startId = service.latestStartId
                worker.execute { service.stopTunnel(XrayStatus.Stopped(runId), startId) }
            } else {
                worker.execute { stopCore() }
                publish(XrayStatus.Stopped(runId))
            }
        }

        /**
         * Cumulative bytes through the tunnel since it came up, from the core's own per-outbound
         * counters. The query resets those counters, so this must stay their only reader.
         */
        fun trafficStats(): TrafficStats? = synchronized(coreLock) {
            val core = controller ?: return null
            if (tunnel == null || !core.isRunning) return null
            val payload = runCatching { core.queryAllOutboundTrafficStats() }.getOrNull() ?: return null
            // Format: tag,direction,value;tag,direction,value;  All outbounds count: direct traffic
            // (private addresses) also passes through the tunnel interface.
            payload.split(';').forEach { entry ->
                val parts = entry.split(',', limit = 3)
                val value = parts.getOrNull(2)?.toLongOrNull() ?: return@forEach
                when (parts[1]) {
                    "downlink" -> rxTotal += value
                    "uplink" -> txTotal += value
                }
            }
            TrafficStats(rxBytes = rxTotal, txBytes = txTotal)
        }

        /**
         * Milliseconds for [url] fetched through the running core's proxy, or null when it does not
         * get through. The app itself is excluded from the tunnel, so a plain request would not test it.
         */
        fun measureDelayMs(url: String): Long? {
            val core = synchronized(coreLock) { controller?.takeIf { tunnel != null && it.isRunning } } ?: return null
            // Not under the lock: it can take up to 12 s. The Go side checks for a stopped core itself.
            return runCatching { core.measureDelay(url) }.getOrNull()?.takeIf { it >= 0 }
        }

        private fun resetTraffic() {
            rxTotal = 0
            txTotal = 0
        }

        /** Must run on [worker]. Stops the core before closing the descriptor it reads from. */
        private fun stopCore() {
            synchronized(coreLock) {
                controller?.let { core ->
                    if (core.isRunning) runCatching { core.stopLoop() }.onFailure { Log.w(TAG, "stopLoop failed", it) }
                }
                runCatching { tunnel?.close() }
                tunnel = null
                // After the core, so nothing is left writing to a proxy that has gone.
                HysteriaClient.stop()
            }
        }

        /** Must run under [coreLock]. One controller for the whole process, as in v2rayNG. */
        private fun XrayVpnService.coreController(): CoreController {
            controller?.let { return it }
            // Seq needs a context before any Go call. Assets would be looked up in this directory,
            // but the config uses no geoip/geosite files. An empty key lets XUDP pick a random one;
            // a malformed key would make the core refuse to start.
            go.Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(getDir("xray", Context.MODE_PRIVATE).absolutePath, "")
            return Libv2ray.newCoreController(CoreCallback()).also { controller = it }
        }
    }
}
