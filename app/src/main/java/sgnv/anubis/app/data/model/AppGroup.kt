package sgnv.anubis.app.data.model

/**
 * Groups for managed apps:
 *
 * LOCAL — "No VPN" group. Frozen when VPN is ON.
 *         Only work on direct connection.
 *
 * LOCAL_AUTO_UNFREEZE — "No VPN + notifications" group.
 *         Frozen when VPN is ON, auto-unfrozen when VPN is OFF.
 *         Use case: banks, MAX, marketplaces — hidden while VPN is up,
 *         can deliver notifications the rest of the time.
 *
 * VPN_ONLY — "VPN only" group. Frozen when VPN is OFF.
 *            Only work through VPN.
 *
 * LAUNCH_VPN — "With VPN" group. Never frozen, but launching one
 *              triggers: freeze LOCAL → start VPN → open app.
 */
enum class AppGroup {
    LOCAL,
    LOCAL_AUTO_UNFREEZE,
    VPN_ONLY,
    LAUNCH_VPN
}
