# Anubis

Android app manager with VPN integration. Manages groups of apps by freezing/unfreezing them based on VPN connection state.

Unlike sandbox-based solutions (Island, Insular, Shelter), which only isolate apps in a work profile where they **can still detect VPN** through the shared network stack, Anubis uses `pm disable-user` to **completely disable** apps at the system level. A disabled app cannot run any code, detect any network interfaces, or send any data.

## Features

- **App groups** with different network policies:
  - **Local** — apps frozen when VPN is active, launched without VPN
  - **VPN Only** — apps frozen when VPN is inactive, launched through VPN
  - **Launch with VPN** — never frozen, but launching triggers VPN activation
- **Home screen launcher** — tap app icons to launch with correct VPN state
  - Grayscale icons for frozen/disabled apps
  - Long press for freeze/unfreeze, shortcut creation, group management
- **VPN client orchestration** — auto start/stop for supported clients, any client works in manual mode
- **Custom VPN client** — select any installed app as VPN client from the app list
- **Active VPN client detection** — identifies which app owns the VPN via `dumpsys connectivity` owner UID
- **Pinned shortcuts** — home screen shortcuts that orchestrate freeze/VPN/launch in one tap
- **Network check** — ping, country, city (IP hidden by default for privacy)
- **Quick Settings tile** — toggle from notification shade
- **Auto-freeze on boot** and on app launch if VPN is already active
- **Background VPN monitoring** — auto-freeze when VPN is toggled outside Anubis (opt-in in Settings)
- **VPN disconnect** — multi-step: API → dummy VPN takeover → force-stop → kill

## How It Works

### Freeze Mechanism
Uses Shizuku to execute `pm disable-user --user 0 <package>` which completely disables an app at the OS level. The app cannot:
- Run any background services
- Receive broadcasts
- Access network interfaces
- Detect VPN or proxy

This is fundamentally different from sandboxing, which still allows the app to run and inspect the network stack.

### VPN Start
For clients with a known API, Anubis sends shell commands via Shizuku. Three control modes:
- **SEPARATE** — independent start and stop commands. Examples: NekoBox (`QuickEnableShortcut` / `QuickDisableShortcut` activities), Incy (`CONNECT` / `DISCONNECT` broadcasts), Clash Meta (`START_CLASH` / `STOP_CLASH` activity).
- **TOGGLE** — single command that toggles on/off. Examples: v2rayNG / Happ / v2rayTun / V2Box / olcng (widget broadcast), Exclave / husi (`QuickToggleShortcut` activity).
- **MANUAL** — no external start/stop API. Anubis opens the app and the user taps connect.

### VPN Stop
Toggle commands are unreliable for stopping (can re-enable immediately), so Anubis uses a multi-step approach:
1. **API stop** — only for SEPARATE clients with explicit stop command
2. **Dummy VPN** — establish our own VPN to revoke theirs, then close ours
3. **`am force-stop`** — kill the detected VPN app process

Apps are never unfrozen while VPN is still active.

### VPN Detection
Extracts the VPN network owner UID from `dumpsys connectivity` by matching `type: VPN[` pattern, then resolves UID → package name via `pm list packages --uid`. Works with any VPN client, including unknown/custom ones.

## Supported VPN Clients

Variants of the same brand (e.g. v2rayNG Play and F-Droid) are grouped together in the Settings picker.

### Full control (independent start/stop)

| Client | Package(s) | Method |
|--------|------------|--------|
| NekoBox | `moe.nb4a` | `QuickEnableShortcut` / `QuickDisableShortcut` activities |
| Incy | `llc.itdev.incy` | `CONNECT` / `DISCONNECT` broadcasts |
| Clash Meta | `com.github.metacubex.clash.meta` (Meta), `com.github.metacubex.clash.alpha` (Alpha) | `START_CLASH` / `STOP_CLASH` on `ExternalControlActivity` |
| FlClash | `com.follow.clash` | `action.START` / `action.STOP` on `TempActivity` |
| FlClashX | `com.follow.clashx` | `action.START` / `action.STOP` on `TempActivity` |

### Auto-toggle (start via widget/shortcut, stop via dummy-VPN fallback)

