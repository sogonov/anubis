package sgnv.anubis.app.service

import android.content.Context
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientControls
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.VpnControlMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    suspend fun enable(client: SelectedVpnClient) = withContext(Dispatchers.Default) {
        // Detached-job pattern: enableImpl runs in a process-wide scope, we wait via
        // job.join() with a hard timeout. Why: the original `withTimeoutOrNull(120s) {
        // enableImpl(...) }` couldn't fire because enableImpl's chain hits a
        // `withContext(Dispatchers.IO) { sync_binder_call }` (Shizuku userService.execCommand
        // for am broadcast / dumpsys), and Kotlin cancellation is cooperative — withContext
        // can't return until the IO block completes. join() IS truly cancellable, so the
        // user-facing state recovers at 120 s even if the binder is wedged. The orphan
        // continues until the binder eventually returns; at most one orphan, and the
        // groupOpMutex is released long before the hang point (after freezeGroup).
        _lastError.value = null
        _state.value = StealthState.ENABLING
        val app = context.applicationContext as AnubisApp
        val job = app.scope.launch { enableImpl(client) }
        try {
            val finished = withTimeoutOrNull(TOTAL_OP_TIMEOUT_MS) {
                job.join(); true
            } ?: false
            if (!finished) {
                job.cancel()  // best-effort; sync binder won't actually interrupt
                _progressText.value = null
                _lastError.value = "Операция превысила таймаут (${TOTAL_OP_TIMEOUT_MS / 1000} с). Попробуйте снова."
                _state.value = StealthState.DISABLED
            }
        } finally {
            // Safety: never leave _state stuck in a transitional value — align with
            // actual VPN state so UI can't get stuck on the orange "ENABLING" banner
            // regardless of cancellation, missed code path or unexpected exception.
            alignStateWithVpn()
        }
    }

    private suspend fun enableImpl(client: SelectedVpnClient) {
        if (!checkShizuku()) return

        if (shouldFreezeClientInIdle(client.packageName)) {
            shizukuManager.unfreezeApp(client.packageName)
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
        val startError = vpnClientManager.startVPN(client)
        if (startError != null) {
            fail(startError)
            return
        }

        if (client.controlMode != VpnControlMode.MANUAL && !waitForVpnOn(10_000)) {
            fail("VPN client ${client.displayName} did not come up in time.")
            return
        }

        bumpVersion()
        _progressText.value = null

        if (client.controlMode == VpnControlMode.MANUAL) {
            _lastError.value = "Подключите VPN вручную в ${client.displayName}"
        }

        // Compare-and-set guards the race where VpnMonitorService.onLost fires on a
        // binder thread between waitForVpnOn succeeding and this line — it would
        // set _state=DISABLED via applyManagedStateForVpn(false), and a plain
        // assignment here would clobber that correct update back to ENABLED.
        // If _state is no longer ENABLING, someone else already wrote a definitive
        // value (ENABLED from applyManagedStateForVpn(true), DISABLED from onLost,
        // or DISABLED from fail elsewhere) — don't overwrite.
        _state.compareAndSet(StealthState.ENABLING, StealthState.ENABLED)
    }

    /**
     * Disable stealth (VPN OFF): stop VPN, freeze VPN_ONLY apps.
     * LOCAL stays frozen — they are only unfrozen by explicit launch.
     */
    suspend fun disable(client: SelectedVpnClient, detectedPackage: String?) = withContext(Dispatchers.Default) {
        // See comment on enable() — same detached-job + join() with timeout pattern.
        _lastError.value = null
        _state.value = StealthState.DISABLING
        val app = context.applicationContext as AnubisApp
        val job = app.scope.launch { disableImpl(client, detectedPackage) }
        try {
            val finished = withTimeoutOrNull(TOTAL_OP_TIMEOUT_MS) {
                job.join(); true
            } ?: false
            if (!finished) {
                job.cancel()
                _progressText.value = null
                _lastError.value = "Операция превысила таймаут (${TOTAL_OP_TIMEOUT_MS / 1000} с). Попробуйте снова."
            }
        } finally {
            alignStateWithVpn()
        }
    }

    private suspend fun disableImpl(client: SelectedVpnClient, detectedPackage: String?) {
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

    private fun alignStateWithVpn() {
        val current = _state.value
        if (current == StealthState.ENABLING || current == StealthState.DISABLING) {
            _state.value = if (vpnClientManager.vpnActive.value) {
                StealthState.ENABLED
            } else {
                StealthState.DISABLED
            }
        }
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

    private suspend fun checkShizuku(): Boolean {
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

    private suspend fun fail(message: String) {
        _progressText.value = null
        freezeSelectedVpnClientIfNeeded()
        _lastError.value = message
        _state.value = StealthState.DISABLED
    }

    private suspend fun freezeSelectedVpnClientIfNeeded() {
        val client = AppSettings.loadSelectedVpnClient(context)
        if (!shouldFreezeClientInIdle(client.packageName)) return
        if (!shizukuManager.awaitUserService(500)) return
        shizukuManager.freezeApp(client.packageName)
    }

    private fun shouldFreezeClientInIdle(packageName: String): Boolean =
        packageName.isNotBlank() && VpnClientControls.getControlForPackage(packageName).freezeInIdle

    private companion object {
        // Cap on concurrent Shizuku IPC calls during group freeze/unfreeze.
        // On Honor MagicOS parallel disable-user emits a flood of PACKAGE_REMOVED
        // broadcasts that overwhelms the stock launcher's grid repaint — 4 was
        // observed to ANR the launcher on 50+ apps (ealos fork, v0.1.4). The
        // v0.1.5 binder path removes the shell-timeout class of failures but
        // not the broadcast-storm class, so parallelism stays low.
        const val FREEZE_CONCURRENCY = 2

        // Hard ceiling for the entire enable/disable operation. Realistic budget:
        // freezeGroup ~5 s (binder calls finish in <200 ms each, semaphore=2),
        // startVPN 1–3 s, waitForVpnOn up to 10 s ≈ 20 s typical.
        // 45 s covers slow OEMs + a couple of stuck binder calls hitting their 5 s
        // BINDER_OP_TIMEOUT_MS cap, while still bailing before the user reaches for
        // force-stop. If we hit this ceiling something is genuinely broken (Shizuku
        // wedged, VPN client unresponsive) — no point waiting longer.
        const val TOTAL_OP_TIMEOUT_MS = 45_000L
    }
}

enum class StealthState {
    DISABLED,
    ENABLING,
    ENABLED,
    DISABLING
}


