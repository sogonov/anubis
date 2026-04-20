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
    EXCLAVE("Exclave", "com.github.dyhkwong.sagernet"),
    HUSI("husi", "fr.husi"),
    INCY("Incy", "llc.itdev.incy"),
    CLASH_META("Meta", "com.github.metacubex.clash.meta", brand = "Clash Meta"),
    CLASH_META_ALPHA("Alpha", "com.github.metacubex.clash.alpha", brand = "Clash Meta"),
    HAPP("Play", "com.happproxy", brand = "Happ"),
    HAPP_GITHUB("Github", "su.happ.proxyutility", brand = "Happ"),
    V2RAY_TUN("v2rayTun", "com.v2raytun.android"),
    OLCNG("GitHub", "xyz.zarazaex.olc", brand = "olcng"),
    OLCNG_FDROID("F-Droid", "xyz.zarazaex.olc.fdroid", brand = "olcng"),
    TEAPOD_STREAM("TeapodStream", "com.teapodstream.teapodstream"),
    KARING("Karing", "com.nebula.karing"),
    AMNEZIA_VPN("VPN", "org.amnezia.vpn", brand = "Amnezia"),
    AMNEZIA_WG("WG", "org.amnezia.awg", brand = "Amnezia"),
    WIREGUARD("Official", "com.wireguard.android", brand = "WireGuard"),
    WG_TUNNEL("WG Tunnel", "com.zaneschepke.wireguardautotunnel", brand = "WireGuard"),
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
        // v2rayNG (Play): widget broadcast toggles VPN on/off.
        // `-p` and absolute component name are required on MIUI/HyperOS — OEM `am`
        // drops broadcasts without explicit package filter (issue #66).
        VpnClientType.V2RAY_NG to VpnClientControl(
            clientType = VpnClientType.V2RAY_NG,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "com.v2ray.ang.action.widget.click",
                "-p", "com.v2ray.ang",
                "-n", "com.v2ray.ang/com.v2ray.ang.receiver.WidgetProvider"
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
                "-p", "com.v2ray.ang.fdroid",
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

        // Exclave (SagerNet fork): kept only QuickToggleShortcut, so it's TOGGLE, not SEPARATE.
        // Stop goes via the orchestrator's dummy-VPN/force-stop fallback.
        VpnClientType.EXCLAVE to VpnClientControl(
            clientType = VpnClientType.EXCLAVE,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "start",
                "-n", "com.github.dyhkwong.sagernet/io.nekohasekai.sagernet.QuickToggleShortcut"
            ),
        ),

        // husi (SagerNet fork): same pattern as Exclave. Component expanded to absolute form
        // because OEM `am` (MIUI/HyperOS) may not resolve relative "/.X" prefixes (issue #66).
        VpnClientType.HUSI to VpnClientControl(
            clientType = VpnClientType.HUSI,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "start",
                "-n", "fr.husi/fr.husi.QuickToggleShortcut"
            ),
        ),

        // Incy: exported VpnIntentReceiver with separate CONNECT/DISCONNECT actions — ideal SEPARATE.
        VpnClientType.INCY to VpnClientControl(
            clientType = VpnClientType.INCY,
            mode = VpnControlMode.SEPARATE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "llc.itdev.incy.CONNECT",
                "-p", "llc.itdev.incy",
                "-n", "llc.itdev.incy/llc.itdev.incy.receiver.VpnIntentReceiver"
            ),
            stopCommand = arrayOf(
                "am", "broadcast",
                "-a", "llc.itdev.incy.DISCONNECT",
                "-p", "llc.itdev.incy",
                "-n", "llc.itdev.incy/llc.itdev.incy.receiver.VpnIntentReceiver"
            ),
        ),

        // Clash Meta (Mihomo, Meta flavor): ExternalControlActivity honors START_CLASH /
        // STOP_CLASH with no extras or auth. Action strings are hard-coded as
        // "com.github.metacubex.clash.meta.action.*" regardless of flavor — only the
        // applicationId differs between Meta and Alpha builds.
        VpnClientType.CLASH_META to VpnClientControl(
            clientType = VpnClientType.CLASH_META,
            mode = VpnControlMode.SEPARATE,
            startCommand = arrayOf(
                "am", "start",
                "-a", "com.github.metacubex.clash.meta.action.START_CLASH",
                "-n", "com.github.metacubex.clash.meta/com.github.kr328.clash.ExternalControlActivity"
            ),
            stopCommand = arrayOf(
                "am", "start",
                "-a", "com.github.metacubex.clash.meta.action.STOP_CLASH",
                "-n", "com.github.metacubex.clash.meta/com.github.kr328.clash.ExternalControlActivity"
            ),
        ),

        // Clash Meta Alpha: applicationId = com.github.metacubex.clash.alpha, same actions.
        VpnClientType.CLASH_META_ALPHA to VpnClientControl(
            clientType = VpnClientType.CLASH_META_ALPHA,
            mode = VpnControlMode.SEPARATE,
            startCommand = arrayOf(
                "am", "start",
                "-a", "com.github.metacubex.clash.alpha.action.START_CLASH",
                "-n", "com.github.metacubex.clash.alpha/com.github.kr328.clash.ExternalControlActivity"
            ),
            stopCommand = arrayOf(
                "am", "start",
                "-a", "com.github.metacubex.clash.alpha.action.STOP_CLASH",
                "-n", "com.github.metacubex.clash.alpha/com.github.kr328.clash.ExternalControlActivity"
            ),
        ),


        // Happ (Play): widget broadcast toggle (discovered via jadx analysis)
        VpnClientType.HAPP to VpnClientControl(
            clientType = VpnClientType.HAPP,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "com.happproxy.action.widget.click",
                "-p", "com.happproxy",
                "-n", "com.happproxy/com.happproxy.receiver.WidgetProvider"
            ),
        ),

        // Happ (GitHub): su.happ.proxyutility fork — both applicationId AND
        // the Java package of the receiver changed.
        VpnClientType.HAPP_GITHUB to VpnClientControl(
            clientType = VpnClientType.HAPP_GITHUB,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "su.happ.proxyutility.action.widget.click",
                "-p", "su.happ.proxyutility",
                "-n", "su.happ.proxyutility/su.happ.proxyutility.receiver.WidgetProvider"
            ),
        ),

        // v2rayTun: widget broadcast toggle (discovered via jadx analysis)
        VpnClientType.V2RAY_TUN to VpnClientControl(
            clientType = VpnClientType.V2RAY_TUN,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "com.v2raytun.android.action.widget.click",
                "-p", "com.v2raytun.android",
                "-n", "com.v2raytun.android/com.v2raytun.android.receiver.WidgetProvider1x1"
            ),
        ),
        // olcng (OpenLibreCommunity fork of v2rayNG): same widget-broadcast pattern, action
        // prefix mirrors applicationId (xyz.zarazaex.olc).
        VpnClientType.OLCNG to VpnClientControl(
            clientType = VpnClientType.OLCNG,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "xyz.zarazaex.olc.action.widget.click",
                "-p", "xyz.zarazaex.olc",
                "-n", "xyz.zarazaex.olc/xyz.zarazaex.olc.receiver.WidgetProvider"
            ),
        ),

        // olcng F-Droid: applicationIdSuffix adds ".fdroid", Java package of the receiver stays
        // xyz.zarazaex.olc — same split as v2rayNG F-Droid, so the component uses the full path.
        VpnClientType.OLCNG_FDROID to VpnClientControl(
            clientType = VpnClientType.OLCNG_FDROID,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "xyz.zarazaex.olc.fdroid.action.widget.click",
                "-p", "xyz.zarazaex.olc.fdroid",
                "-n", "xyz.zarazaex.olc.fdroid/xyz.zarazaex.olc.receiver.WidgetProvider"
            ),
        ),


        // V2Box: widget broadcast toggle (discovered via jadx analysis)
        VpnClientType.V2BOX to VpnClientControl(
            clientType = VpnClientType.V2BOX,
            mode = VpnControlMode.TOGGLE,
            startCommand = arrayOf(
                "am", "broadcast",
                "-a", "dev.hexasoftware.v2box.action.widget.click",
                "-p", "dev.hexasoftware.v2box",
                "-n", "dev.hexasoftware.v2box/dev.hexasoftware.v2box.receiver.WidgetProvider"
            ),
        ),

        // TeapodStream: Flutter app with only MainActivity exported, no widget/shortcut API.
        // MANUAL for now; a PR upstream could add a toggle intent.
        VpnClientType.TEAPOD_STREAM to VpnClientControl(
            clientType = VpnClientType.TEAPOD_STREAM,
            mode = VpnControlMode.MANUAL,
        ),
        // AmneziaVPN (Qt): no exported broadcast/activity API for start/stop — only QS Tile
        // and MainActivity. Fall back to MANUAL (open app, user taps connect).
        VpnClientType.AMNEZIA_VPN to VpnClientControl(
            clientType = VpnClientType.AMNEZIA_VPN,
            mode = VpnControlMode.MANUAL,
        ),

        // AmneziaWG: exports SET_TUNNEL_UP/DOWN with a required EXTRA_TUNNEL name under
        // the dangerous CONTROL_TUNNELS permission. Shizuku shell bypasses the permission,
        // but without a configured tunnel name nothing happens. MANUAL until we add
        // per-client tunnel-name config in Anubis.
        VpnClientType.AMNEZIA_WG to VpnClientControl(
            clientType = VpnClientType.AMNEZIA_WG,
            mode = VpnControlMode.MANUAL,
        ),

        // WireGuard official: same SET_TUNNEL_UP/DOWN mechanism as AmneziaWG. MANUAL for now.
        VpnClientType.WIREGUARD to VpnClientControl(
            clientType = VpnClientType.WIREGUARD,
            mode = VpnControlMode.MANUAL,
        ),

        // WG Tunnel: exports START_TUNNEL/STOP_TUNNEL on RemoteControlReceiver, but gates
        // commands behind isRemoteControlEnabled + a user-defined remoteKey (EXTRA_KEY).
        // MANUAL until Anubis gains a key-config UI.
        VpnClientType.WG_TUNNEL to VpnClientControl(
            clientType = VpnClientType.WG_TUNNEL,
            mode = VpnControlMode.MANUAL,
        ),


        // Karing (Flutter): only MainActivity + TileService exported — no broadcast/activity
        // API surface for external start/stop. MANUAL.
        VpnClientType.KARING to VpnClientControl(
            clientType = VpnClientType.KARING,
            mode = VpnControlMode.MANUAL,
        ),

    )

    fun getControl(type: VpnClientType): VpnClientControl = controls[type]!!
}
