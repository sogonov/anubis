package sgnv.anubis.app.vpn

enum class VpnClientType(
    val displayName: String,
    val packageName: String,
    /** null = standalone; same string = variants of one brand (grouped in picker). */
    val brand: String? = null,
) {
    V2RAY_NG("Play", "com.v2ray.ang", brand = "v2rayNG"),
    V2RAY_NG_FDROID("F-Droid", "com.v2ray.ang.fdroid", brand = "v2rayNG"),
    NEKO_BOX("NekoBox", "moe.nb4a"),
    HAPP("Play", "com.happproxy", brand = "Happ"),
    HAPP_GITHUB("Github", "su.happ.proxyutility", brand = "Happ"),
    V2RAY_TUN("v2rayTun", "com.v2raytun.android"),
    V2BOX("V2Box", "dev.hexasoftware.v2box");

    /** "v2rayNG (Play)" for branded variants, "NekoBox" for standalones. */
    val fullDisplayName: String
        get() = if (brand != null) "$brand ($displayName)" else displayName

    companion object {
        fun fromPackageName(pkg: String): VpnClientType? =
            entries.find { it.packageName == pkg }
    }
}

/**
 * Selected VPN client — either a known type with API, or a custom package (MANUAL mode).
 */
data class SelectedVpnClient(
    val knownType: VpnClientType? = null,
    val packageName: String
) {
    val displayName: String
        get() = knownType?.fullDisplayName ?: packageName.substringAfterLast('.')

    val controlMode: VpnControlMode
        get() = if (knownType != null) VpnClientControls.getControl(knownType).mode else VpnControlMode.MANUAL

    companion object {
        fun fromKnown(type: VpnClientType) = SelectedVpnClient(type, type.packageName)
        fun fromPackage(pkg: String) = SelectedVpnClient(VpnClientType.fromPackageName(pkg), pkg)
    }
}

/**
 * How a VPN client can be controlled:
 * - SEPARATE: distinct start and stop commands (best for orchestration)
 * - TOGGLE: single command that toggles on/off (for stop: toggle + force-stop fallback)
 * - MANUAL: no external control API — just open the app
 */
enum class VpnControlMode { SEPARATE, TOGGLE, MANUAL }

data class VpnClientControl(
    val clientType: VpnClientType,
    val mode: VpnControlMode,
    /** Shell command to start VPN (SEPARATE/TOGGLE). Null for MANUAL. */
    val startCommand: Array<String>? = null,
    /** Shell command to stop VPN. Only for SEPARATE mode. */
    val stopCommand: Array<String>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VpnClientControl) return false
        return clientType == other.clientType
    }

    override fun hashCode() = clientType.hashCode()
}

object VpnClientControls {

    private val controls = mapOf(
        // v2rayNG (Play): widget broadcast toggles VPN on/off
        // Works via Shizuku shell (bypasses exported=false)
        VpnClientType.V2RAY_NG to VpnClientControl(
            clientType = VpnClientType.V2RAY_NG,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "com.v2ray.ang.action.widget.click",
                "-n", "com.v2ray.ang/.receiver.WidgetProvider"
            ),
        ),

        // v2rayNG (F-Droid): same code, different applicationId — action prefix mirrors applicationId.
        // Receiver class is still com.v2ray.ang.receiver.WidgetProvider (Java package unchanged in the fork).
        VpnClientType.V2RAY_NG_FDROID to VpnClientControl(
            clientType = VpnClientType.V2RAY_NG_FDROID,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "com.v2ray.ang.fdroid.action.widget.click",
                "-n", "com.v2ray.ang.fdroid/com.v2ray.ang.receiver.WidgetProvider"
            ),
        ),

        // NekoBox: separate exported start/stop activities — ideal
        VpnClientType.NEKO_BOX to VpnClientControl(
            clientType = VpnClientType.NEKO_BOX,
            mode = VpnControlMode.SEPARATE,
            startCommand = arrayOf(
                "am", "start",
                "-n", "moe.nb4a/io.nekohasekai.sagernet.ui.QuickEnableShortcut"
            ),
            stopCommand = arrayOf(
                "am", "start",
                "-n", "moe.nb4a/io.nekohasekai.sagernet.ui.QuickDisableShortcut"
            ),
        ),

        // Happ (Play): widget broadcast toggle (discovered via jadx analysis)
        VpnClientType.HAPP to VpnClientControl(
            clientType = VpnClientType.HAPP,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "com.happproxy.action.widget.click",
                "-n", "com.happproxy/.receiver.WidgetProvider"
            ),
        ),

        // Happ (GitHub): su.happ.proxyutility fork — both applicationId AND
        // the Java package of the receiver changed, so action mirrors the new id
        // and the component uses the relative form.
        VpnClientType.HAPP_GITHUB to VpnClientControl(
            clientType = VpnClientType.HAPP_GITHUB,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "su.happ.proxyutility.action.widget.click",
                "-n", "su.happ.proxyutility/.receiver.WidgetProvider"
            ),
        ),

        // v2rayTun: widget broadcast toggle (discovered via jadx analysis)
        VpnClientType.V2RAY_TUN to VpnClientControl(
            clientType = VpnClientType.V2RAY_TUN,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "com.v2raytun.android.action.widget.click",
                "-n", "com.v2raytun.android/.receiver.WidgetProvider1x1"
            ),
        ),

        // V2Box: widget broadcast toggle (discovered via jadx analysis)
        VpnClientType.V2BOX to VpnClientControl(
            clientType = VpnClientType.V2BOX,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "dev.hexasoftware.v2box.action.widget.click",
                "-n", "dev.hexasoftware.v2box/.receiver.WidgetProvider"
            ),
        ),

    )

    fun getControl(type: VpnClientType): VpnClientControl = controls[type]!!

    fun getControlForPackage(packageName: String): VpnClientControl {
        val type = VpnClientType.fromPackageName(packageName)
        return if (type != null) controls[type]!! else VpnClientControl(
            clientType = VpnClientType.V2RAY_NG, // placeholder, unused for MANUAL
            mode = VpnControlMode.MANUAL
        )
    }
}
