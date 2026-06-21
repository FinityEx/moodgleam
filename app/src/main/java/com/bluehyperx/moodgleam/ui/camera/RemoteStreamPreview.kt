package com.bluehyperx.moodgleam.ui.camera

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.common.RemoteStreamSupport

@Composable
fun RemoteStreamPreview(
    streamSource: String,
    streamUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val trimmedUrl = streamUrl.trim()
    if (trimmedUrl.isBlank()) {
        RemotePreviewMessage(
            text = context.getString(R.string.camera_remote_stream_required),
            modifier = modifier
        )
        return
    }

    if (RemoteStreamSupport.normalizeSource(streamSource) == RemoteStreamSupport.SOURCE_MJPEG) {
        MjpegPreview(
            streamUrl = trimmedUrl,
            modifier = modifier
        )
        return
    }

    var errorMessage by remember(streamSource, trimmedUrl) { mutableStateOf<String?>(null) }
    val exoPlayer = remember(streamSource, trimmedUrl) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            setMediaItem(RemoteStreamSupport.buildMediaItem(streamSource, trimmedUrl))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                errorMessage = null
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = context.getString(
                    R.string.camera_remote_stream_error,
                    error.cause?.localizedMessage ?: error.errorCodeName
                )
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    player = exoPlayer
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )
        errorMessage?.let {
            RemotePreviewMessage(
                text = it,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun RemotePreviewMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MjpegPreview(
    streamUrl: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                loadDataWithBaseURL(
                    null,
                    "<html><body style='margin:0;background:black;display:flex;align-items:center;justify-content:center;overflow:hidden;'><img src='$streamUrl' style='width:100%;height:100%;object-fit:cover;'/></body></html>",
                    "text/html",
                    "utf-8",
                    null
                )
            }
        },
        modifier = modifier,
        update = {
            it.loadDataWithBaseURL(
                null,
                "<html><body style='margin:0;background:black;display:flex;align-items:center;justify-content:center;overflow:hidden;'><img src='$streamUrl' style='width:100%;height:100%;object-fit:cover;'/></body></html>",
                "text/html",
                "utf-8",
                null
            )
        }
    )
}
