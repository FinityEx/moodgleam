package com.bluehyperx.moodgleam.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.common.network.HyperionThread
import com.bluehyperx.moodgleam.common.util.AppOptions
import com.bluehyperx.moodgleam.common.util.ColorProcessor
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

class MjpegCameraEncoder(
    private val context: Context,
    private val listener: HyperionThread.HyperionThreadListener,
    private val options: AppOptions,
    corners: FloatArray,
    private val streamUrl: String,
    private val onError: (String) -> Unit,
) : CameraCaptureController {

    @Volatile
    private var running = false

    @Volatile
    private var capturing = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var activeStream: BufferedInputStream? = null

    @Volatile
    private var worker: Thread? = null

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
    private var rgbBuffer: ByteArray? = null
    private var outPixels: IntArray? = null

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
        worker = Thread(::captureLoop, "MjpegCameraEncoder").also { it.start() }
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

    private fun captureLoop() {
        var connection: HttpURLConnection? = null
        var stream: BufferedInputStream? = null
        try {
            connection = URL(streamUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "multipart/x-mixed-replace")
            activeConnection = connection
            connection.connect()
            stream = BufferedInputStream(connection.inputStream)
            activeStream = stream
            capturing = true
            listener.sendStatus(true)

            var lastFrameAt = 0L
            while (running) {
                val jpeg = readNextJpeg(stream) ?: break
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < frameIntervalMs) continue
                lastFrameAt = now
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: throw IllegalStateException("MJPEG frame decode failed")
                try {
                    processFrame(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
            if (running) {
                throw IllegalStateException("MJPEG stream ended")
            }
        } catch (e: Exception) {
            if (running) {
                capturing = false
                listener.sendStatus(false)
                Log.e(TAG, "MJPEG capture failed", e)
                onError(context.getString(R.string.camera_remote_stream_error, e.localizedMessage ?: "MJPEG error"))
            }
        } finally {
            activeStream = null
            activeConnection = null
            try {
                stream?.close()
            } catch (_: Exception) {
            }
            connection?.disconnect()
        }
    }

    private fun readNextJpeg(stream: BufferedInputStream): ByteArray? {
        val output = ByteArrayOutputStream(64 * 1024)
        var previous = -1
        var started = false
        while (running) {
            val current = stream.read()
            if (current == -1) return null
            if (!started) {
                if (previous == 0xFF && current == 0xD8) {
                    output.write(0xFF)
                    output.write(0xD8)
                    started = true
                }
            } else {
                output.write(current)
                if (previous == 0xFF && current == 0xD9) {
                    return output.toByteArray()
                }
            }
            previous = current
        }
        return null
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
        running = false
        capturing = false
        try { activeStream?.close() } catch (_: Exception) {}
        try { activeConnection?.disconnect() } catch (_: Exception) {}
        worker?.interrupt()
        worker = null
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

    companion object {
        private const val TAG = "MjpegCameraEncoder"
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
