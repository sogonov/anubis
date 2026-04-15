package sgnv.anubis.app.service

import android.content.Context
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.VpnControlMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core logic:
 *
 * LOCAL ("No VPN") and VPN_ONLY are always frozen by default.
 * They are only unfrozen when the user explicitly launches them from the home screen.
 *
 * enable (VPN ON):  freeze LOCAL group
 * disable (VPN OFF): freeze VPN_ONLY group
 *
 * launchWithVpn: ensure VPN is on + restricted frozen → unfreeze this app → launch
 * launchLocal:     ensure VPN is off + vpn_only frozen → unfreeze this app → launch
 */
class StealthOrchestrator(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val vpnClientManager: VpnClientManager,
    private val repository: AppRepository,
) {
    private val _state = MutableStateFlow(StealthState.DISABLED)
    val state: StateFlow<StealthState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun clearError() { _lastError.value = null }

    /**
     * Sync stealth state from reality.
     * Called on onResume — handles state changes made by ShortcutActivity or TileService.
     */
    fun syncState() {
        val vpnActive = vpnClientManager.vpnActive.value
        val newState = if (vpnActive) StealthState.ENABLED else StealthState.DISABLED
        if (_state.value != StealthState.ENABLING && _state.value != StealthState.DISABLING) {
            _state.value = newState
        }
    }

    /** Version counter — incremented on any freeze/unfreeze to trigger UI refresh */
    private val _frozenVersion = MutableStateFlow(0L)
    val frozenVersion: StateFlow<Long> = _frozenVersion

    /**
     * Enable stealth (VPN ON): freeze LOCAL apps, start VPN.
     * VPN_ONLY stays frozen — they are only unfrozen by explicit launch.
     */
    suspend fun enable(client: SelectedVpnClient) {
        _lastError.value = null
        _state.value = StealthState.ENABLING
        if (!checkShizuku()) return

        if (shizukuManager.isAppFrozen(client.packageName)) {
            fail("VPN-клиент ${client.displayName} заморожен!")
            return
        }

        freezeGroup(AppGroup.LOCAL)

        vpnClientManager.startVPN(client)

        bumpVersion()

        if (client.controlMode == VpnControlMode.MANUAL) {
            _lastError.value = "Подключите VPN вручную в ${client.displayName}"
        }

        _state.value = StealthState.ENABLED
    }

    /**
     * Disable stealth (VPN OFF): stop VPN, freeze VPN_ONLY apps.
     * LOCAL stays frozen — they are only unfrozen by explicit launch.
     */
    suspend fun disable(client: SelectedVpnClient, detectedPackage: String?) {
        _lastError.value = null
        _state.value = StealthState.DISABLING
        if (vpnClientManager.vpnActive.value) {
            if (!stopVpn(client, detectedPackage)) {
                _lastError.value = "Не удалось отключить VPN. Приложения НЕ разморожены."
                _state.value = StealthState.ENABLED
                return
            }
        }

        freezeGroup(AppGroup.VPN_ONLY)
        bumpVersion()
        _state.value = StealthState.DISABLED
    }

    /**
     * VPN already active on app start — just freeze LOCAL.
     */
    suspend fun freezeOnly() {
        _lastError.value = null
        if (!checkShizuku()) return
        freezeGroup(AppGroup.LOCAL)
        bumpVersion()
        syncState()
    }

    /**
     * VPN turned OFF — freeze VPN_ONLY group.
     */
    suspend fun freezeVpnOnly() {
        if (!checkShizuku()) return
        freezeGroup(AppGroup.VPN_ONLY)
        bumpVersion()
        syncState()
    }

    /**
     * Launch app from LAUNCH_VPN or VPN_ONLY group:
     * freeze LOCAL → start VPN → unfreeze this app → launch.
     */
    suspend fun launchWithVpn(packageName: String, vpnClient: SelectedVpnClient) {
        _lastError.value = null

        if (_state.value != StealthState.ENABLED) {
            enable(vpnClient)
            if (_state.value != StealthState.ENABLED) return
        }

        if (shizukuManager.isAppFrozen(packageName)) {
            shizukuManager.unfreezeApp(packageName)
            bumpVersion()
        }

        vpnClientManager.launchApp(packageName)
    }

    /**
     * Launch LOCAL app: stop VPN → freeze VPN_ONLY → unfreeze app → launch.
     */
    suspend fun launchLocal(
        packageName: String,
        vpnClient: SelectedVpnClient,
        detectedPackage: String?
    ) {
        _lastError.value = null

        if (_state.value == StealthState.ENABLED || vpnClientManager.vpnActive.value) {
            disable(vpnClient, detectedPackage)
            if (vpnClientManager.vpnActive.value) {
                _lastError.value = "Не удалось отключить VPN. Приложение не запущено."
                return
            }
        }

        if (shizukuManager.isAppFrozen(packageName)) {
            shizukuManager.unfreezeApp(packageName)
            bumpVersion()
        }

        vpnClientManager.launchApp(packageName)
    }

    /**
     * Manual freeze/unfreeze from context menu.
     */
    suspend fun toggleAppFrozen(packageName: String) {
        if (!checkShizuku()) return
        if (shizukuManager.isAppFrozen(packageName)) {
            shizukuManager.unfreezeApp(packageName)
        } else {
            shizukuManager.freezeApp(packageName)
        }
        bumpVersion()
    }

    // --- Helpers ---

    private fun bumpVersion() {
        _frozenVersion.value++
    }

    private suspend fun stopVpn(client: SelectedVpnClient, detectedPackage: String?): Boolean {
        // Step 1: API stop — only for SEPARATE mode (explicit stop command).
        // TOGGLE is unreliable for stop (can re-enable immediately), skip to dummy VPN.
        if (client.controlMode == VpnControlMode.SEPARATE) {
            vpnClientManager.stopVPN(client)
            if (waitForVpnOff(3000)) return true
        }

        // Step 2: Dummy VPN — take over as VPN, system kills theirs
        StealthVpnService.disconnect(context)
        if (waitForVpnOff(2000)) return true

        // Step 3: Force-stop the detected VPN app
        val pkg = detectedPackage ?: client.packageName
        shizukuManager.forceStopApp(pkg)
        return waitForVpnOff(2000)
    }

    private fun checkShizuku(): Boolean {
        if (!shizukuManager.isAvailable()) { fail("Shizuku не запущен."); return false }
        if (!shizukuManager.hasPermission()) { fail("Нет разрешения Shizuku."); return false }
        return true
    }

    private suspend fun freezeGroup(group: AppGroup) {
        val packages = repository.getPackagesByGroup(group)
        for (pkg in packages) {
            if (shizukuManager.isAppInstalled(pkg) && !shizukuManager.isAppFrozen(pkg)) {
                shizukuManager.freezeApp(pkg)
            }
        }
    }

    private suspend fun waitForVpnOff(timeoutMs: Long): Boolean {
        val steps = (timeoutMs / 200).toInt()
        repeat(steps) {
            delay(200)
            vpnClientManager.refreshVpnState()
            if (!vpnClientManager.vpnActive.value) return true
        }
        return false
    }

    private fun fail(message: String) {
        _lastError.value = message
        _state.value = StealthState.DISABLED
    }
}

enum class StealthState {
    DISABLED,
    ENABLING,
    ENABLED,
    DISABLING
}
