package sgnv.anubis.app.service

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.vpn.VpnClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StealthTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val willBeActive = !isVpnActive()
        updateTile(isActive = willBeActive)

        val app = application as AnubisApp
        val shizukuManager = app.shizukuManager
        val vpnClientManager = VpnClientManager(this, shizukuManager)
        val repo = AppRepository(app.database.managedAppDao(), this)
        val orchestrator = StealthOrchestrator(this, shizukuManager, vpnClientManager, repo)

        // Tile uses the same selected client preference as the rest of the app.
        val client = AppSettings.loadSelectedVpnClient(this)

        vpnClientManager.startMonitoringVpn()

        scope.launch {
            try {
                shizukuManager.awaitUserService()

                withTimeoutOrNull(TOTAL_TOGGLE_TIMEOUT_MS) {
                    if (willBeActive) {
                        VpnMonitorService.start(this@StealthTileService)
                        orchestrator.enable(client)
                        if (orchestrator.lastError.value == null) {
                            withTimeoutOrNull(VPN_CONNECT_TIMEOUT_MS) {
                                vpnClientManager.vpnActive.first { it }
                            }
                        }
                    } else {
                        vpnClientManager.refreshVpnState()
                        vpnClientManager.detectActiveVpnClient()
                        val detectedPkg = vpnClientManager.activeVpnPackage.value
                        orchestrator.disable(client, detectedPkg)
                        VpnMonitorService.stop(this@StealthTileService)
                    }
                }

                vpnClientManager.stopMonitoringVpn()
            } finally {
                updateTile()
            }
        }
    }

    private fun updateTile(isActive: Boolean? = null) {
        val tile = qsTile ?: return
        val vpnActive = isActive ?: isVpnActive()
        tile.state = if (vpnActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (vpnActive) "Stealth ON" else "Stealth OFF"
        tile.updateTile()
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val VPN_CONNECT_TIMEOUT_MS = 15_000L
        private const val TOTAL_TOGGLE_TIMEOUT_MS = 40_000L
    }
}
