package sgnv.anubis.app.data

/**
 * Packages that must never end up frozen, because doing so breaks text input,
 * phone calls, or 2FA flows — which typically requires a factory reset or ADB
 * recovery for non-technical users.
 *
 * This list is one input to [sgnv.anubis.app.policy.FreezeSafetyPolicy].
 * The policy layer also adds VPN client packages and is the single entry point
 * used by UI warnings and auto-selection filters.
 *
 * List curated from incidents in the issue tracker and Habr feedback
 * (Yandex.Клавиатура auto-selected by the `ru.yandex.` prefix heuristic in
 * [DefaultRestrictedApps] being the canonical case).
 */
object NeverRestrictApps {

    val packageNames = setOf(
        // Anubis itself - never allow self-freeze
        "sgnv.anubis.app",

        // Keyboards — without them text input stops working
        "com.google.android.inputmethod.latin",       // Gboard
        "com.samsung.android.honeyboard",             // Samsung Keyboard
        "org.futo.inputmethod.latin",                 // FUTO (privacy-focused)
        "ru.yandex.androidkeyboard",                  // Yandex.Клавиатура
        "com.touchtype.swiftkey",                     // SwiftKey

        // OEM IMS — calls/SMS break without these on Honor/Huawei
        "com.hihonor.ims",
        "com.huawei.ims",

        // 2FA — loss of access to any service whose key lives here
        "ru.yandex.key",
    )

    fun isNeverRestrict(packageName: String): Boolean = packageName in packageNames
}
