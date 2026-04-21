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

        fun activeColor(context: Context): Int = context.getColor(R.color.widget_icon_active)
        fun inactiveColor(context: Context): Int = context.getColor(R.color.widget_icon_inactive)
        fun workingColor(context: Context): Int = context.getColor(R.color.widget_icon_working)

        fun updateAllWidgets(context: Context) {
            val vpnActive = isVpnActive(context)
            updateAllWidgets(
                context,
                if (vpnActive) "Stealth ON" else "Stealth OFF",
                if (vpnActive) activeColor(context) else inactiveColor(context)
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
                if (vpnActive) activeColor(context) else inactiveColor(context)
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
