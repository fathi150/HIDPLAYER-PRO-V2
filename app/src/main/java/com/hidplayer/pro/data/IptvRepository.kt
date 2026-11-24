package com.hidplayer.pro.data

import com.hidplayer.pro.model.ActivationStatus
import com.hidplayer.pro.model.CategoryItem
import com.hidplayer.pro.model.EpisodeItem
import com.hidplayer.pro.model.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class IptvRepository {

    private fun buildClient(useVpnShield: Boolean): OkHttpClient {
        val baseBuilder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)

        if (useVpnShield) {
            try {
                val bootstrapClient = OkHttpClient.Builder().build()
                val dns = DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url("https://1.1.1.1/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(
                        InetAddress.getByName("1.1.1.1"),
                        InetAddress.getByName("1.0.0.1")
                    )
                    .build()
                baseBuilder.dns(dns)
            } catch (_: Exception) {
            }
        }
        return baseBuilder.build()
    }

    suspend fun loadLive(status: ActivationStatus, vpnShield: Boolean): Pair<List<CategoryItem>, List<MediaEntry>> =
        withContext(Dispatchers.IO) {
            val client = buildClient(vpnShield)
            if (status.serverType == "m3u" && status.m3uUrl.isNotBlank()) {
                fetchM3u(client, status.m3uUrl)
            } else if (status.host.isNotBlank()) {
                fetchXtreamCategoryAndItems(client, status, "get_live_categories", "get_live_streams", "live")
            } else {
                Pair(emptyList(), emptyList())
            }
        }

    suspend fun loadMovies(status: ActivationStatus, vpnShield: Boolean): Pair<List<CategoryItem>, List<MediaEntry>> =
        withContext(Dispatchers.IO) {
            if (status.host.isBlank()) return@withContext Pair(emptyList(), emptyList())
            val client = buildClient(vpnShield)
            fetchXtreamCategoryAndItems(client, status, "get_vod_categories", "get_vod_streams", "movie")
        }

    suspend fun loadSeries(status: ActivationStatus, vpnShield: Boolean): Pair<List<CategoryItem>, List<MediaEntry>> =
        withContext(Dispatchers.IO) {
            if (status.host.isBlank()) return@withContext Pair(emptyList(), emptyList())
            val client = buildClient(vpnShield)
            fetchXtreamCategoryAndItems(client, status, "get_series_categories", "get_series", "series")
        }

    suspend fun loadSeriesEpisodes(status: ActivationStatus, seriesId: String, vpnShield: Boolean): List<EpisodeItem> =
        withContext(Dispatchers.IO) {
            val episodes = mutableListOf<EpisodeItem>()
            if (status.host.isBlank()) return@withContext episodes
            val client = buildClient(vpnShield)
            val url = "${status.host}/player_api.php?username=${status.username}&password=${status.password}&action=get_series_info&series_id=$seriesId"
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    if (!res.isSuccessful) return@withContext episodes
                    val root = JSONObject(res.body?.string().orEmpty())
                    val episodesObj = root.optJSONObject("episodes") ?: return@withContext episodes
                    val keys = episodesObj.keys()
                    while (keys.hasNext()) {
                        val seasonKey = keys.next()
                        val seasonNum = seasonKey.toIntOrNull() ?: 1
                        val arr = episodesObj.optJSONArray(seasonKey) ?: continue
                        for (i in 0 until arr.length()) {
                            val ep = arr.getJSONObject(i)
                            val epId = ep.optString("id")
                            val ext = ep.optString("container_extension", "mp4").ifBlank { "mp4" }
                            val title = ep.optString("title", "الحلقة ${i + 1}")
                            val epNum = ep.optInt("episode_num", i + 1)
                            episodes.add(
                                EpisodeItem(
                                    id = epId,
                                    title = "الموسم $seasonNum - $title",
                                    season = seasonNum,
                                    episodeNum = epNum,
                                    streamUrl = "${status.host}/series/${status.username}/${status.password}/$epId.$ext"
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
            episodes.sortedWith(compareBy<EpisodeItem> { it.season }.thenBy { it.episodeNum })
        }

    private fun fetchXtreamCategoryAndItems(
        client: OkHttpClient,
        status: ActivationStatus,
        catAction: String,
        streamAction: String,
        mode: String
    ): Pair<List<CategoryItem>, List<MediaEntry>> {
        val categories = mutableListOf(
            CategoryItem("ALL", "الكل"),
            CategoryItem("FAV", "★ المفضلة")
        )
        val items = mutableListOf<MediaEntry>()
        val host = status.host
        val user = status.username
        val pass = status.password

        try {
            val catUrl = "$host/player_api.php?username=$user&password=$pass&action=$catAction"
            client.newCall(Request.Builder().url(catUrl).build()).execute().use { res ->
                if (res.isSuccessful) {
                    val arr = JSONArray(res.body?.string().orEmpty())
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        categories.add(
                            CategoryItem(
                                id = obj.optString("category_id"),
                                name = obj.optString("category_name")
                            )
                        )
                    }
                }
            }

            val itemsUrl = "$host/player_api.php?username=$user&password=$pass&action=$streamAction"
            client.newCall(Request.Builder().url(itemsUrl).build()).execute().use { res ->
                if (res.isSuccessful) {
                    val arr = JSONArray(res.body?.string().orEmpty())
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        when (mode) {
                            "live" -> {
                                val id = obj.optString("stream_id")
                                items.add(
                                    MediaEntry(
                                        id = "live_$id",
                                        name = obj.optString("name"),
                                        streamUrl = "$host/live/$user/$pass/$id.ts",
                                        categoryId = obj.optString("category_id"),
                                        logoUrl = obj.optString("stream_icon"),
                                        number = i + 1
                                    )
                                )
                            }
                            "movie" -> {
                                val id = obj.optString("stream_id")
                                val ext = obj.optString("container_extension", "mp4").ifBlank { "mp4" }
                                items.add(
                                    MediaEntry(
                                        id = "vod_$id",
                                        name = obj.optString("name"),
                                        streamUrl = "$host/movie/$user/$pass/$id.$ext",
                                        categoryId = obj.optString("category_id"),
                                        logoUrl = obj.optString("stream_icon"),
                                        rating = obj.optString("rating"),
                                        containerExtension = ext,
                                        number = i + 1
                                    )
                                )
                            }
                            "series" -> {
                                val id = obj.optString("series_id")
                                items.add(
                                    MediaEntry(
                                        id = id,
                                        name = obj.optString("name"),
                                        streamUrl = "",
                                        categoryId = obj.optString("category_id"),
                                        logoUrl = obj.optString("cover"),
                                        rating = obj.optString("rating"),
                                        number = i + 1,
                                        isSeries = true
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return Pair(categories, items)
    }

    private fun fetchM3u(client: OkHttpClient, m3uUrl: String): Pair<List<CategoryItem>, List<MediaEntry>> {
        val categoriesMap = linkedMapOf("ALL" to "الكل", "FAV" to "★ المفضلة")
        val channels = mutableListOf<MediaEntry>()

        try {
            client.newCall(Request.Builder().url(m3uUrl).build()).execute().use { res ->
                if (!res.isSuccessful) return Pair(emptyList(), emptyList())
                val lines = res.body?.string().orEmpty().lines()
                var currentName = ""
                var currentGroup = "عام"
                var currentLogo = ""
                var index = 1

                for (rawLine in lines) {
                    val line = rawLine.trim()
                    if (line.startsWith("#EXTINF")) {
                        currentGroup = Regex("group-title=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: "عام"
                        currentLogo = Regex("tvg-logo=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: ""
                        currentName = line.substringAfterLast(",").trim().ifBlank { "Channel $index" }
                        categoriesMap.putIfAbsent(currentGroup, currentGroup)
                    } else if (line.isNotBlank() && !line.startsWith("#")) {
                        channels.add(
                            MediaEntry(
                                id = "m3u_$index",
                                name = currentName.ifBlank { "Channel $index" },
                                streamUrl = line,
                                categoryId = currentGroup,
                                logoUrl = currentLogo,
                                number = index
                            )
                        )
                        index++
                        currentName = ""
                    }
                }
            }
        } catch (_: Exception) {
        }
        return Pair(categoriesMap.map { CategoryItem(it.key, it.value) }, channels)
    }
}
