package sgnv.anubis.app.policy

import sgnv.anubis.app.data.NeverRestrictApps
import sgnv.anubis.app.vpn.VpnClientType

/**
 * Single source of truth for "do not freeze blindly" rules.
 */
object FreezeSafetyPolicy {

    private val vpnClientPackages: Set<String> = VpnClientType.entries
        .mapTo(mutableSetOf()) { it.packageName }

    fun isProtected(packageName: String): Boolean =
        NeverRestrictApps.isNeverRestrict(packageName) || packageName in vpnClientPackages

    fun findProtected(packageNames: Collection<String>): List<String> =
        packageNames
            .distinct()
            .filter { pkg -> isProtected(pkg) }
}
