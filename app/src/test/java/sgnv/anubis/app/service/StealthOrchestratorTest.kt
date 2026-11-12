package sgnv.anubis.app.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientManager

class StealthOrchestratorTest {
    private val app = mock(AnubisApp::class.java)
    private val shizuku = mock(ShizukuManager::class.java)
    private val vpn = mock(VpnClientManager::class.java)
    private val repo = mock(AppRepository::class.java)
    private val prefs = mock(SharedPreferences::class.java)
    private val active = MutableStateFlow(false)
    private val mutex = Mutex()
    private val frozen = mutableSetOf<String>()
    private val launches = mutableListOf<String>()
    private val client = SelectedVpnClient.fromPackage("org.amnezia.vpn")
    private lateinit var orchestrator: StealthOrchestrator

    @Before fun setUp() = runBlocking<Unit> {
        `when`(app.applicationContext).thenReturn(app)
        `when`(app.groupOpMutex).thenReturn(mutex)
        `when`(app.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        `when`(app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.getString(AppSettings.KEY_VPN_CLIENT_PACKAGE, null)).thenReturn(client.packageName)
        `when`(vpn.vpnActive).thenReturn(active)
        `when`(vpn.isExternalVpnActive()).thenAnswer { active.value }
        `when`(vpn.isVpnAvailableForLaunch(true)).thenAnswer { active.value }
        `when`(vpn.isVpnAvailableForLaunch(false)).thenAnswer { !active.value }
        `when`(vpn.startVPN(client)).thenReturn("Test VPN did not connect")
        `when`(shizuku.isAvailable()).thenReturn(true)
        `when`(shizuku.hasPermission()).thenReturn(true)
        `when`(shizuku.isAppInstalled(anyString())).thenReturn(true)
        `when`(shizuku.isAppFrozen(anyString())).thenAnswer { it.getArgument<String>(0) in frozen }
        doAnswer { frozen.add(it.getArgument(0)); Unit }.`when`(shizuku).freezeApp(anyString())
        doAnswer { frozen.remove(it.getArgument<String>(0)); Unit }.`when`(shizuku).unfreezeApp(anyString())
        doAnswer { Unit }.`when`(shizuku).persistPackageState()
        for (group in AppGroup.entries) {
            `when`(repo.getPackagesByGroup(group)).thenReturn(when (group) {
                AppGroup.LOCAL -> setOf("local")
                AppGroup.VPN_ONLY -> setOf("vpn")
                AppGroup.LOCAL_AUTO_UNFREEZE -> setOf("auto")
                else -> emptySet()
            })
        }
        doAnswer {
            val allowed = it.getArgument<() -> Boolean>(1)
            if (allowed()) { launches.add(it.getArgument(0)); true } else false
        }.`when`(vpn).launchApp(anyString(), anyGuard())
        orchestrator = StealthOrchestrator(app, shizuku, vpn, repo)
    }

    // Mockito's matcher must have a non-null Kotlin function value at the call site.
    private fun anyGuard(): () -> Boolean { any<() -> Boolean>(); return { true } }

    @Test fun `freeze persistence failure is retried even when all apps already look frozen`() = runBlocking<Unit> {
        val failure = Result.failure<Unit>(IllegalStateException("flush failed"))
        val rawFailure = Result::class.java.getDeclaredMethod("unbox-impl").invoke(failure)
        doAnswer { rawFailure }.`when`(shizuku).persistPackageState()
        assertFalse(orchestrator.reconcileVpnState())
        assertTrue("vpn" in frozen)
        doAnswer { Unit }.`when`(shizuku).persistPackageState()
        assertTrue(orchestrator.reconcileVpnState())
        verify(shizuku, times(2)).persistPackageState()
    }

    @Test fun `boot with already connected VPN never thaws local auto apps`() = runBlocking<Unit> {
        active.value = true
        frozen.add("auto")
        assertTrue(orchestrator.freezeOnBoot())
        assertTrue(frozen.containsAll(setOf("local", "vpn", "auto")))
        verify(shizuku, never()).unfreezeApp("auto")
    }

    @Test fun `boot without VPN freezes both managed groups and restores local notifications`() = runBlocking<Unit> {
        frozen.add("auto")
        assertTrue(orchestrator.freezeOnBoot())
        assertEquals(setOf("local", "vpn"), frozen)
    }

    @Test fun `queued boot policy uses VPN state after acquiring the shared lock`() = runBlocking<Unit> {
        mutex.lock()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { orchestrator.freezeOnBoot() }
        active.value = true
        mutex.unlock()
        assertTrue(pending.await())
        assertTrue("auto" in frozen)
        verify(shizuku, never()).unfreezeApp("auto")
    }

    @Test fun `stale enabled UI does not authorize launch after VPN loss`() = runBlocking<Unit> {
        active.value = true
        orchestrator.syncState()
        active.value = false
        frozen.add("vpn")
        orchestrator.launchWithVpn("vpn", client)
        assertTrue(launches.isEmpty())
        assertTrue("vpn" in frozen)
        verify(shizuku, never()).unfreezeApp("vpn")
    }

    @Test fun `VPN lost during unfreeze prevents launch and refreezes target`() = runBlocking<Unit> {
        active.value = true
        frozen.add("vpn")
        doAnswer { frozen.remove("vpn"); active.value = false; Unit }.`when`(shizuku).unfreezeApp("vpn")
        orchestrator.launchWithVpn("vpn", client)
        assertTrue(launches.isEmpty())
        assertTrue("vpn" in frozen)
    }

    @Test fun `unfreeze failure prevents launch and remains visible`() = runBlocking<Unit> {
        active.value = true
        frozen.add("vpn")
        val failure = Result.failure<Unit>(IllegalStateException("binder denied"))
        val rawFailure = Result::class.java.getDeclaredMethod("unbox-impl").invoke(failure)
        doAnswer { rawFailure }.`when`(shizuku).unfreezeApp("vpn")
        orchestrator.launchWithVpn("vpn", client)
        assertTrue(launches.isEmpty())
        assertTrue(orchestrator.lastError.value!!.contains("binder denied"))
    }

    @Test fun `missing Shizuku never unfreezes or launches target`() = runBlocking<Unit> {
        active.value = true
        `when`(shizuku.isAvailable()).thenReturn(false)
        orchestrator.launchWithVpn("vpn", client)
        assertTrue(launches.isEmpty())
        verify(shizuku, never()).unfreezeApp(anyString())
    }

    @Test fun `freeze failure is not reported as successful policy`() = runBlocking<Unit> {
        doAnswer { Unit }.`when`(shizuku).freezeApp("vpn")
        assertFalse(orchestrator.reconcileVpnState())
        assertNotNull(orchestrator.lastError.value)
    }

    @Test fun `cancelled launch refreezes target before releasing the policy lock`() = runBlocking<Unit> {
        active.value = true
        frozen.add("vpn")
        doAnswer { throw kotlinx.coroutines.CancellationException("activity closed") }
            .`when`(vpn).launchApp(anyString(), anyGuard())
        try {
            orchestrator.launchWithVpn("vpn", client)
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertTrue("vpn" in frozen)
            assertFalse(mutex.isLocked)
        }
    }

    @Test fun `failed local freeze prevents protected app launch`() = runBlocking<Unit> {
        active.value = true
        frozen.add("vpn")
        doAnswer { Unit }.`when`(shizuku).freezeApp("local")
        orchestrator.launchWithVpn("vpn", client)
        assertTrue(launches.isEmpty())
        assertTrue("vpn" in frozen)
    }

    @Test fun `failed VPN start does not thaw local apps while a VPN remains connected`() = runBlocking<Unit> {
        active.value = true
        frozen.add("auto")
        orchestrator.enable(client)
        assertTrue("auto" in frozen)
        verify(shizuku, never()).unfreezeApp("auto")
    }

    @Test fun `successful VPN launch keeps local apps frozen`() = runBlocking<Unit> {
        active.value = true
        frozen.add("vpn")
        orchestrator.launchWithVpn("vpn", client)
        assertEquals(listOf("vpn"), launches)
        assertEquals(setOf("local", "auto"), frozen)
    }
}
