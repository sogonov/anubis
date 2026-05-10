package sgnv.anubis.app.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Sort order for apps inside groups on HomeScreen (#56). Persistent across sessions.
 * NAME — by display label (default, what users almost always want).
 * PACKAGE — by package name, useful for users who think in package terms (developers,
 * power users with many apps that share a label like "Settings").
 */
enum class HomeSortMode {
    NAME,
    PACKAGE;

    companion object {
        fun load(context: Context): HomeSortMode {
            val raw = AppSettings.prefs(context).getString(AppSettings.KEY_HOME_SORT_MODE, NAME.name)
            return runCatching { valueOf(raw ?: NAME.name) }.getOrDefault(NAME)
        }

        fun save(context: Context, mode: HomeSortMode) {
            AppSettings.prefs(context).edit { putString(AppSettings.KEY_HOME_SORT_MODE, mode.name) }
        }
    }
}
