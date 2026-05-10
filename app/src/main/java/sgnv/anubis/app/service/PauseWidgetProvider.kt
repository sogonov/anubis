package sgnv.anubis.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R

/**
 * Home-screen widget for the master pause (#145). Single-tap toggle — paused
 * ⇄ active. The toggle goes through orchestrator.setPaused() directly; no
 * binder calls or VPN coordination, so we don't need a separate service like
 * [StealthWidgetService] uses for stealth toggle.
 */
class PauseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val orchestrator = (context.applicationContext as AnubisApp).orchestrator
            orchestrator.setPaused(!orchestrator.paused.value)
            updateAllWidgets(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "sgnv.anubis.app.PAUSE_WIDGET_TOGGLE"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PauseWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            manager.updateAppWidget(widgetId, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val toggleIntent = Intent(context, PauseWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val orchestrator = (context.applicationContext as AnubisApp).orchestrator
            val paused = orchestrator.paused.value
            // State-semantic widget: glyph reflects the *current* state, not the
            // action that the next tap would perform. Pause glyph (||) = paused,
            // play glyph (▶) = active. Color reinforces it, but the glyph alone
            // is readable when the user can't compare the two colors side-by-side
            // (one widget on the home screen, no reference for "active vs. inactive").
            val iconRes = if (paused) R.drawable.ic_pause else R.drawable.ic_play
            val color = if (paused) {
                context.getColor(R.color.widget_icon_active)
            } else {
                context.getColor(R.color.widget_icon_inactive)
            }
            val label = context.getString(
                if (paused) R.string.paused_banner_title else R.string.pause_widget_label_active
            )
            return RemoteViews(context.packageName, R.layout.widget_pause).apply {
                setImageViewResource(R.id.pause_widget_icon, iconRes)
                setTextViewText(R.id.pause_widget_status, label)
                setInt(R.id.pause_widget_icon, "setColorFilter", color)
                setOnClickPendingIntent(R.id.pause_widget_root, pi)
            }
        }
    }
}
