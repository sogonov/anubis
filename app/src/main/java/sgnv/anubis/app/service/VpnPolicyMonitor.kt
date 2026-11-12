package sgnv.anubis.app.service

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.util.AppLogger

/** Serial policy application, including boot and recovery without another network event. */
internal class VpnPolicyMonitor(
    private val app: AnubisApp,
    private val isExternalVpn: (Network) -> Boolean,
    private val onUpdate: () -> Unit,
    private val dummyVpnState: StateFlow<Boolean> = StealthVpnService.dummyVpnState,
) {
    val bootPending = MutableStateFlow(false)

    private data class State(
        val active: Boolean, val ready: Boolean, val paused: Boolean,
        val boot: Boolean, val dummy: Boolean,
    )

    suspend fun run() {
        combine(
            app.vpnClientManager.applicableVpnNetworks, app.shizukuManager.status,
            app.orchestrator.paused, bootPending, dummyVpnState,
        ) { networks, status, paused, boot, dummy ->
            State(networks.any(isExternalVpn), status == ShizukuStatus.READY, paused, boot, dummy)
        }.distinctUntilChanged().collectLatest { state ->
            if (state.dummy) return@collectLatest
            if (!state.ready || (state.paused && !state.boot)) {
                onUpdate()
                return@collectLatest
            }
            // Failed freezes must not be remembered as an applied policy. Retry while
            // the state is unchanged; a new event cancels the obsolete attempt.
            var retryDelay = 1_000L
            while (true) {
                val applied = try {
                    // Two concurrent binder ops at 5 s each: 30 apps take up to 75 s.
                    withTimeoutOrNull(90_000L) {
                        if (state.boot) app.orchestrator.freezeOnBoot()
                        else app.orchestrator.reconcileVpnState()
                    } == true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("VpnPolicyMonitor", "VPN policy failed", e)
                    false
                }
                if (applied) {
                    if (state.boot) bootPending.value = false
                    onUpdate()
                    return@collectLatest
                }
                onUpdate()
                AppLogger.e("VpnPolicyMonitor", "VPN policy incomplete; retrying")
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }
}
