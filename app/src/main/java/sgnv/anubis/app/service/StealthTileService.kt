package sgnv.anubis.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StealthTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val app = application as AnubisApp
        val shizukuManager = app.shizukuManager
        val vpnClientManager = VpnClientManager(this, shizukuManager)
        val repo = AppRepository(app.database.managedAppDao(), this)
        val orchestrator = StealthOrchestrator(this, shizukuManager, vpnClientManager, repo)

        // Tile uses the same selected client preference as the rest of the app.
        val prefs = AppSettings.prefs(this)
        val pkg = prefs.getString(AppSettings.KEY_VPN_CLIENT_PACKAGE, null)
            ?: VpnClientType.V2RAY_NG.packageName
        val client = SelectedVpnClient.fromPackage(pkg)

        // Ensure UserService is bound (instant if Shizuku is ready)
        shizukuManager.bindUserService()
        vpnClientManager.startMonitoringVpn()

        scope.launch {
            // Small delay for UserService connection callback
            kotlinx.coroutines.delay(200)

            val vpnActive = isVpnActive()

            if (!vpnActive) {
                orchestrator.enable(client)
                VpnMonitorService.start(this@StealthTileService)
            } else {
                vpnClientManager.refreshVpnState()
                vpnClientManager.detectActiveVpnClient()
                val detectedPkg = vpnClientManager.activeVpnPackage.value
                orchestrator.disable(client, detectedPkg)
                if (!isVpnActive()) {
                    VpnMonitorService.stop(this@StealthTileService)
                }
            }

            vpnClientManager.stopMonitoringVpn()
            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val vpnActive = isVpnActive()
        tile.state = if (vpnActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (vpnActive) "Stealth ON" else "Stealth OFF"
        tile.updateTile()
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) { false }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
