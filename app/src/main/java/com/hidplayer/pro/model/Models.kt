package com.hidplayer.pro.model

data class ActivationStatus(
    val isActive: Boolean = false,
    val deviceId: String = "",
    val expiryDate: String = "غير مفعل",
    val serverType: String = "xtream",
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val message: String = ""
)

data class PlayerSettings(
    val bufferMode: String = "BALANCED", // FAST, BALANCED, ANTI_FREEZE
    val decoderMode: String = "HARDWARE", // HARDWARE, SOFTWARE
    val vpnShieldEnabled: Boolean = false, // Cloudflare DoH 1.1.1.1 Anti-Throttle Shield
    val aspectRatioMode: Int = 0 // 0: Fit, 1: Fill, 2: Zoom
)

data class CategoryItem(
    val id: String,
    val name: String
)

data class MediaEntry(
    val id: String,
    val name: String,
    val streamUrl: String,
    val categoryId: String,
    val logoUrl: String = "",
    val number: Int = 0,
    val rating: String = "",
    val containerExtension: String = "mp4",
    val isSeries: Boolean = false
)

data class EpisodeItem(
    val id: String,
    val title: String,
    val season: Int,
    val episodeNum: Int,
    val streamUrl: String
)

enum class AppScreen {
    ACTIVATION,
    HOME,
    LIVE_TV,
    MOVIES,
    SERIES,
    SERIES_DETAILS,
    PLAYER,
    SETTINGS
}
