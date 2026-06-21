package com.bluehyperx.moodgleam.common

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

object RemoteStreamSupport {
    const val SOURCE_INTERNAL = "internal"
    const val SOURCE_RTSP = "rtsp"
    const val SOURCE_HLS = "hls"
    const val SOURCE_HTTP = "http"
    const val SOURCE_MJPEG = "mjpeg"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

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

    /**
     * Creates an appropriate [MediaSource] for the given stream source and URL.
     * This ensures ExoPlayer uses the correct protocol handler (RTSP, HLS, or progressive)
     * instead of relying solely on content sniffing which fails for many live streams
     * (e.g. go2rtc).
     */
    fun buildMediaSource(context: Context, source: String?, url: String): MediaSource {
        val normalizedSource = normalizeSource(source)
        val mediaItem = buildMediaItem(source, url)

        return when (normalizedSource) {
            SOURCE_RTSP -> {
                val factory = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
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
                    .setAllowChunklessPreparation(true)
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

    fun buildPlayer(context: Context, source: String?, url: String): ExoPlayer {
        val player = buildConfiguredPlayer(context)
        val mediaSource = buildMediaSource(context, source, url)
        player.setMediaSource(mediaSource)
        player.playWhenReady = true
        player.prepare()
        return player
    }

    fun buildConfiguredPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
        }
    }
}
