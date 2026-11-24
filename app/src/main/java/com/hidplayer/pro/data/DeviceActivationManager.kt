package com.hidplayer.pro.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.hidplayer.pro.model.ActivationStatus
import com.hidplayer.pro.model.PlayerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DeviceActivationManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("hidplayer_pro_v2_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        val saved = prefs.getString("device_id", null)
        if (!saved.isNullOrBlank()) return saved

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "TVBOX-${System.currentTimeMillis()}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray())
            .joinToString("") { "%02X".format(it) }
            .take(6)

        val generated = "LP-$hash"
        prefs.edit().putString("device_id", generated).apply()
        return generated
    }

    fun getActivationApiUrl(): String {
        return prefs.getString(
            "activation_api_url",
            "https://hidaia-license.hidaia.workers.dev/"
        ) ?: "https://hidaia-license.hidaia.workers.dev/"
    }

    fun saveActivationApiUrl(url: String) {
        prefs.edit().putString("activation_api_url", url.trim()).apply()
    }

    fun savePlayerSettings(settings: PlayerSettings) {
        prefs.edit()
            .putString("buffer_mode", settings.bufferMode)
            .putString("decoder_mode", settings.decoderMode)
            .putBoolean("vpn_shield", settings.vpnShieldEnabled)
            .putInt("aspect_ratio", settings.aspectRatioMode)
            .apply()
    }

    fun loadPlayerSettings(): PlayerSettings {
        return PlayerSettings(
            bufferMode = prefs.getString("buffer_mode", "BALANCED") ?: "BALANCED",
            decoderMode = prefs.getString("decoder_mode", "HARDWARE") ?: "HARDWARE",
            vpnShieldEnabled = prefs.getBoolean("vpn_shield", false),
            aspectRatioMode = prefs.getInt("aspect_ratio", 0)
        )
    }

    fun getFavorites(): Set<String> {
        return prefs.getStringSet("favorites", emptySet()) ?: emptySet()
    }

    fun toggleFavorite(id: String): Set<String> {
        val current = getFavorites().toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        prefs.edit().putStringSet("favorites", current).apply()
        return current
    }

    fun saveManualXtream(host: String, user: String, pass: String) {
        prefs.edit()
            .putBoolean("is_active", true)
            .putString("server_type", "xtream")
            .putString("host", host.trim().removeSuffix("/"))
            .putString("username", user.trim())
            .putString("password", pass.trim())
            .putString("expiry", "مفعل يدوياً")
            .apply()
    }

    fun saveManualM3u(m3uUrl: String) {
        prefs.edit()
            .putBoolean("is_active", true)
            .putString("server_type", "m3u")
            .putString("m3u_url", m3uUrl.trim())
            .putString("expiry", "مفعل يدوياً")
            .apply()
    }

    fun loadSavedStatus(): ActivationStatus {
        return ActivationStatus(
            isActive = prefs.getBoolean("is_active", false),
            deviceId = getDeviceId(),
            expiryDate = prefs.getString("expiry", "غير مفعل") ?: "غير مفعل",
            serverType = prefs.getString("server_type", "xtream") ?: "xtream",
            host = prefs.getString("host", "") ?: "",
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            m3uUrl = prefs.getString("m3u_url", "") ?: ""
        )
    }

    suspend fun verifyWithServer(apiBaseUrl: String): ActivationStatus = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId()
        val cleanUrl = apiBaseUrl.trim().ifBlank { "https://hidaia-license.hidaia.workers.dev/" }
        saveActivationApiUrl(cleanUrl)
        try {
            val separator = if (cleanUrl.contains("?")) "&" else "?"
            val url = "$cleanUrl${separator}action=check&device_id=$deviceId"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext loadSavedStatus().copy(
                        isActive = false,
                        message = "خطأ في الاتصال بالسيرفر (${response.code})"
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val active = json.optBoolean("active", false) ||
                        json.optString("status").equals("active", ignoreCase = true)

                val expiry = json.optString("expiry", json.optString("expiry_date", "غير محدد"))
                val host = json.optString("host", "").removeSuffix("/")
                val username = json.optString("username", "")
                val password = json.optString("password", "")
                val m3uUrl = json.optString("m3u_url", "")
                val serverType = if (m3uUrl.isNotBlank() && host.isBlank()) "m3u" else "xtream"

                if (active) {
                    prefs.edit()
                        .putBoolean("is_active", true)
                        .putString("expiry", expiry)
                        .putString("server_type", serverType)
                        .putString("host", host)
                        .putString("username", username)
                        .putString("password", password)
                        .putString("m3u_url", m3uUrl)
                        .apply()
                }

                ActivationStatus(
                    isActive = active,
                    deviceId = deviceId,
                    expiryDate = expiry,
                    serverType = serverType,
                    host = host,
                    username = username,
                    password = password,
                    m3uUrl = m3uUrl,
                    message = if (active) "تم التفعيل بنجاح" else json.optString("message", "الجهاز غير مفعل")
                )
            }
        } catch (e: Exception) {
            loadSavedStatus().copy(
                message = "فشل الاتصال: ${e.localizedMessage ?: "خطأ غير معروف"}"
            )
        }
    }
}
