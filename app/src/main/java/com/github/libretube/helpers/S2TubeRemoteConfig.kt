package com.github.libretube.helpers

import android.app.Activity
import android.app.AlertDialog
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.CustomInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * S2 Tube remote config: the app fetches its admin-controlled settings
 * (Piped servers, default region, announcements, maintenance) from the
 * S2 admin panel on launch. Works offline: the last fetched config stays
 * cached in preferences, so the app keeps working without internet.
 */
object S2TubeRemoteConfig {
    const val CONFIG_URL = "https://s3-ecosystem-10adcd4c.easyhost.fun/api/s2tube/config"

    const val KEY_REMOTE_REGION = "s2tube_remote_region"
    const val KEY_REMOTE_ANNOUNCEMENT = "s2tube_remote_announcement"
    const val KEY_REMOTE_ANNOUNCEMENT_ID = "s2tube_remote_announcement_id"
    const val KEY_LAST_ANNOUNCEMENT_SHOWN = "s2tube_last_announcement_shown"
    const val KEY_REMOTE_MAINTENANCE = "s2tube_remote_maintenance"
    const val KEY_REMOTE_MAINTENANCE_MSG = "s2tube_remote_maintenance_msg"

    fun fetchAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fetchAndApply()
            } catch (_: Exception) {
                // offline or server down: keep the cached config
            }
        }
    }

    suspend fun fetchAndApply() {
        val client = OkHttpClient()
        val request = Request.Builder().url(CONFIG_URL).get().build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            response.body?.string() ?: return
        }
        val json = JSONObject(body)
        val region = json.optString("defaultRegion", "SA")
        val defaultInstance = json.optString("defaultInstance", "")
        val announcement = json.optString("announcement", "")
        val announcementId = json.optInt("announcementId", 0)
        val maintenance = json.optBoolean("maintenanceMode", false)
        val maintenanceMsg = json.optString("maintenanceMessage", "")

        // servers: add the admin's servers the user doesn't have yet
        val instancesJson = json.optJSONArray("instances")
        if (instancesJson != null) {
            val dao = Database.customInstanceDao()
            for (i in 0 until instancesJson.length()) {
                val obj = instancesJson.optJSONObject(i) ?: continue
                val apiUrl = obj.optString("apiUrl", "").trim()
                val name = obj.optString("name", "").trim()
                if (apiUrl.isEmpty() || name.isEmpty()) continue
                if (dao.getByApiUrl(apiUrl) == null) {
                    dao.insert(CustomInstance(name, apiUrl, ""))
                }
            }
        }

        // default instance: only when the user never picked one themselves
        if (defaultInstance.isNotEmpty() &&
            PreferenceHelper.getString(PreferenceKeys.AUTH_INSTANCE, RetrofitInstance.PIPED_API_URL) == RetrofitInstance.PIPED_API_URL
        ) {
            PreferenceHelper.putString(PreferenceKeys.AUTH_INSTANCE, defaultInstance)
        }

        PreferenceHelper.putString(KEY_REMOTE_REGION, region)
        PreferenceHelper.putString(KEY_REMOTE_ANNOUNCEMENT, announcement)
        PreferenceHelper.putInt(KEY_REMOTE_ANNOUNCEMENT_ID, announcementId)
        PreferenceHelper.putBoolean(KEY_REMOTE_MAINTENANCE, maintenance)
        PreferenceHelper.putString(KEY_REMOTE_MAINTENANCE_MSG, maintenanceMsg)
    }

    /** Show maintenance / announcement dialogs from the cached config. Call from MainActivity. */
    fun showStartupNotices(activity: Activity) {
        if (PreferenceHelper.getBoolean(KEY_REMOTE_MAINTENANCE, false)) {
            val msg = PreferenceHelper.getString(KEY_REMOTE_MAINTENANCE_MSG, "")
            AlertDialog.Builder(activity)
                .setTitle(R.string.app_name)
                .setMessage(msg.ifEmpty { "التطبيق تحت الصيانة حالياً." })
                .setCancelable(false)
                .setPositiveButton(R.string.okay) { _, _ -> activity.finish() }
                .show()
            return
        }
        val announcement = PreferenceHelper.getString(KEY_REMOTE_ANNOUNCEMENT, "")
        val announcementId = PreferenceHelper.getInt(KEY_REMOTE_ANNOUNCEMENT_ID, 0)
        val lastShown = PreferenceHelper.getInt(KEY_LAST_ANNOUNCEMENT_SHOWN, -1)
        if (announcement.isNotEmpty() && announcementId != lastShown) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.app_name)
                .setMessage(announcement)
                .setPositiveButton(R.string.okay) { _, _ ->
                    PreferenceHelper.putInt(KEY_LAST_ANNOUNCEMENT_SHOWN, announcementId)
                }
                .setOnDismissListener {
                    PreferenceHelper.putInt(KEY_LAST_ANNOUNCEMENT_SHOWN, announcementId)
                }
                .show()
        }
    }
}
