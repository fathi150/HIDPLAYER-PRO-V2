package com.hidplayer.pro.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.hidplayer.pro.model.PlayerSettings
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class TvPlayerEngine(private val context: Context, initialSettings: PlayerSettings) {

    var exoPlayer: ExoPlayer = createPlayer(initialSettings)
        private set

    private var currentUrl: String = ""

    private fun createPlayer(settings: PlayerSettings): ExoPlayer {
        val okHttpBuilder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (settings.vpnShieldEnabled) {
            try {
                val bootstrap = OkHttpClient.Builder().build()
                val doh = DnsOverHttps.Builder()
                    .client(bootstrap)
                    .url("https://1.1.1.1/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(
                        InetAddress.getByName("1.1.1.1"),
                        InetAddress.getByName("1.0.0.1")
                    )
                    .build()
                okHttpBuilder.dns(doh)
            } catch (_: Exception) {
            }
        }

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpBuilder.build())
            .setUserAgent("HIDPLAYER-PRO/2.0 (AndroidTV)")

        val (minBuf, maxBuf, startBuf, rebuf) = when (settings.bufferMode) {
            "FAST" -> listOf(1000, 10000, 600, 1000)
            "ANTI_FREEZE" -> listOf(5000, 35000, 2500, 4000)
            else -> listOf(2000, 20000, 1200, 2000)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, startBuf, rebuf)
            .build()

        val extMode = if (settings.decoderMode == "SOFTWARE") {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(extMode)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build().apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (currentUrl.isNotBlank()) {
                            prepare()
                            play()
                        }
                    }
                })
            }
    }

    fun applySettings(settings: PlayerSettings) {
        val wasPlaying = exoPlayer.isPlaying
        val url = currentUrl
        exoPlayer.release()
        exoPlayer = createPlayer(settings)
        if (wasPlaying && url.isNotBlank()) {
            playStream(url)
        }
    }

    fun playStream(url: String) {
        currentUrl = url
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun stop() {
        exoPlayer.stop()
    }

    fun release() {
        exoPlayer.release()
    }
}
