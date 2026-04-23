package sgnv.anubis.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Short-lived service for home-screen widget toggle.
 * Mirrors StealthTileService.onClick() and relays orchestrator progress text
 * back to the widget in real time.
 */
class StealthWidgetService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) doToggle()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun doToggle() {
        val willBeActive = !StealthWidgetProvider.isVpnActive(this)

        val app = applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        val vpnClientManager = app.vpnClientManager
        val orchestrator = app.orchestrator

        val prefs = AppSettings.prefs(this)
        val pkg = prefs.getString(AppSettings.KEY_VPN_CLIENT_PACKAGE, null)
            ?: VpnClientType.V2RAY_NG.packageName
        val client = SelectedVpnClient.fromPackage(pkg)

        StealthWidgetProvider.updateAllWidgets(
            this,
            if (willBeActive) "Замораживаю..." else "Отключаю VPN...",
            StealthWidgetProvider.workingColor(this)
        )

        scope.launch {
            try {
                shizukuManager.awaitUserService()

                val progressJob = launch {
                    orchestrator.progressText.filterNotNull().collect { text ->
                        StealthWidgetProvider.updateAllWidgets(
                            this@StealthWidgetService, text, StealthWidgetProvider.workingColor(this@StealthWidgetService)
                        )
                    }
                }

                // Hard ceiling for the entire operation so the widget never gets
                // permanently stuck in an intermediate state if something hangs.
                withTimeoutOrNull(TOTAL_TOGGLE_TIMEOUT_MS) {
                    if (willBeActive) {
                        orchestrator.enable(client)
                        // join() ensures any in-flight updateAllWidgets() inside
                        // progressJob completes before we overwrite with final state.
                        progressJob.cancel()
                        progressJob.join()
                        if (orchestrator.lastError.value == null) {
                            StealthWidgetProvider.updateAllWidgets(
                                this@StealthWidgetService,
                                "Подключаю...",
                                StealthWidgetProvider.workingColor(this@StealthWidgetService)
                            )
                            withTimeoutOrNull(VPN_CONNECT_TIMEOUT_MS) {
                                vpnClientManager.vpnActive.first { it }
                            }
                        }
                    } else {
                        vpnClientManager.refreshVpnState()
                        vpnClientManager.detectActiveVpnClient()
                        val detectedPkg = vpnClientManager.activeVpnPackage.value
                        orchestrator.disable(client, detectedPkg)
                        progressJob.cancel()
                        progressJob.join()
                    }
                }

                progressJob.cancel()
            } finally {
                // Always reset widget to real VPN state — covers normal completion,
                // timeout, and any unexpected exception.
                StealthWidgetProvider.updateAllWidgets(this@StealthWidgetService)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "sgnv.anubis.app.WIDGET_DO_TOGGLE"
        private const val VPN_CONNECT_TIMEOUT_MS = 15_000L

        // Freeze/unfreeze timeout per app is 5 s (ShizukuManager.BINDER_OP_TIMEOUT_MS).
        // Budget: awaitUserService 500 ms + freeze batch ~10 s + VPN connect 15 s + margin.
        private const val TOTAL_TOGGLE_TIMEOUT_MS = 40_000L

        fun toggle(context: Context) {
            context.startService(
                Intent(context, StealthWidgetService::class.java).apply {
                    action = ACTION_TOGGLE
                }
            )
        }
    }
}
