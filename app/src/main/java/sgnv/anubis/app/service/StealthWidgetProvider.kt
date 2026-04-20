package sgnv.anubis.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.RemoteViews
import sgnv.anubis.app.R

class StealthWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            StealthWidgetService.toggle(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "sgnv.anubis.app.WIDGET_TOGGLE"

        const val COLOR_ACTIVE = 0xFF4CAF50.toInt()
        const val COLOR_INACTIVE = 0xFF9E9E9E.toInt()
        const val COLOR_WORKING = 0xFFFFAA00.toInt()

        fun updateAllWidgets(context: Context) {
            val vpnActive = isVpnActive(context)
            updateAllWidgets(
                context,
                if (vpnActive) "Stealth ON" else "Stealth OFF",
                if (vpnActive) COLOR_ACTIVE else COLOR_INACTIVE
            )
        }

        fun updateAllWidgets(context: Context, text: String, iconColor: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StealthWidgetProvider::class.java))
            val views = buildViews(context, text, iconColor)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val vpnActive = isVpnActive(context)
            manager.updateAppWidget(widgetId, buildViews(
                context,
                if (vpnActive) "Stealth ON" else "Stealth OFF",
                if (vpnActive) COLOR_ACTIVE else COLOR_INACTIVE
            ))
        }

        fun buildViews(context: Context, text: String, iconColor: Int): RemoteViews {
            val toggleIntent = Intent(context, StealthWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return RemoteViews(context.packageName, R.layout.widget_stealth).apply {
                setTextViewText(R.id.widget_status, text)
                setInt(R.id.widget_icon, "setColorFilter", iconColor)
                setOnClickPendingIntent(R.id.widget_root, pi)
            }
        }

        fun isVpnActive(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return try {
                cm.allNetworks.any { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
