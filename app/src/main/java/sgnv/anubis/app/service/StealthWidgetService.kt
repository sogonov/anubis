package sgnv.anubis.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.VpnClientType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

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
        val vpnClientManager = VpnClientManager(this, shizukuManager)
        val repo = AppRepository(app.database.managedAppDao(), this)
        val orchestrator = StealthOrchestrator(this, shizukuManager, vpnClientManager, repo)

        val prefs = AppSettings.prefs(this)
        val pkg = prefs.getString(AppSettings.KEY_VPN_CLIENT_PACKAGE, null)
            ?: VpnClientType.V2RAY_NG.packageName
        val client = SelectedVpnClient.fromPackage(pkg)

        // Immediate feedback before the Shizuku bind delay.
        StealthWidgetProvider.updateAllWidgets(
            this,
            if (willBeActive) "Замораживаю..." else "Отключаю VPN...",
            StealthWidgetProvider.COLOR_WORKING
        )

        shizukuManager.bindUserService()
        vpnClientManager.startMonitoringVpn()

        scope.launch {
            shizukuManager.awaitUserService()

            // Mirror each orchestrator progress step into the widget text.
            val progressJob = launch {
                orchestrator.progressText.filterNotNull().collect { text ->
                    StealthWidgetProvider.updateAllWidgets(
                        this@StealthWidgetService, text, StealthWidgetProvider.COLOR_WORKING
                    )
                }
            }

            if (willBeActive) {
                orchestrator.enable(client)
                VpnMonitorService.start(this@StealthWidgetService)
            } else {
                vpnClientManager.refreshVpnState()
                vpnClientManager.detectActiveVpnClient()
                val detectedPkg = vpnClientManager.activeVpnPackage.value
                orchestrator.disable(client, detectedPkg)
                VpnMonitorService.stop(this@StealthWidgetService)
            }

            progressJob.cancel()
            vpnClientManager.stopMonitoringVpn()
            StealthWidgetProvider.updateAllWidgets(this@StealthWidgetService)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "sgnv.anubis.app.WIDGET_DO_TOGGLE"

        fun toggle(context: Context) {
            context.startService(
                Intent(context, StealthWidgetService::class.java).apply {
                    action = ACTION_TOGGLE
                }
            )
        }
    }
}
