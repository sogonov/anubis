package sgnv.anubis.app.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import sgnv.anubis.app.util.AppLogger

/**
 * Dummy VPN service to force-disconnect any active VPN.
 *
 * Android allows only one VPN at a time. When we establish() our VPN,
 * the system automatically revokes (disconnects) whatever VPN was running.
 * We then immediately close our VPN — result: no VPN is running.
 *
 * Requires VPN consent (prepare). If consent was taken by another VPN app,
 * the caller must re-request via prepareVpn() before calling disconnect().
 */
class StealthVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            doDisconnect()
        }
        return START_NOT_STICKY
    }

    private fun doDisconnect() {
        try {
            val fd = Builder()
                .addAddress("10.255.255.1", 32)
                .setSession("stealth-disconnect")
                .setBlocking(false)
                .establish()

            if (fd != null) {
                // Our VPN established → other VPN is revoked
                fd.close()
                Log.d(TAG, "Dummy VPN established and closed — other VPN disconnected")
            } else {
                Log.w(TAG, "establish() returned null — no VPN consent")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to establish dummy VPN", e)
        }
        stopSelf()
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        // Grace period: even after fd.close() the system may still briefly report our
        // dummy VPN network as active in ConnectivityManager. Without this window,
        // VpnMonitorService / orchestrator detection races us and sees our own dummy
        // as "external VPN came up" → re-freezes groups we just unfroze.
        Handler(Looper.getMainLooper()).postDelayed({
            dummyVpnInFlight = false
        }, DUMMY_GRACE_MS)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StealthVpnService"
        const val ACTION_DISCONNECT = "sgnv.anubis.app.FORCE_DISCONNECT_VPN"

        /**
         * True while our dummy VPN is being set up, is active, or has just been torn
         * down and may still briefly appear in the system's network list. All VPN
         * detection paths must ignore VPN transports while this flag is set, otherwise
         * we'll treat our own force-disconnect VPN as an external VPN coming up.
         */
        @Volatile
        var dummyVpnInFlight: Boolean = false
            private set

        private const val DUMMY_GRACE_MS = 1500L

        /**
         * Check if we have VPN consent.
         * Returns null if we have it, or an Intent to show the system dialog.
         */
        fun prepareVpn(context: Context): Intent? = prepare(context)

        /**
         * Start the dummy VPN to disconnect any active VPN.
         * Only works if we have VPN consent (prepareVpn returns null).
         */
        fun disconnect(context: Context) {
            // Set synchronously BEFORE startService so no callback can fire before the flag is up.
            dummyVpnInFlight = true
            val intent = Intent(context, StealthVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
