package sgnv.anubis.app.service

import android.net.Network
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.vpn.VpnClientManager

@OptIn(ExperimentalCoroutinesApi::class)
class VpnPolicyMonitorTest {
    private val app = mock(AnubisApp::class.java)
    private val vpn = mock(VpnClientManager::class.java)
    private val shizuku = mock(ShizukuManager::class.java)
    private val orchestrator = mock(StealthOrchestrator::class.java)
    private val networks = MutableStateFlow<Set<Network>>(emptySet())
    private val status = MutableStateFlow(ShizukuStatus.READY)
    private val paused = MutableStateFlow(false)
    private val dummy = MutableStateFlow(false)
    private lateinit var monitor: VpnPolicyMonitor

    @Before fun setUp() = runTest {
        `when`(app.vpnClientManager).thenReturn(vpn)
        `when`(app.shizukuManager).thenReturn(shizuku)
        `when`(app.orchestrator).thenReturn(orchestrator)
        `when`(vpn.applicableVpnNetworks).thenReturn(networks)
        `when`(shizuku.status).thenReturn(status)
        `when`(orchestrator.paused).thenReturn(paused)
        `when`(orchestrator.reconcileVpnState()).thenReturn(true)
        `when`(orchestrator.freezeOnBoot()).thenReturn(true)
        monitor = VpnPolicyMonitor(app, { true }, {}, dummy)
    }

    @Test fun `boot waits for late Shizuku without losing boot freeze request`() = runTest {
        status.value = ShizukuStatus.NOT_RUNNING
        monitor.bootPending.value = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.run() }
        advanceTimeBy(60_000)
        verify(orchestrator, never()).freezeOnBoot()
        assertTrue(monitor.bootPending.value)
        status.value = ShizukuStatus.READY
        runCurrent()
        verify(orchestrator).freezeOnBoot()
        assertFalse(monitor.bootPending.value)
    }

    @Test fun `unpausing reapplies policy without another network change`() = runTest {
        paused.value = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.run() }
        verify(orchestrator, never()).reconcileVpnState()
        paused.value = false
        runCurrent()
        verify(orchestrator).reconcileVpnState()
    }

    @Test fun `dummy grace ending reapplies policy without another network change`() = runTest {
        dummy.value = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.run() }
        verify(orchestrator, never()).reconcileVpnState()
        dummy.value = false
        runCurrent()
        verify(orchestrator).reconcileVpnState()
    }

    @Test fun `Shizuku recovery reapplies unchanged VPN state`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.run() }
        status.value = ShizukuStatus.NOT_RUNNING
        runCurrent()
        status.value = ShizukuStatus.READY
        runCurrent()
        verify(orchestrator, times(2)).reconcileVpnState()
    }

    @Test fun `failed freeze retries instead of caching success`() = runTest {
        `when`(orchestrator.reconcileVpnState()).thenReturn(false, true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.run() }
        advanceTimeBy(1_000)
        runCurrent()
        verify(orchestrator, times(2)).reconcileVpnState()
    }

    @Test fun `unsuccessful boot freeze remains pending until a successful retry`() = runTest {
        monitor.bootPending.value = true
        `when`(orchestrator.freezeOnBoot()).thenReturn(false, true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.run() }
        assertTrue(monitor.bootPending.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(monitor.bootPending.value)
        verify(orchestrator, times(2)).freezeOnBoot()
    }

    @Test fun `policy exception does not permanently kill monitoring`() = runTest {
        `when`(orchestrator.reconcileVpnState()).thenThrow(IllegalStateException("binder lost")).thenReturn(true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.run() }
        advanceTimeBy(1_000)
        runCurrent()
        verify(orchestrator, times(2)).reconcileVpnState()
    }

    @Test fun `VPN loss is applied even after a previous successful policy`() = runTest {
        networks.value = setOf(mock(Network::class.java))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.run() }
        networks.value = emptySet()
        runCurrent()
        verify(orchestrator, times(2)).reconcileVpnState()
    }
}
