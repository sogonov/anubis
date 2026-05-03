package sgnv.anubis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R
import sgnv.anubis.app.ui.MainActivity
import sgnv.anubis.app.util.AppLogger

/**
 * Foreground service that syncs stealth state with real VPN state while the UI is
 * killed. Delegates all freeze/unfreeze and _state transitions to the shared
 * StealthOrchestrator in AnubisApp — that way UI and service are backed by the
 * same StateFlow and can't drift from each other.
 *
 * When VPN turns ON:  orchestrator.freezeOnly()  -> freezes LOCAL, sets ENABLED
 * When VPN turns OFF: orchestrator.freezeVpnOnly() -> freezes VPN_ONLY, sets DISABLED
 *
 * This closes the gap where the user toggles VPN outside of Anubis.
 */
class VpnMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startVpnMonitoring()
            }
            ACTION_STOP -> {
                stopVpnMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVpnMonitoring() {
        if (networkCallback != null) return

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val app = applicationContext as AnubisApp

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Skip our own force-disconnect dummy VPN — treating it as "external VPN ON"
                // would re-freeze groups that disable() just unfroze.
                if (isOwnVpnNetwork(cm, network)) return
                if (StealthVpnService.dummyVpnInFlight) return
                scope.launch {
                    withTimeoutOrNull(APPLY_STATE_TIMEOUT_MS) {
                        app.orchestrator.freezeOnly()
                    }
                    StealthWidgetProvider.updateAllWidgets(applicationContext)
                }
            }

            override fun onLost(network: Network) {
                if (isOwnVpnNetwork(cm, network)) return
                if (StealthVpnService.dummyVpnInFlight) return
                // Exclude the just-lost network: at the moment onLost is dispatched,
                // the system may not have removed it from allNetworks yet. Without
                // this, stillActive sees the dying network and we skip freezeVpnOnly
                // — state gets stuck at ENABLED after external VPN crashes.
                val stillActive = cm.allNetworks.any { n ->
                    if (n == network) return@any false
                    val caps = cm.getNetworkCapabilities(n) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        caps.ownerUid != Process.myUid()
                }
                if (!stillActive) {
                    scope.launch {
                        withTimeoutOrNull(APPLY_STATE_TIMEOUT_MS) {
                            app.orchestrator.freezeVpnOnly()
                        }
                        StealthWidgetProvider.updateAllWidgets(applicationContext)
                    }
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            AppLogger.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    private fun isOwnVpnNetwork(cm: ConnectivityManager, network: Network): Boolean = try {
        cm.getNetworkCapabilities(network)?.ownerUid == Process.myUid()
    } catch (e: Exception) {
        AppLogger.e(TAG, "isOwnVpnNetwork failed", e)
        false
    }

    private fun stopVpnMonitoring() {
        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                AppLogger.e(TAG, "unregisterNetworkCallback failed", e)
            }
            networkCallback = null
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AnubisApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_monitor_notification_title))
            .setContentText(getString(R.string.vpn_monitor_notification_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpnMonitoring()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VpnMonitorService"
        const val ACTION_START = "sgnv.anubis.app.START_MONITOR"
        const val ACTION_STOP = "sgnv.anubis.app.STOP_MONITOR"
        const val NOTIFICATION_ID = 1

        // Per-binder-op timeout is 5 s (ShizukuManager.BINDER_OP_TIMEOUT_MS).
        // With FREEZE_CONCURRENCY=2 and up to ~30 managed apps, worst case
        // is ceil(30/2) * 5 s = 75 s — use 90 s as the ceiling for the whole op.
        private const val APPLY_STATE_TIMEOUT_MS = 90_000L

        fun start(context: Context) {
            val intent = Intent(context, VpnMonitorService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VpnMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
