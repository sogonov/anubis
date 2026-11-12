package sgnv.anubis.app.vpn

/** Resolve only an external VPN belonging to the caller's Android profile. */
internal object VpnOwnerResolver {
    // Match transport tokens only; VPN may also appear in a Wi-Fi SSID.
    private val vpn = Regex(
        """Transports:\s*(?:[A-Z_]+\|)*VPN(?:\||\s|$)|type: VPN\[|ni\{VPN\s"""
    )
    private val owner = Regex("OwnerUid:\\s*(\\d+)")

    fun ownerUid(dump: String, ownUid: Int): Int? =
        dump.lineSequence()
            .filter { it.trimStart().startsWith("NetworkAgentInfo{") && vpn.containsMatchIn(it) }
            .mapNotNull { owner.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull { it != ownUid && it / 100_000 == ownUid / 100_000 }
}
