package sgnv.anubis.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnOwnerResolverTest {
    private val work = "NetworkAgentInfo{network{518} ni{VPN CONNECTED} nc{Transports: CELLULAR|VPN OwnerUid: 1010364}}"
    private val personal = "NetworkAgentInfo{network{508} ni{VPN CONNECTED} nc{Transports: CELLULAR|VPN OwnerUid: 10332}}"
    private val dummy = "NetworkAgentInfo{network{519} ni{VPN CONNECTED} nc{Transports: VPN OwnerUid: 10401}}"

    @Test
    fun `work VPN is not a personal VPN owner`() {
        assertNull(VpnOwnerResolver.ownerUid(work, 10401))
    }

    @Test
    fun `personal owner wins even when work and dummy entries come first`() {
        assertEquals(10332, VpnOwnerResolver.ownerUid("$work\n$dummy\n$personal", 10401))
    }

    @Test
    fun `owner detection also works when Anubis runs in a work profile`() {
        assertEquals(1010364, VpnOwnerResolver.ownerUid("$personal\n$work", 1010401))
    }

    @Test
    fun `legacy VPN format remains supported`() {
        val legacy = "NetworkAgentInfo{ ni{type: VPN[]} nc{OwnerUid: 10332}}"
        assertEquals(10332, VpnOwnerResolver.ownerUid(legacy, 10401))
    }

    @Test
    fun `ordinary NOT_VPN network is not a VPN owner`() {
        val wifi = "NetworkAgentInfo{network{500} nc{Transports: WIFI Capabilities: INTERNET&NOT_VPN OwnerUid: 10332}}"
        assertNull(VpnOwnerResolver.ownerUid(wifi, 10401))
    }

    @Test
    fun `a WiFi SSID containing VPN is not a VPN transport`() {
        val wifi = """NetworkAgentInfo{network{102} ni{WIFI CONNECTED} nc{Transports: WIFI Capabilities: INTERNET&NOT_VPN TransportInfo: <SSID: "Home VPN"> OwnerUid: 1000}}"""
        assertEquals(10332, VpnOwnerResolver.ownerUid("$wifi\n$personal", 10401))
    }
}