| Client | Package(s) | Method |
|--------|------------|--------|
| v2rayNG | `com.v2ray.ang` (Play), `com.v2ray.ang.fdroid` (F-Droid) | Widget broadcast |
| Happ | `com.happproxy` (Play), `su.happ.proxyutility` (Github) | Widget broadcast |
| v2rayTun | `com.v2raytun.android` | Widget broadcast |
| V2Box | `dev.hexasoftware.v2box` | Widget broadcast |
| olcng | `xyz.zarazaex.olc`, `xyz.zarazaex.olc.fdroid` | Widget broadcast |
| Exclave | `com.github.dyhkwong.sagernet` | `QuickToggleShortcut` activity |
| husi | `fr.husi` | `QuickToggleShortcut` activity |
| [Tunguska](https://github.com/Acionyx/tunguska) | `io.acionyx.tunguska` | Full (start/stop) | Token-gated exported activity |

### Manual (Anubis opens the app, user connects)

| Client | Package | Why manual |
|--------|---------|------------|
| AmneziaVPN | `org.amnezia.vpn` | No exported start/stop API (Qt app) |
| AmneziaWG | `org.amnezia.awg` | `SET_TUNNEL_UP`/`DOWN` requires a tunnel-name extra — planned for a later release |
| WireGuard | `com.wireguard.android` | Same mechanism as AmneziaWG |
| WG Tunnel | `com.zaneschepke.wireguardautotunnel` | `RemoteControlReceiver` is gated by a user-defined secret key |
| TeapodStream | `com.teapodstream.teapodstream` | Only `MainActivity` exported |
| Karing | `com.nebula.karing` | Only `MainActivity` + QS tile exported |
| **Any app** | — | Select in Settings → "Other client" |

### Tunguska

Tunguska integrates differently from widget-toggle clients. Instead of a single broadcast toggle, it exposes explicit `AUTOMATION_START` and `AUTOMATION_STOP` actions through a dedicated exported activity, guarded by a per-client automation token. Anubis stores that token in encrypted preferences and uses it for separate start and stop orchestration.

Quick setup:

1. Install [Tunguska](https://github.com/Acionyx/tunguska) and import a working profile.
2. Start Tunguska once manually and grant Android VPN permission.
3. In Tunguska, open `Anubis Integration`, enable automation, and copy the generated token.
4. In Anubis, select `Tunguska` as the VPN client and paste the token into the VPN client settings.
5. Use Anubis normally: it can unfreeze Tunguska, send explicit start/stop commands, and freeze it again while idle.

### Discovering VPN Client APIs

For closed-source clients, broadcast actions can be discovered via APK analysis using [jadx](https://github.com/skylot/jadx):

1. **Resources** → find `app_widget_name` in `strings.xml` (resources are not obfuscated)
2. **Manifest** → find `<receiver>` with `android.appwidget.provider` metadata → get class name
3. **Receiver code** → find `setAction("...")` → this is the broadcast action
4. **Verify** → check `onReceive()` for the `isRunning ? stop : start` toggle pattern

Two common patterns discovered so far:

**v2ray/xray forks** (v2rayNG, Happ, V2Box, v2rayTun, olcng) — widget broadcast:
```
Action:   <package>.action.widget.click
Receiver: <package>.receiver.WidgetProvider
```
```shell
am broadcast -a <package>.action.widget.click -n <package>/.receiver.WidgetProvider
```

**SagerNet forks** (NekoBox, Exclave, husi) — exported shortcut activities:
```
Activity: QuickEnableShortcut / QuickDisableShortcut (separate)
     or:  QuickToggleShortcut (single toggle)
```
```shell
am start -n <package>/<activity-fully-qualified-name>
```

This is standard Android IPC discovery, not reverse engineering of protected code. Broadcast actions and exported activities are public interfaces by design.

## Requirements

- Android 10+ (API 29)
- [Shizuku](https://shizuku.rikka.app/) installed and running
- At least one VPN client installed

## Setup

1. Install and start Shizuku (via ADB or Wireless Debugging)
2. Install Anubis
3. Grant Shizuku permission when prompted
4. Grant VPN permission when prompted (needed for dummy VPN disconnect)
5. Go to **Apps** tab, assign apps to groups
6. Select your VPN client in **Settings** (known or custom)
7. Toggle stealth mode on the **Home** screen

## Building

```bash
./gradlew assembleDebug
./gradlew assembleRelease  # requires signing config
```

Create `signing.properties` in project root:
```properties
storeFile=release.keystore
storePassword=your_password
keyAlias=your_alias
keyPassword=your_password
```

## Tech Stack

- Kotlin + Jetpack Compose (Material 3)
- Shizuku API 13.1.5 (AIDL UserService pattern)
- Room database with TypeConverters (app groups)
- ConnectivityManager NetworkCallback (VPN state monitoring)
- ShortcutManager (pinned shortcuts)

## Roadmap

- [ ] Per-client tunnel-name / secret-key config so WireGuard, AmneziaWG and WG Tunnel can move from MANUAL to full control
- [ ] Fourth app group with auto-unfreeze when VPN is turned off (banking / messenger notifications)
- [ ] Self-hosted `app_process` daemon to remove Shizuku dependency
- [ ] Export/import app group configuration
- [ ] More VPN clients — PRs welcome, see "Discovering VPN Client APIs" above

## License

MIT
