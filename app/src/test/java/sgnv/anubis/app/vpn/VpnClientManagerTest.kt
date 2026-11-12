package sgnv.anubis.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.runBlocking
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.MockedConstruction
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.util.AppLogger

class VpnClientManagerTest {
    private val connectivity = mock(ConnectivityManager::class.java)
    private val context = mock(Context::class.java)
    private val amnezia = mock(Network::class.java)
    private val workOpenVpn = mock(Network::class.java)
    private val vpnCapabilities = mock(NetworkCapabilities::class.java)
    private lateinit var builders: MockedConstruction<NetworkRequest.Builder>
    private lateinit var manager: VpnClientManager

    @Before
    fun setUp() {
        builders = mockConstruction(NetworkRequest.Builder::class.java,
            withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            `when`(builder.build()).thenReturn(mock(NetworkRequest::class.java))
        }
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivity)
        `when`(vpnCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        `when`(connectivity.getNetworkCapabilities(amnezia)).thenReturn(vpnCapabilities)
        `when`(connectivity.getNetworkCapabilities(workOpenVpn)).thenReturn(vpnCapabilities)
        manager = VpnClientManager(context, mock(ShizukuManager::class.java))
    }

    @After
    fun tearDown() {
        manager.stopMonitoringVpn()
        builders.close()
    }

    @Test
    fun `work profile VPN alone does not enable personal apps`() {
        `when`(connectivity.allNetworks).thenReturn(arrayOf(workOpenVpn))
        `when`(connectivity.activeNetwork).thenReturn(null)

        manager.startMonitoringVpn()
        manager.refreshVpnState()

        assertFalse(manager.vpnActive.value)
    }

    @Test
    fun `losing Amnezia disables personal VPN state while work VPN remains`() {
        // getAllNetworks can still include the lost network inside onLost.
        `when`(connectivity.allNetworks).thenReturn(arrayOf(workOpenVpn, amnezia))
        `when`(connectivity.activeNetwork).thenReturn(amnezia)
        val callback = startMonitoring()
        callback.onAvailable(amnezia)
        assertTrue(manager.vpnActive.value)

        callback.onLost(amnezia)

        assertFalse(manager.vpnActive.value)
    }

    @Test
    fun `VPN handover keeps the replacement network active`() {
        val replacement = mock(Network::class.java)
        val callback = startMonitoring()
        callback.onAvailable(amnezia)
        callback.onAvailable(replacement)

        callback.onLost(amnezia)
        assertTrue(manager.vpnActive.value)

        callback.onLost(replacement)
        assertFalse(manager.vpnActive.value)
    }

    @Test
    fun `registration failure remains visible in the journal and can be retried`() {
        val failure = IllegalStateException("VPN callback registration rejected")
        doThrow(failure).doNothing().`when`(connectivity).registerNetworkCallback(
            any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java)
        )
        val journalSize = AppLogger.lines.value.size

        manager.startMonitoringVpn()

        assertTrue(AppLogger.lines.value.drop(journalSize).any { it.contains(failure.message!!) })
        manager.startMonitoringVpn()
        verify(connectivity, times(2)).registerNetworkCallback(
            any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java)
        )
    }

    @Test
    fun `refresh cannot resurrect a lost VPN from a stale system snapshot`() {
        `when`(connectivity.activeNetwork).thenReturn(amnezia)
        val callback = startMonitoring()
        callback.onLost(amnezia)
        manager.refreshVpnState()
        assertFalse(manager.vpnActive.value)
        callback.onAvailable(amnezia)
        assertTrue(manager.vpnActive.value)
    }

    @Test
    fun `launch guard runs after package manager lookup`() = runBlocking<Unit> {
        val pm = mock(PackageManager::class.java)
        val intent = mock(Intent::class.java)
        var permitted = true
        `when`(context.packageManager).thenReturn(pm)
        `when`(pm.getLaunchIntentForPackage("target")).thenAnswer {
            permitted = false
            intent
        }
        assertFalse(manager.launchApp("target") { permitted })
        verify(context, org.mockito.Mockito.never()).startActivity(any(Intent::class.java))
    }

    @Test
    fun `launch with valid final guard starts the activity`() = runBlocking<Unit> {
        val pm = mock(PackageManager::class.java)
        val intent = mock(Intent::class.java)
        `when`(context.packageManager).thenReturn(pm)
        `when`(pm.getLaunchIntentForPackage("target")).thenReturn(intent)
        assertTrue(manager.launchApp("target") { true })
        verify(context).startActivity(intent)
    }

    @Test
    fun `own dummy remains visible for shutdown but cannot authorize a VPN launch`() {
        val properties = mock(android.net.LinkProperties::class.java)
        val address = mock(android.net.LinkAddress::class.java)
        `when`(address.address).thenReturn(java.net.InetAddress.getByName("198.18.0.1"))
        `when`(properties.linkAddresses).thenReturn(listOf(address))
        `when`(connectivity.getLinkProperties(amnezia)).thenReturn(properties)
        `when`(connectivity.activeNetwork).thenReturn(amnezia)
        manager.startMonitoringVpn()
        assertTrue(manager.vpnActive.value)
        assertFalse(manager.isExternalVpnActive())
    }

    @Test
    fun `refresh during handover preserves the already announced replacement`() {
        `when`(connectivity.activeNetwork).thenReturn(amnezia)
        val callback = startMonitoring()
        val replacement = mock(Network::class.java)
        callback.onAvailable(replacement)
        manager.refreshVpnState() // default route still points to the old tunnel
        callback.onLost(amnezia)
        assertTrue(replacement in manager.applicableVpnNetworks.value)
    }

    @Test
    fun `launch rejects VPN loss before onLost arrives`() {
        `when`(connectivity.activeNetwork).thenReturn(amnezia)
        manager.startMonitoringVpn()
        `when`(connectivity.activeNetwork).thenReturn(null)
        assertFalse(manager.isVpnAvailableForLaunch(true))
        assertFalse(manager.isVpnAvailableForLaunch(false)) // still awaiting onLost
    }

    @Test
    fun `local launch rejects VPN availability before onAvailable arrives`() {
        manager.startMonitoringVpn()
        `when`(connectivity.activeNetwork).thenReturn(amnezia)
        assertFalse(manager.isVpnAvailableForLaunch(false))
        assertFalse(manager.isVpnAvailableForLaunch(true)) // still awaiting onAvailable
    }

    private fun startMonitoring(): ConnectivityManager.NetworkCallback {
        manager.startMonitoringVpn()
        val request = ArgumentCaptor.forClass(NetworkRequest::class.java)
        val callback = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(connectivity).registerNetworkCallback(request.capture(), callback.capture())
        return callback.value
    }
}
