package com.bluehyperx.moodgleam.common

interface CameraCaptureController {
    fun start()
    fun stopRecording()
    fun stopRecordingNoDisconnect()
    fun resumeRecording()
    fun isCapturing(): Boolean
    fun sendStatus()
    fun clearLights()
    fun setOrientation(orientation: Int)
}
