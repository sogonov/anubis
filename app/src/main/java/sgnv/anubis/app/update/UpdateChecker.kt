package sgnv.anubis.app.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sgnv.anubis.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверяет наличие новой версии через GitHub Releases API.
 *
 * API: https://api.github.com/repos/<owner>/<repo>/releases/latest
 * Лимит без токена: 60 req/hour/IP. Для нашего масштаба достаточно.
 */
object UpdateChecker {

    private const val API_URL = "https://api.github.com/repos/sogonov/anubis/releases/latest"
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "update_check_enabled"
    private const val KEY_LAST_CHECK_MS = "update_last_check_ms"
    private const val KEY_SKIPPED_VERSION = "update_skipped_version"

    private const val MIN_INTERVAL_MS = 60 * 60 * 1000L  // 1 час

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {putBoolean(KEY_ENABLED, enabled)}
    }

    fun skipVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_SKIPPED_VERSION, version)}
    }

    private fun skippedVersion(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIPPED_VERSION, null)

    /**
     * Проверить обновления.
     * @param force если true — игнорирует кэш (для ручной проверки из настроек).
     * @return UpdateInfo при успехе, null при ошибке или если кэш свежий и force=false.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)

        if (!force && now - lastCheck < MIN_INTERVAL_MS) {
            return@withContext null
        }

        try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Anubis/${BuildConfig.VERSION_NAME}")
                connectTimeout = 5000
                readTimeout = 5000
            }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name").trimStart('v')
            val htmlUrl = json.optString("html_url")
            val notes = json.optString("body").take(2000)
            val apkUrl = json.optJSONArray("assets")?.let { assets ->
                var found: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true) &&
                        !name.contains("debug", ignoreCase = true)
                    ) {
                        found = a.optString("browser_download_url")
                        break
                    }
                }
                found
            }

            prefs.edit { putLong(KEY_LAST_CHECK_MS, now) }

            UpdateInfo(
                latestVersion = tagName,
                currentVersion = BuildConfig.VERSION_NAME,
                releaseUrl = htmlUrl,
                apkUrl = apkUrl,
                releaseNotes = notes,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True, если обновление доступно и пользователь не попросил его пропустить.
     */
    fun shouldNotify(context: Context, info: UpdateInfo): Boolean {
        if (!info.isUpdateAvailable) return false
        val skipped = skippedVersion(context) ?: return true
        return UpdateInfo.compareVersions(info.latestVersion, skipped) > 0
    }
}
