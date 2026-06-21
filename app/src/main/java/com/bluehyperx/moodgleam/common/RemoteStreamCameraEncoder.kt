package com.bluehyperx.moodgleam.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.common.network.HyperionThread
import com.bluehyperx.moodgleam.common.util.AppOptions
import com.bluehyperx.moodgleam.common.util.ColorProcessor
import kotlin.math.max
import kotlin.math.min

class RemoteStreamCameraEncoder(
    private val context: Context,
    private val listener: HyperionThread.HyperionThreadListener,
    private val options: AppOptions,
    corners: FloatArray,
    private val streamSource: String,
    private val streamUrl: String,
    private val onError: (String) -> Unit,
) : CameraCaptureController {

    @Volatile
    private var running = false

    @Volatile
    private var capturing = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var player: ExoPlayer? = null

    private val cornersCopy = corners.copyOf()
    private val frameIntervalMs = (1000L / options.frameRate).coerceAtLeast(16L)
    private val outputWidth: Int
    private val outputHeight: Int
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderCropper = com.bluehyperx.moodgleam.common.util.BorderProcessor()
    private val displayPts = FloatArray(8)
    private val dstPts = FloatArray(8)
    private val perspectiveMatrix = Matrix()
    private val correctedCanvas = Canvas()

    private var correctedBitmap: Bitmap? = null
    private var sourceBitmap: Bitmap? = null
    private var compactRgba: ByteArray? = null
    private var rowBuffer: ByteArray? = null
    private var rgbBuffer: ByteArray? = null
    private var outPixels: IntArray? = null
    private var lastFrameAt = 0L

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (!running) return
            capturing = true
            listener.sendStatus(true)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!running) return
            capturing = false
            listener.sendStatus(false)
            Log.e(TAG, "Remote stream playback failed", error)
            onError(buildErrorMessage(error))
        }
    }

    init {
        val q = if (options.captureQuality > 0) options.captureQuality else 128
        outputWidth = max(32, min(q, 512))
        outputHeight = max(32, (outputWidth * 9f / 16f).toInt())
        dstPts[0] = 0f
        dstPts[1] = 0f
        dstPts[2] = outputWidth.toFloat()
        dstPts[3] = 0f
        dstPts[4] = outputWidth.toFloat()
        dstPts[5] = outputHeight.toFloat()
        dstPts[6] = 0f
        dstPts[7] = outputHeight.toFloat()
    }

    override fun start() {
        if (running) return
        running = true
        imageThread = HandlerThread("RemoteStreamFrames").also { it.start() }
        imageHandler = Handler(imageThread!!.looper)
        mainHandler.post(::startPlayer)
    }

    override fun stopRecording() {
        stopInternal(disconnect = true)
    }

    override fun stopRecordingNoDisconnect() {
        stopInternal(disconnect = false)
    }

    override fun resumeRecording() {
        if (!capturing) start()
    }

    override fun isCapturing(): Boolean = capturing

    override fun sendStatus() {
        listener.sendStatus(capturing)
    }

    override fun clearLights() {
        Thread {
            repeat(CLEAR_FRAMES) {
                sleep(CLEAR_DELAY_MS)
                listener.clear()
            }
        }.start()
    }

    override fun setOrientation(orientation: Int) {
        // no-op
    }

    private fun startPlayer() {
        if (!running) return
        imageReader = ImageReader.newInstance(outputWidth, outputHeight, android.graphics.PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ imageSource ->
                val image = imageSource.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt >= frameIntervalMs) {
                        lastFrameAt = now
                        processImage(image)
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.w(TAG, "Failed to process remote frame", e)
                    }
                } finally {
                    image.close()
                }
            }, imageHandler)
        }

        player = ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            addListener(playerListener)
            setVideoSurface(imageReader!!.surface)
            setMediaItem(RemoteStreamSupport.buildMediaItem(streamSource, streamUrl))
            playWhenReady = true
            prepare()
        }
    }

    private fun processImage(image: Image) {
        val width = image.width
        val height = image.height
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 4) {
            throw IllegalStateException("Unsupported remote frame pixel stride: $pixelStride")
        }

        val expectedBytes = width * height * 4
        if (compactRgba == null || compactRgba!!.size != expectedBytes) {
            compactRgba = ByteArray(expectedBytes)
        }

        val compact = compactRgba!!
        if (rowStride == width * 4) {
            buffer.rewind()
            buffer.get(compact, 0, expectedBytes)
        } else {
            val rowBytes = width * 4
            if (rowBuffer == null || rowBuffer!!.size < rowBytes) {
                rowBuffer = ByteArray(rowBytes)
            }
            val row = rowBuffer!!
            val savedPos = buffer.position()
            var dstOffset = 0
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, rowBytes)
                System.arraycopy(row, 0, compact, dstOffset, rowBytes)
                dstOffset += rowBytes
            }
            buffer.position(savedPos)
        }

        val src = ensureSourceBitmap(width, height)
        src.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(compact))
        processFrame(src)
    }

    private fun ensureSourceBitmap(width: Int, height: Int): Bitmap {
        if (sourceBitmap == null || sourceBitmap!!.width != width || sourceBitmap!!.height != height) {
            sourceBitmap?.recycle()
            sourceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        return sourceBitmap!!
    }

    private fun processFrame(srcBitmap: Bitmap) {
        val width = srcBitmap.width
        val height = srcBitmap.height
        displayPts[0] = cornersCopy[0] * width
        displayPts[1] = cornersCopy[1] * height
        displayPts[2] = cornersCopy[2] * width
        displayPts[3] = cornersCopy[3] * height
        displayPts[4] = cornersCopy[4] * width
        displayPts[5] = cornersCopy[5] * height
        displayPts[6] = cornersCopy[6] * width
        displayPts[7] = cornersCopy[7] * height
        perspectiveMatrix.setPolyToPoly(displayPts, 0, dstPts, 0, 4)

        if (correctedBitmap == null || correctedBitmap!!.width != outputWidth || correctedBitmap!!.height != outputHeight) {
            correctedBitmap?.recycle()
            correctedBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            correctedCanvas.setBitmap(correctedBitmap)
        }

        val totalPixels = outputWidth * outputHeight
        if (outPixels == null || outPixels!!.size < totalPixels) {
            outPixels = IntArray(totalPixels)
        }

        correctedCanvas.drawColor(android.graphics.Color.BLACK)
        correctedCanvas.drawBitmap(srcBitmap, perspectiveMatrix, paint)
        correctedBitmap!!.getPixels(outPixels!!, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        val rgbSize = outputWidth * outputHeight * 3
        if (rgbBuffer == null || rgbBuffer!!.size < rgbSize) {
            rgbBuffer = ByteArray(rgbSize)
        }

        var idx = 0
        for (pixel in outPixels!!) {
            rgbBuffer!![idx++] = ((pixel shr 16) and 0xFF).toByte()
            rgbBuffer!![idx++] = ((pixel shr 8) and 0xFF).toByte()
            rgbBuffer!![idx++] = (pixel and 0xFF).toByte()
        }

        ColorProcessor.processRgbData(rgbBuffer!!, options)
        val cropped = borderCropper.applyForEncoder(rgbBuffer!!, outputWidth, outputHeight, options)
        listener.sendFrame(cropped.rgb, cropped.width, cropped.height)
    }

    private fun stopInternal(disconnect: Boolean) {
        if (!running && !capturing) return
        running = false
        capturing = false
        mainHandler.post {
            try {
                player?.removeListener(playerListener)
                player?.release()
            } catch (_: Exception) {
            }
            player = null

            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            imageReader = null
        }
        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null
        listener.sendStatus(false)
        if (disconnect) {
            clearAndDisconnect()
        } else {
            clearLights()
        }
    }

    private fun clearAndDisconnect() {
        Thread {
            repeat(CLEAR_FRAMES) {
                sleep(CLEAR_DELAY_MS)
                listener.clear()
            }
            listener.disconnect()
        }.start()
    }

    private fun buildErrorMessage(error: PlaybackException): String {
        val causeMessage = error.cause?.localizedMessage?.takeIf { it.isNotBlank() }
        val details = causeMessage ?: error.errorCodeName
        return context.getString(R.string.camera_remote_stream_error, details)
    }

    companion object {
        private const val TAG = "RemoteStreamEncoder"
        private const val CLEAR_FRAMES = 5
        private const val CLEAR_DELAY_MS = 100L

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
