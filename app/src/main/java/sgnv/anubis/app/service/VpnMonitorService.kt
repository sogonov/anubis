package sgnv.anubis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import sgnv.anubis.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R
import sgnv.anubis.app.ui.MainActivity

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
    private var monitoringJob: Job? = null
    private val policyMonitor: VpnPolicyMonitor by lazy {
        val app = applicationContext as AnubisApp
        VpnPolicyMonitor(app, { !app.vpnClientManager.isOwnVpnNetwork(it) }, {
            AppSettings.prefs(this).edit(commit = true) {
                putBoolean(AppSettings.KEY_PENDING_BOOT_FREEZE, policyMonitor.bootPending.value)
            }
            StealthWidgetProvider.updateAllWidgets(applicationContext)
            if (!policyMonitor.bootPending.value &&
                !AppSettings.prefs(this)
                    .getBoolean(AppSettings.KEY_BACKGROUND_MONITORING, false)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }).also {
            it.bootPending.value = AppSettings.prefs(this)
                .getBoolean(AppSettings.KEY_PENDING_BOOT_FREEZE, false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_BOOT, null -> {
                if (intent?.action == ACTION_BOOT) {
                    AppSettings.prefs(this).edit(commit = true) { putBoolean(AppSettings.KEY_PENDING_BOOT_FREEZE, true) }
                    policyMonitor.bootPending.value = true
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                startVpnMonitoring()
            }
            ACTION_STOP -> {
                stopVpnMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVpnMonitoring() {
        if (monitoringJob?.isActive == true) return

        monitoringJob = scope.launch { policyMonitor.run() }
    }

    private fun stopVpnMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
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
        const val ACTION_START = "sgnv.anubis.app.START_MONITOR"
        const val ACTION_STOP = "sgnv.anubis.app.STOP_MONITOR"
        const val NOTIFICATION_ID = 1

        private const val ACTION_BOOT = "sgnv.anubis.app.BOOT_MONITOR"

        fun start(context: Context, freezeOnBoot: Boolean = false) {
            val intent = Intent(context, VpnMonitorService::class.java).apply {
                action = if (freezeOnBoot) ACTION_BOOT else ACTION_START
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
