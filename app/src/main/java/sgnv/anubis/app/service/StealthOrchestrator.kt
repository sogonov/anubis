package sgnv.anubis.app.service

import android.content.Context
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.VpnControlMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import sgnv.anubis.app.AnubisApp

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
        // If VPN is on, we're in stealth mode (spies should be frozen)
        // If VPN is off, stealth is disabled
        val newState = if (vpnActive) StealthState.ENABLED else StealthState.DISABLED
        if (_state.value != StealthState.ENABLING && _state.value != StealthState.DISABLING) {
            _state.value = newState
        }
    }

    /** Short status string for widget display; null when idle. */
    private val _progressText = MutableStateFlow<String?>(null)
    val progressText: StateFlow<String?> = _progressText

    /** Version counter — incremented on any freeze/unfreeze to trigger UI refresh */
    private val _frozenVersion = MutableStateFlow(0L)
    val frozenVersion: StateFlow<Long> = _frozenVersion

    /** Easter-egg benchmark: emits "Заморожено N за X.Xс" / "Разморожено ..." after group ops. */
    private val _benchmark = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val benchmark: SharedFlow<String> = _benchmark

    /**
     * Enable stealth (VPN ON): freeze LOCAL apps, start VPN.
     * VPN_ONLY stays frozen — they are only unfrozen by explicit launch.
     */
    suspend fun enable(client: SelectedVpnClient) {
        _lastError.value = null
        _state.value = StealthState.ENABLING

        if (!checkShizuku()) return

        if (shouldFreezeClientInIdle(client.packageName)) {
            shizukuManager.unfreezeApp(client.packageName)
            delay(200)
        }

        if (shizukuManager.isAppFrozen(client.packageName)) {
            fail("VPN client ${client.displayName} is still frozen.")
            return
        }

        _progressText.value = "Замораживаю..."
        val mutex = (context.applicationContext as AnubisApp).groupOpMutex
        val benchStart = System.currentTimeMillis()
        val frozen = mutex.withLock {
            freezeGroup(AppGroup.LOCAL) + freezeGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
        }
        emitBenchmark(frozen = frozen, unfrozen = 0, startMs = benchStart)

        _progressText.value = "Запускаю VPN..."
        vpnClientManager.startVPN(client)

        if (client.controlMode != VpnControlMode.MANUAL && !waitForVpnOn(10_000)) {
            fail("VPN client ${client.displayName} did not come up in time.")
            return
        }

        bumpVersion()
        _progressText.value = null

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
            _progressText.value = "Отключаю VPN..."
            if (!stopVpn(client, detectedPackage)) {
                _progressText.value = null
                _lastError.value = "Не удалось отключить VPN. Приложения НЕ разморожены."
                _state.value = StealthState.ENABLED
                return
            }
        }

        _progressText.value = "Размораживаю..."
        // After confirmed VPN shutdown, align managed groups with the new network state.
        applyManagedStateForVpn(active = false)
        _progressText.value = null
    }

    /**
     * VPN already active on app start — just freeze LOCAL.
     */
    suspend fun freezeOnly() {
        _lastError.value = null
        if (!checkShizuku()) return
        // Handle external/manual VPN activation using the same managed-group rules.
        applyManagedStateForVpn(active = true)
    }

    /**
     * VPN turned OFF — freeze VPN_ONLY group.
     */
    suspend fun freezeVpnOnly() {
        if (!checkShizuku()) return
        // Handle external/manual VPN shutdown using the same managed-group rules.
        applyManagedStateForVpn(active = false)
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

    /** Returns the number of apps actually transitioned (already-frozen apps are skipped). */
    private suspend fun freezeGroup(group: AppGroup): Int {
        val packages = repository.getPackagesByGroup(group)
        val sem = Semaphore(FREEZE_CONCURRENCY)
        val counter = AtomicInteger(0)
        coroutineScope {
            packages.map { pkg ->
                async {
                    sem.withPermit {
                        if (shizukuManager.isAppInstalled(pkg) && !shizukuManager.isAppFrozen(pkg)) {
                            shizukuManager.freezeApp(pkg)
                            counter.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }
        return counter.get()
    }

    private suspend fun unfreezeGroup(group: AppGroup): Int {
        val packages = repository.getPackagesByGroup(group)
        val sem = Semaphore(FREEZE_CONCURRENCY)
        val counter = AtomicInteger(0)
        coroutineScope {
            packages.map { pkg ->
                async {
                    sem.withPermit {
                        if (shizukuManager.isAppInstalled(pkg) && shizukuManager.isAppFrozen(pkg)) {
                            shizukuManager.unfreezeApp(pkg)
                            counter.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }
        return counter.get()
    }

    private fun emitBenchmark(frozen: Int, unfrozen: Int, startMs: Long) {
        if (frozen == 0 && unfrozen == 0) return
        val secs = (System.currentTimeMillis() - startMs) / 1000.0
        val formattedSecs = "%.1f".format(secs)
        val msg = when {
            frozen > 0 && unfrozen > 0 ->
                "Заморожено $frozen, разморожено $unfrozen за $formattedSecs с"
            frozen > 0 -> "Заморожено $frozen за $formattedSecs с"
            else -> "Разморожено $unfrozen за $formattedSecs с"
        }
        _benchmark.tryEmit(msg)
    }

    private suspend fun applyManagedStateForVpn(active: Boolean) {
        val mutex = (context.applicationContext as AnubisApp).groupOpMutex
        val benchStart = System.currentTimeMillis()
        var frozen = 0
        var unfrozen = 0
        mutex.withLock {
            if (active) {
                frozen += freezeGroup(AppGroup.LOCAL)
                // LOCAL_AUTO_UNFREEZE: freeze unconditionally when VPN comes up — that's the whole point of the group.
                frozen += freezeGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
                // Optional issue #31 behavior: make VPN_ONLY apps usable immediately after VPN comes up.
                if (shouldUnfreezeManagedAppsOnVpnToggle()) {
                    unfrozen += unfreezeGroup(AppGroup.VPN_ONLY)
                }
                _state.value = StealthState.ENABLED
            } else {
                frozen += freezeGroup(AppGroup.VPN_ONLY)
                // LOCAL_AUTO_UNFREEZE: unfreeze unconditionally when VPN goes down — that's the whole point of the group.
                unfrozen += unfreezeGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
                // Optional issue #31 behavior: restore LOCAL apps once VPN is fully down.
                if (shouldUnfreezeManagedAppsOnVpnToggle()) {
                    unfrozen += unfreezeGroup(AppGroup.LOCAL)
                }
                freezeSelectedVpnClientIfNeeded()
                _state.value = StealthState.DISABLED
            }
        }
        emitBenchmark(frozen = frozen, unfrozen = unfrozen, startMs = benchStart)
        bumpVersion()
    }

    private fun shouldUnfreezeManagedAppsOnVpnToggle(): Boolean {
        return AppSettings.shouldUnfreezeManagedAppsOnVpnToggle(context)
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

    private suspend fun waitForVpnOn(timeoutMs: Long): Boolean {
        val steps = (timeoutMs / 200).toInt()
        repeat(steps) {
            delay(200)
            vpnClientManager.refreshVpnState()
            if (vpnClientManager.vpnActive.value) return true
        }
        return false
    }

    private fun fail(message: String) {
        _progressText.value = null
        _lastError.value = message
        _state.value = StealthState.DISABLED
    }

    private suspend fun freezeSelectedVpnClientIfNeeded() {
        val packageName = AppSettings.prefs(context)
            .getString(AppSettings.KEY_VPN_CLIENT_PACKAGE, null)
            .orEmpty()
        if (!shouldFreezeClientInIdle(packageName)) return
        shizukuManager.bindUserService()
        delay(200)
        shizukuManager.freezeApp(packageName)
    }

    private fun shouldFreezeClientInIdle(packageName: String): Boolean =
        packageName == TUNGUSKA_PACKAGE

    private companion object {
        const val TUNGUSKA_PACKAGE = "io.acionyx.tunguska"
        // Cap on concurrent Shizuku IPC calls during group freeze/unfreeze.
        // On Honor MagicOS parallel disable-user emits a flood of PACKAGE_REMOVED
        // broadcasts that overwhelms the stock launcher's grid repaint — 4 was
        // observed to ANR the launcher on 50+ apps (ealos fork, v0.1.4). The
        // v0.1.5 binder path removes the shell-timeout class of failures but
        // not the broadcast-storm class, so parallelism stays low.
        const val FREEZE_CONCURRENCY = 2
    }
}

enum class StealthState {
    DISABLED,
    ENABLING,
    ENABLED,
    DISABLING
}


