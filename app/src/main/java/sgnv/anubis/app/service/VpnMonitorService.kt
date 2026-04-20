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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.vpn.VpnClientControls
import sgnv.anubis.app.ui.MainActivity

/**
 * Foreground service that monitors VPN state changes and auto-freezes app groups.
 *
 * When VPN turns ON:  freezes LOCAL group
 * When VPN turns OFF: freezes VPN_ONLY group
 *
 * This closes the gap where user toggles VPN outside of Anubis.
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

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Skip our own force-disconnect dummy VPN — treating it as "external VPN ON"
                // would re-freeze groups that disable() just unfroze.
                //
                // Primary check: ownerUid. Stable, survives the "long-idle phantom VPN
                // entry" case where Android still lists our dummy well past its
                // onDestroy grace window.
                // Fallback check: the time-based dummyVpnInFlight flag, in case
                // ownerUid reads -1 for reasons outside our understanding.
                if (isOwnVpnNetwork(cm, network)) return
                if (StealthVpnService.dummyVpnInFlight) return
                // VPN turned ON — freeze LOCAL group
                scope.launch { applyManagedStateForVpn(active = true) }
            }

            override fun onLost(network: Network) {
                if (isOwnVpnNetwork(cm, network)) return
                if (StealthVpnService.dummyVpnInFlight) return
                // VPN turned OFF — freeze VPN_ONLY group
                val stillActive = cm.allNetworks.any { n ->
                    val caps = cm.getNetworkCapabilities(n) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        caps.ownerUid != Process.myUid()
                }
                if (!stillActive) {
                    scope.launch { applyManagedStateForVpn(active = false) }
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun isOwnVpnNetwork(cm: ConnectivityManager, network: Network): Boolean = try {
        cm.getNetworkCapabilities(network)?.ownerUid == Process.myUid()
    } catch (_: Exception) {
        false
    }

    private fun stopVpnMonitoring() {
        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private suspend fun freezeGroup(group: AppGroup) {
        val app = applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        if (!shizukuManager.isAvailable() || !shizukuManager.hasPermission()) return
        shizukuManager.bindUserService()

        val repo = AppRepository(app.database.managedAppDao(), applicationContext)
        val packages = repo.getPackagesByGroup(group)
        val sem = Semaphore(FREEZE_CONCURRENCY)
        coroutineScope {
            packages.map { pkg ->
                async {
                    sem.withPermit {
                        if (shizukuManager.isAppInstalled(pkg) && !shizukuManager.isAppFrozen(pkg)) {
                            shizukuManager.freezeApp(pkg)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun unfreezeGroup(group: AppGroup) {
        val app = applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        if (!shizukuManager.isAvailable() || !shizukuManager.hasPermission()) return
        shizukuManager.bindUserService()

        val repo = AppRepository(app.database.managedAppDao(), applicationContext)
        val packages = repo.getPackagesByGroup(group)
        val sem = Semaphore(FREEZE_CONCURRENCY)
        coroutineScope {
            packages.map { pkg ->
                async {
                    sem.withPermit {
                        if (shizukuManager.isAppInstalled(pkg) && shizukuManager.isAppFrozen(pkg)) {
                            shizukuManager.unfreezeApp(pkg)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun applyManagedStateForVpn(active: Boolean) {
        val mutex = (applicationContext as AnubisApp).groupOpMutex
        try {
            // Hard ceiling: if freeze/unfreeze hangs (e.g. binder IPC stuck),
            // withTimeoutOrNull cancels the lock holder and releases the mutex
            // via withLock's finally block — preventing permanent mutex starvation.
            withTimeoutOrNull(APPLY_STATE_TIMEOUT_MS) {
                mutex.withLock {
                    if (active) {
                        freezeGroup(AppGroup.LOCAL)
                        freezeGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
                        if (AppSettings.shouldUnfreezeManagedAppsOnVpnToggle(applicationContext)) {
                            unfreezeGroup(AppGroup.VPN_ONLY)
                        }
                    } else {
                        freezeGroup(AppGroup.VPN_ONLY)
                        unfreezeGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
                        if (AppSettings.shouldUnfreezeManagedAppsOnVpnToggle(applicationContext)) {
                            unfreezeGroup(AppGroup.LOCAL)
                        }
                    }
                }
                freezeSelectedVpnClientIfNeeded()
            }
        } finally {
            // Always update widget to real VPN state — even if freeze/unfreeze timed out.
            StealthWidgetProvider.updateAllWidgets(applicationContext)
        }
    }

    private suspend fun freezeSelectedVpnClientIfNeeded() {
        val client = AppSettings.loadSelectedVpnClient(applicationContext)
        val control = VpnClientControls.getControlForClient(client)
        if (!control.freezeInIdle) return
        val shizukuManager = (applicationContext as AnubisApp).shizukuManager
        if (!shizukuManager.isAvailable() || !shizukuManager.hasPermission()) return
        if (!shizukuManager.awaitUserService(500)) return
        shizukuManager.freezeApp(client.packageName)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AnubisApp.CHANNEL_ID)
            .setContentTitle("Anubis: мониторинг активен")
            .setContentText("Автозаморозка при изменении VPN")
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
        const val ACTION_START = "sgnv.anubis.app.START_MONITOR"
        const val ACTION_STOP = "sgnv.anubis.app.STOP_MONITOR"
        const val NOTIFICATION_ID = 1

        private const val FREEZE_CONCURRENCY = 2

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
