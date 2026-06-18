package com.bluehyperx.moodgleam.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.util.Log
import com.bluehyperx.moodgleam.common.network.HyperionThread
import com.bluehyperx.moodgleam.common.util.AppOptions
import com.bluehyperx.moodgleam.common.util.ColorProcessor
import kotlin.math.max
import kotlin.math.min

class RtspCameraEncoder(
    private val listener: HyperionThread.HyperionThreadListener,
    private val options: AppOptions,
    corners: FloatArray,
    private val rtspUrl: String,
) : CameraCaptureController {

    @Volatile
    private var mRunning = false

    @Volatile
    private var mCapturing = false

    @Volatile
    private var worker: Thread? = null

    private val mCorners = corners.copyOf()
    private val frameIntervalMs = 1000L / options.frameRate
    private val outputWidth: Int
    private val outputHeight: Int
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val mBorderCropper = com.bluehyperx.moodgleam.common.util.BorderProcessor()
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
        if (mRunning) return
        mRunning = true
        worker = Thread(::captureLoop, "RtspCameraEncoder")
        worker?.start()
    }

    override fun stopRecording() {
        mRunning = false
        worker?.interrupt()
        worker = null
        mCapturing = false
        listener.sendStatus(false)
        clearAndDisconnect()
    }

    override fun stopRecordingNoDisconnect() {
        mRunning = false
        worker?.interrupt()
        worker = null
        mCapturing = false
        listener.sendStatus(false)
        clearLights()
    }

    override fun resumeRecording() {
        if (!mCapturing) start()
    }

    override fun isCapturing(): Boolean = mCapturing

    override fun sendStatus() {
        listener.sendStatus(mCapturing)
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
        // no-op for RTSP source
    }

    private fun captureLoop() {
        while (mRunning) {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(rtspUrl, hashMapOf())
                mCapturing = true
                listener.sendStatus(true)

                while (mRunning) {
                    val frameStart = System.currentTimeMillis()
                    val frame = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame == null) {
                        throw IllegalStateException("RTSP frame is null")
                    }
                    processFrame(frame)
                    frame.recycle()

                    val elapsed = System.currentTimeMillis() - frameStart
                    val sleepFor = (frameIntervalMs - elapsed).coerceAtLeast(0)
                    if (sleepFor > 0) sleep(sleepFor)
                }
            } catch (e: Exception) {
                mCapturing = false
                listener.sendStatus(false)
                Log.w(TAG, "RTSP capture failed, retrying", e)
                if (mRunning) sleep(RETRY_DELAY_MS)
            } finally {
                try {
                    retriever?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun processFrame(srcBitmap: Bitmap) {
        val width = srcBitmap.width
        val height = srcBitmap.height
        displayPts[0] = mCorners[0] * width
        displayPts[1] = mCorners[1] * height
        displayPts[2] = mCorners[2] * width
        displayPts[3] = mCorners[3] * height
        displayPts[4] = mCorners[4] * width
        displayPts[5] = mCorners[5] * height
        displayPts[6] = mCorners[6] * width
        displayPts[7] = mCorners[7] * height
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
        val cropped = mBorderCropper.applyForEncoder(rgbBuffer!!, outputWidth, outputHeight, options)
        listener.sendFrame(cropped.rgb, cropped.width, cropped.height)
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
        private const val TAG = "RtspCameraEncoder"
        private const val CLEAR_FRAMES = 5
        private const val CLEAR_DELAY_MS = 100L
        private const val RETRY_DELAY_MS = 1500L

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
