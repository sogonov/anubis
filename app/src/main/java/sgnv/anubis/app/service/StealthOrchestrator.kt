package sgnv.anubis.app.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.core.content.edit
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.util.AppLogger
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientControls
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.VpnControlMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
     * Tracks the in-flight enable/disable job so the UI can interrupt a long wait
     * (e.g. user picked a wrong client and is stuck on the 90 s MANUAL_VPN_WAIT_MS).
     * Volatile because [cancelOngoing] is called from the UI thread while [enable]
     * /[disable] mutate this field on Dispatchers.Default.
     */
    @Volatile
    private var currentJob: Job? = null

    /**
     * Whether a transitional operation can be cancelled by the user. Exposed as a
     * StateFlow so the cancel button in HomeScreen can render only when there's
     * actually something to cancel (i.e. enable() with VPN-up wait is in flight).
     */
    private val _cancellable = MutableStateFlow(false)
    val cancellable: StateFlow<Boolean> = _cancellable

    /**
     * Request cancellation of the currently running enable/disable operation.
     * No-op if nothing is in flight. The orchestrator will roll back any
     * already-applied side effects (re-freeze idle client, unfreeze
     * LOCAL_AUTO_UNFREEZE) so we don't leave the user with a half-enabled state.
     */
    fun cancelOngoing() {
        currentJob?.cancel(CancellationException("user-cancelled"))
    }

    /**
     * Master pause (#145). When true, the orchestrator stops reacting to *external*
     * VPN-up/down events — `freezeOnly` and `freezeVpnOnly` early-return without
     * touching any group. Explicit user actions (toggle Stealth from Home or tile,
     * shortcut launches, manual freeze/unfreeze) keep working — they're explicit
     * intent and the user is in control. Already-frozen apps stay frozen; user
     * unfreezes them via Recovery → "Разморозить всё" if they want to.
     *
     * Backed by SharedPreferences so the state survives process death — if the user
     * paused us before going to bed, we're still paused after a reboot.
     */
    private val _paused = MutableStateFlow(
        AppSettings.prefs(context).getBoolean(AppSettings.KEY_PAUSED, false)
    )
    val paused: StateFlow<Boolean> = _paused

    fun setPaused(value: Boolean) {
        if (_paused.value == value) return
        _paused.value = value
        AppSettings.prefs(context).edit { putBoolean(AppSettings.KEY_PAUSED, value) }
        AppLogger.i(TAG, "paused=$value")
        // Refresh out-of-process surfaces so the user sees the change wherever they
        // toggled from — Home, Settings, the QS tile, or the widget. Each of these
        // observes its own state silo, so changing one doesn't propagate without a push.
        PauseWidgetProvider.updateAllWidgets(context)
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, PauseTileService::class.java)
            )
        }
    }

    /**
     * Sync stealth state from reality.
     * Called on onResume — handles state changes made by ShortcutActivity or TileService.
     */
    fun syncState() {
        val vpnActive = vpnClientManager.vpnActive.value
        // If VPN is on, we're in stealth mode (spies should be frozen)
        // If VPN is off, stealth is disabled
        val newState = if (vpnActive) StealthState.ENABLED else StealthState.DISABLED
        if (_state.value != StealthState.ENABLING
            && _state.value != StealthState.DISABLING
            && _state.value != StealthState.UNFREEZING
        ) {
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
        AppLogger.i(TAG, "enable requested, client=${client.packageName}")
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
        currentJob = job
        _cancellable.value = true
        // MANUAL mode now waits for the user to connect the VPN themselves, so the
        // overall ceiling has to absorb that wait — otherwise the 45 s cap would clip
        // MANUAL_VPN_WAIT_MS at ~half its budget.
        val ceilingMs = if (client.controlMode == VpnControlMode.MANUAL) {
            MANUAL_TOTAL_OP_TIMEOUT_MS
        } else {
            TOTAL_OP_TIMEOUT_MS
        }
        try {
            val finished = withTimeoutOrNull(ceilingMs) {
                job.join(); true
            } ?: false
            if (!finished) {
                job.cancel()  // best-effort; sync binder won't actually interrupt
            }
            // Reconcile against the current network: cancellation does not prove VPN shutdown.
            if (job.isCancelled) {
                _progressText.value = null
                _lastError.value = if (!finished) {
                    "Операция превысила таймаут (${ceilingMs / 1000} с). Попробуйте снова."
                } else {
                    "Операция отменена."
                }
                applyManagedStateForVpn()
            }
        } finally {
            currentJob = null
            _cancellable.value = false
            // Safety: never leave _state stuck in a transitional value — align with
            // actual VPN state so UI can't get stuck on the orange "ENABLING" banner
            // regardless of cancellation, missed code path or unexpected exception.
            alignStateWithVpn()
        }
    }

    private suspend fun enableImpl(client: SelectedVpnClient) {
        if (!checkShizuku()) return

        if (shouldFreezeClientInIdle(client.packageName)) {
            val result = shizukuManager.unfreezeApp(client.packageName)
            logShizukuFailure("enableImpl.unfreezeClient", client.packageName, result)
        }

        if (shizukuManager.isAppFrozen(client.packageName)) {
            fail("VPN client ${client.displayName} is still frozen.")
            return
        }

        _progressText.value = "Замораживаю..."
        val mutex = (context.applicationContext as AnubisApp).groupOpMutex
        val benchStart = System.currentTimeMillis()
        val frozen = mutex.withLock {
            val count = freezeGroup(AppGroup.LOCAL) + freezeGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
            if (!isGroupFrozen(AppGroup.LOCAL) || !isGroupFrozen(AppGroup.LOCAL_AUTO_UNFREEZE)) null else count
        }
        if (frozen == null) {
            _progressText.value = null
            return
        }
        emitBenchmark(frozen = frozen, unfrozen = 0, startMs = benchStart)

        _progressText.value = "Запускаю VPN..."
        val startError = vpnClientManager.startVPN(client)
        if (startError != null) {
            AppLogger.e(TAG, "VPN start command failed: ${client.packageName}; error=$startError")
            fail(startError)
            return
        }
        AppLogger.i(TAG, "VPN start command sent: ${client.packageName}")

        // Wait for VPN-up in every mode, including MANUAL. Skipping the wait for MANUAL
        // (the previous behaviour) prematurely flipped state to ENABLED — and from
        // launchWithVpn that meant unfreeze + launch of the protected app while the user
        // was still tapping "Connect" in the VPN client. The app would briefly run with
        // no tunnel (#119). MANUAL gets a longer budget because the user is in the loop.
        val waitTimeoutMs = if (client.controlMode == VpnControlMode.MANUAL) {
            MANUAL_VPN_WAIT_MS
        } else {
            AUTO_VPN_WAIT_MS
        }
        if (client.controlMode == VpnControlMode.MANUAL) {
            _progressText.value = "Подключите VPN в ${client.displayName}..."
        }
        if (!waitForVpnOn(waitTimeoutMs)) {
            AppLogger.e(TAG, "VPN did not become active in time: ${client.packageName}")
            fail("VPN client ${client.displayName} did not come up in time.")
            return
        }
        AppLogger.i(TAG, "VPN became active: ${client.packageName}")

        bumpVersion()
        _progressText.value = null

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
        AppLogger.i(TAG, "disable requested, client=${client.packageName}, detected=${detectedPackage.orEmpty()}")
        // See comment on enable() — same detached-job + join() with timeout pattern.
        _lastError.value = null
        _state.value = StealthState.DISABLING
        val app = context.applicationContext as AnubisApp
        val job = app.scope.launch { disableImpl(client, detectedPackage) }
        currentJob = job
        _cancellable.value = true
        try {
            val finished = withTimeoutOrNull(TOTAL_OP_TIMEOUT_MS) {
                job.join(); true
            } ?: false
            if (!finished) {
                job.cancel()
            }
            if (job.isCancelled) {
                _progressText.value = null
                _lastError.value = if (!finished) {
                    "Операция превысила таймаут (${TOTAL_OP_TIMEOUT_MS / 1000} с). Попробуйте снова."
                } else {
                    "Операция отменена."
                }
                // Disable rolls forward through stopVpn → applyManagedStateForVpn(false).
                // If the user cancelled before VPN went down, alignStateWithVpn() in
                // finally restores ENABLED. If VPN went down already, alignStateWithVpn()
                // pins us to DISABLED. Either way, the group rules will reapply on the
                // next VPN-up/down event — no eager rollback needed here.
            }
        } finally {
            currentJob = null
            _cancellable.value = false
            alignStateWithVpn()
        }
    }

    private suspend fun disableImpl(client: SelectedVpnClient, detectedPackage: String?) {
        if (vpnClientManager.vpnActive.value) {
            _progressText.value = "Отключаю VPN..."
            if (!stopVpn(client, detectedPackage)) {
                _progressText.value = null
                _lastError.value = "Не удалось отключить VPN. Приложения НЕ разморожены. Проверьте, что Anubis имеет разрешение VPN, и что выбран правильный VPN-клиент."
                _state.value = StealthState.ENABLED
                return
            }
        }

        _progressText.value = "Размораживаю..."
        _state.value = StealthState.UNFREEZING
        // After confirmed VPN shutdown, align managed groups with the new network state.
        applyManagedStateForVpn()
        _progressText.value = null
    }

    private fun alignStateWithVpn() {
        val current = _state.value
        if (current == StealthState.ENABLING
            || current == StealthState.DISABLING
            || current == StealthState.UNFREEZING
        ) {
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
    suspend fun freezeOnly(): Boolean = reconcileVpnState()

    suspend fun freezeVpnOnly(): Boolean = reconcileVpnState()

    suspend fun reconcileVpnState(): Boolean {
        if (_paused.value) {
            AppLogger.i(TAG, "VPN policy skipped: orchestrator paused")
            return false
        }
        _lastError.value = null
        if (!checkShizuku()) return false
        return applyManagedStateForVpn()
    }

    /** Boot freeze and ongoing monitoring use the same lock and network policy. */
    suspend fun freezeOnBoot(): Boolean {
        if (!checkShizuku()) return false
        return applyManagedStateForVpn(freezeOnBoot = true)
    }

    /**
     * Launch app from LAUNCH_VPN or VPN_ONLY group:
     * freeze LOCAL → start VPN → unfreeze this app → launch.
     */
    suspend fun launchWithVpn(packageName: String, vpnClient: SelectedVpnClient) {
        _lastError.value = null

        vpnClientManager.refreshVpnState()
        if (!vpnClientManager.isVpnAvailableForLaunch(true)) enable(vpnClient)
        launchGuarded(packageName, requiresVpn = true)
    }

    /** Launch a local app only after confirming VPN shutdown. */
    suspend fun launchLocal(
        packageName: String,
        vpnClient: SelectedVpnClient,
        detectedPackage: String?
    ) {
        _lastError.value = null
        vpnClientManager.refreshVpnState()
        if (!vpnClientManager.isVpnAvailableForLaunch(false)) disable(vpnClient, detectedPackage)
        launchGuarded(packageName, requiresVpn = false)
    }

    private suspend fun launchGuarded(packageName: String, requiresVpn: Boolean) {
        if (!checkShizuku() || !applyManagedStateForVpn()) return
        val mutex = (context.applicationContext as AnubisApp).groupOpMutex
        mutex.withLock {
            fun allowed(): Boolean {
                vpnClientManager.refreshVpnState()
                return vpnClientManager.isVpnAvailableForLaunch(requiresVpn)
            }
            if (!allowed()) {
                _lastError.value = if (requiresVpn) {
                    "VPN не подключён. Приложение не запущено. Проверьте VPN-клиент и включение Anubis в его список приложений."
                } else {
                    "Не удалось отключить VPN. Приложение не запущено. Проверьте, что Anubis имеет разрешение VPN, и что выбран правильный VPN-клиент."
                }
                logShizukuFailure("launchGuarded.freezeApp", packageName, shizukuManager.freezeApp(packageName))
                return@withLock
            }
            var launched = false
            try {
                if (shizukuManager.isAppFrozen(packageName)) {
                    val result = shizukuManager.unfreezeApp(packageName)
                    logShizukuFailure("launchGuarded.unfreezeApp", packageName, result)
                    if (result.isFailure) return@withLock
                    bumpVersion()
                }
                // Recheck after the binder operation AND after launcher-intent polling.
                launched = vpnClientManager.launchApp(packageName, ::allowed)
                if (!launched) _lastError.value = "Запуск отменён: VPN изменился или приложение недоступно."
            } finally {
                // Cancellation (including Activity destruction) must not leave the target enabled.
                if (!launched) withContext(NonCancellable) {
                    logShizukuFailure("launchGuarded.refreezeApp", packageName, shizukuManager.freezeApp(packageName))
                    bumpVersion()
                }
            }
        }
    }

    /**
     * Manual freeze/unfreeze from context menu.
     */
    suspend fun toggleAppFrozen(packageName: String) {
        if (!checkShizuku()) return
        if (shizukuManager.isAppFrozen(packageName)) {
            val result = shizukuManager.unfreezeApp(packageName)
            logShizukuFailure("toggleAppFrozen.unfreezeApp", packageName, result)
        } else {
            val result = shizukuManager.freezeApp(packageName)
            logShizukuFailure("toggleAppFrozen.freezeApp", packageName, result)
        }
        bumpVersion()
    }

    // --- Helpers ---

    private fun bumpVersion() {
        _frozenVersion.value++
    }

    private suspend fun stopVpn(client: SelectedVpnClient, detectedPackage: String?): Boolean {
        AppLogger.i(TAG, "Stopping VPN requested: client=${client.packageName}, detected=${detectedPackage.orEmpty()}")
        // Step 1: API stop — only for SEPARATE mode (explicit stop command).
        // TOGGLE is unreliable for stop (can re-enable immediately), skip to dummy VPN.
        if (client.controlMode == VpnControlMode.SEPARATE) {
            vpnClientManager.stopVPN(client)
            if (waitForVpnOff(VPN_OFF_WAIT_AFTER_API_STOP_MS)) {
                AppLogger.i(TAG, "VPN stopped after API stop: ${client.packageName}")
                return true
            }
        }

        // Step 2: Dummy VPN — take over as VPN, system kills theirs
        StealthVpnService.disconnect(context)
        if (waitForVpnOff(VPN_OFF_WAIT_AFTER_DUMMY_MS)) {
            AppLogger.i(TAG, "VPN stopped after dummy takeover: ${client.packageName}")
            return true
        }

        // Step 3: Force-stop the detected VPN app
        val pkg = detectedPackage ?: client.packageName
        shizukuManager.forceStopApp(pkg)
            .also { logShizukuFailure("stopVpn.forceStopApp", pkg, it) }
        val stopped = waitForVpnOff(VPN_OFF_WAIT_AFTER_FORCE_STOP_MS)
        if (stopped) {
            AppLogger.i(TAG, "VPN stopped after force-stop: $pkg")
        } else {
            AppLogger.e(TAG, "VPN is still active after stop sequence: $pkg")
        }
        return stopped
    }

    private suspend fun checkShizuku(): Boolean {
        if (!shizukuManager.isAvailable()) { _lastError.value = "Shizuku не запущен."; return false }
        if (!shizukuManager.hasPermission()) { _lastError.value = "Нет разрешения Shizuku."; return false }
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
                            val result = shizukuManager.freezeApp(pkg)
                            logShizukuFailure("freezeGroup.freezeApp", pkg, result)
                            if (result.isSuccess) {
                                counter.incrementAndGet()
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        return counter.get()
    }

    private suspend fun isGroupFrozen(group: AppGroup): Boolean {
        val packages = repository.getPackagesByGroup(group)
        if (packages.isEmpty()) return true
        for (pkg in packages) {
            if (shizukuManager.isAppInstalled(pkg) && !shizukuManager.isAppFrozen(pkg)) {
                _lastError.value = "Не удалось заморозить $pkg."
                return false
            }
        }
        val persisted = shizukuManager.persistPackageState()
        logShizukuFailure("isGroupFrozen.persistPackageState", group.name, persisted)
        return persisted.isSuccess
    }

    private suspend fun unfreezeGroup(group: AppGroup): Int {
        val packages = repository.getPackagesByGroup(group).toList()
        if (AppSettings.shouldUseLauncherSafeMode(context)) {
            var transitioned = 0
            packages.forEachIndexed { index, pkg ->
                if (!vpnClientManager.vpnActive.value && !StealthVpnService.dummyVpnInFlight &&
                    shizukuManager.isAppInstalled(pkg) && shizukuManager.isAppFrozen(pkg)) {
                    val result = shizukuManager.unfreezeApp(pkg)
                    logShizukuFailure("unfreezeGroup.unfreezeApp", pkg, result)
                    if (result.isSuccess) {
                        transitioned++
                    }
                }
                // On MIUI/HyperOS and some OEM launchers, bursts of package-enabled events
                // are handled as repeated "new app" inserts and produce duplicate icons.
                if (index < packages.lastIndex) {
                    delay(LAUNCHER_SAFE_UNFREEZE_DELAY_MS)
                }
            }
            return transitioned
        }
        val sem = Semaphore(FREEZE_CONCURRENCY)
        val counter = AtomicInteger(0)
        coroutineScope {
            packages.map { pkg ->
                async {
                    sem.withPermit {
                        if (!vpnClientManager.vpnActive.value && !StealthVpnService.dummyVpnInFlight &&
                            shizukuManager.isAppInstalled(pkg) && shizukuManager.isAppFrozen(pkg)) {
                            val result = shizukuManager.unfreezeApp(pkg)
                            logShizukuFailure("unfreezeGroup.unfreezeApp", pkg, result)
                            if (result.isSuccess) {
                                counter.incrementAndGet()
                            }
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
        AppLogger.i(TAG, msg)
        _benchmark.tryEmit(msg)
    }

    private suspend fun applyManagedStateForVpn(freezeOnBoot: Boolean = false): Boolean {
        val mutex = (context.applicationContext as AnubisApp).groupOpMutex
        val benchStart = System.currentTimeMillis()
        var frozen = 0
        var unfrozen = 0
        var succeeded = true
        mutex.withLock {
            suspend fun freeze(group: AppGroup) {
                frozen += freezeGroup(group)
                if (!isGroupFrozen(group)) succeeded = false
            }
            if (freezeOnBoot) {
                freeze(AppGroup.LOCAL)
                freeze(AppGroup.VPN_ONLY)
            }
            // Read after acquiring the lock: a queued operation may outlive a VPN change.
            val active = vpnClientManager.isExternalVpnActive() || StealthVpnService.dummyVpnInFlight
            if (active) {
                freeze(AppGroup.LOCAL)
                freeze(AppGroup.LOCAL_AUTO_UNFREEZE)
                _state.value = StealthState.ENABLED
            } else {
                freeze(AppGroup.VPN_ONLY)
                unfrozen += unfreezeGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
                freezeSelectedVpnClientIfNeeded()
                _state.value = StealthState.DISABLED
            }
        }
        emitBenchmark(frozen = frozen, unfrozen = unfrozen, startMs = benchStart)
        bumpVersion()
        return succeeded
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
            if (vpnClientManager.isVpnAvailableForLaunch(true)) return true
        }
        return false
    }

    private suspend fun fail(message: String) {
        _progressText.value = null
        // A failed start/stop does not prove that an existing tunnel disappeared.
        applyManagedStateForVpn()
        _lastError.value = message
    }

    private suspend fun freezeSelectedVpnClientIfNeeded() {
        val client = AppSettings.loadSelectedVpnClient(context)
        if (!shouldFreezeClientInIdle(client.packageName)) return
        if (!shizukuManager.awaitUserService(500)) return
        val result = shizukuManager.freezeApp(client.packageName)
        logShizukuFailure("freezeSelectedVpnClientIfNeeded.freezeApp", client.packageName, result)
    }

    private fun logShizukuFailure(op: String, packageName: String, result: Result<Unit>) {
        if (result.isFailure) {
            _lastError.value = "$op: $packageName: ${result.exceptionOrNull()?.message}"
            AppLogger.e(TAG, "$op failed for package=$packageName", result.exceptionOrNull())
        }
    }

    private fun shouldFreezeClientInIdle(packageName: String): Boolean =
        packageName.isNotBlank() && VpnClientControls.getControlForPackage(packageName).freezeInIdle

    private companion object {
        private const val TAG = "StealthOrchestrator"
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

        // Same ceiling for MANUAL clients (Hiddify, AmneziaWG without API control, etc.)
        // — the user has to open the VPN UI and tap "Connect" themselves, which can
        // realistically take up to a minute on cold-start (server selection, handshake).
        // 120 s leaves a buffer for slow OEMs without making the user wait absurdly long
        // before a timeout is surfaced.
        const val MANUAL_TOTAL_OP_TIMEOUT_MS = 120_000L

        // waitForVpnOn timeout for clients we control via API (start/stop intent or
        // toggle). VPN should be up within seconds; 10 s covers slow handshakes.
        private const val AUTO_VPN_WAIT_MS = 10_000L

        // waitForVpnOn timeout for MANUAL clients — the user has to connect the VPN
        // themselves. 90 s is the budget to open the client, pick a server, tap Connect
        // and finish the handshake. Total enable() ceiling (MANUAL_TOTAL_OP_TIMEOUT_MS)
        // is sized to absorb this on top of freeze time.
        private const val MANUAL_VPN_WAIT_MS = 90_000L

        private const val VPN_OFF_WAIT_AFTER_API_STOP_MS = 3_000L
        private const val VPN_OFF_WAIT_AFTER_DUMMY_MS = 2_000L
        private const val VPN_OFF_WAIT_AFTER_FORCE_STOP_MS = 2_000L

        // Launcher-safe profile: serialize mass-unfreeze to avoid duplicate launcher icons.
        const val LAUNCHER_SAFE_UNFREEZE_DELAY_MS = 180L
    }
}

enum class StealthState {
    DISABLED,
    ENABLING,
    ENABLED,
    DISABLING,
    UNFREEZING
}


