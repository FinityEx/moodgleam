package com.bluehyperx.moodgleam.common

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.common.util.Preferences

object RemoteStreamSupport {
    const val SOURCE_INTERNAL = "internal"
    const val SOURCE_RTSP = "rtsp"
    const val SOURCE_HLS = "hls"
    const val SOURCE_HTTP = "http"
    const val SOURCE_MJPEG = "mjpeg"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val LOW_LATENCY_MIN_BUFFER_MS = 200
    private const val LOW_LATENCY_MAX_BUFFER_MS = 900
    private const val LOW_LATENCY_PLAYBACK_BUFFER_MS = 120
    private const val LOW_LATENCY_REBUFFER_MS = 200

    data class LatencyOptions(
        val preferRtspUdp: Boolean = true,
        val lowLatencyBuffering: Boolean = false,
        val preferLlHls: Boolean = false,
        val optimizeRemoteCapture: Boolean = false,
        val optimizedCaptureWidth: Int = 128,
    )

    fun isRemoteSource(source: String?): Boolean = normalizeSource(source) != SOURCE_INTERNAL

    fun normalizeSource(source: String?): String = when (source?.trim().orEmpty()) {
        SOURCE_RTSP, SOURCE_HLS, SOURCE_HTTP, SOURCE_MJPEG -> source!!.trim()
        else -> SOURCE_INTERNAL
    }

    fun buildMediaItem(source: String?, url: String): MediaItem {
        val normalizedSource = normalizeSource(source)
        val builder = MediaItem.Builder().setUri(url)
        when (normalizedSource) {
            SOURCE_HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            SOURCE_HTTP -> builder.setMimeType(MimeTypes.VIDEO_MP4)
            // RTSP: no MIME type needed; RtspMediaSource detects from URI scheme
        }
        return builder.build()
    }

    fun readLatencyOptions(context: Context): LatencyOptions {
        val prefs = Preferences(context)
        val width = prefs.getString(R.string.pref_key_latency_remote_resolution, "128")
            ?.toIntOrNull()
            ?.coerceIn(64, 512)
            ?: 128
        return LatencyOptions(
            preferRtspUdp = prefs.getBoolean(R.string.pref_key_latency_rtsp_udp_preferred, true),
            lowLatencyBuffering = prefs.getBoolean(R.string.pref_key_latency_low_buffering, false),
            preferLlHls = prefs.getBoolean(R.string.pref_key_latency_ll_hls, false),
            optimizeRemoteCapture = prefs.getBoolean(R.string.pref_key_latency_remote_optimize, false),
            optimizedCaptureWidth = width
        )
    }

    fun resolveRemoteCaptureWidth(context: Context, fallbackWidth: Int): Int {
        val options = readLatencyOptions(context)
        return if (options.optimizeRemoteCapture) {
            options.optimizedCaptureWidth
        } else {
            fallbackWidth
        }
    }

    /**
     * Creates an appropriate [MediaSource] for the given stream source and URL.
     * This ensures ExoPlayer uses the correct protocol handler (RTSP, HLS, or progressive)
     * instead of relying solely on content sniffing which fails for many live streams
     * (e.g. go2rtc).
     */
    fun buildMediaSource(
        context: Context,
        source: String?,
        url: String,
        latencyOptions: LatencyOptions = readLatencyOptions(context),
    ): MediaSource {
        val normalizedSource = normalizeSource(source)
        val mediaItem = buildMediaItem(source, url)

        return when (normalizedSource) {
            SOURCE_RTSP -> {
                val factory = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(!latencyOptions.preferRtspUdp)
                    .setTimeoutMs(READ_TIMEOUT_MS.toLong())
                factory.createMediaSource(mediaItem)
            }
            SOURCE_HLS -> {
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                    .setReadTimeoutMs(READ_TIMEOUT_MS)
                    .setAllowCrossProtocolRedirects(true)
                val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(!latencyOptions.preferLlHls)
                    .createMediaSource(mediaItem)
            }
            else -> {
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                    .setReadTimeoutMs(READ_TIMEOUT_MS)
                    .setAllowCrossProtocolRedirects(true)
                val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
        }
    }

    fun buildPlayer(
        context: Context,
        source: String?,
        url: String,
        latencyOptions: LatencyOptions = readLatencyOptions(context),
    ): ExoPlayer {
        val player = buildConfiguredPlayer(context, latencyOptions)
        val mediaSource = buildMediaSource(context, source, url, latencyOptions)
        player.setMediaSource(mediaSource)
        player.playWhenReady = true
        player.prepare()
        return player
    }

    fun buildConfiguredPlayer(
        context: Context,
        latencyOptions: LatencyOptions = readLatencyOptions(context),
    ): ExoPlayer {
        val builder = ExoPlayer.Builder(context)
        if (latencyOptions.lowLatencyBuffering) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    LOW_LATENCY_MIN_BUFFER_MS,
                    LOW_LATENCY_MAX_BUFFER_MS,
                    LOW_LATENCY_PLAYBACK_BUFFER_MS,
                    LOW_LATENCY_REBUFFER_MS
                )
                .build()
            builder.setLoadControl(loadControl)
        }
        return builder.build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
        }
    }
}
