package com.bluehyperx.moodgleam.common

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

object RemoteStreamSupport {
    const val SOURCE_INTERNAL = "internal"
    const val SOURCE_RTSP = "rtsp"
    const val SOURCE_HLS = "hls"
    const val SOURCE_HTTP = "http"
    const val SOURCE_MJPEG = "mjpeg"

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
            SOURCE_HTTP -> if (url.contains(".mp4", ignoreCase = true) || url.contains("?mp4", ignoreCase = true)) {
                builder.setMimeType(MimeTypes.VIDEO_MP4)
            }
        }
        return builder.build()
    }
}
