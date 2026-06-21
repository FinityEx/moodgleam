package com.bluehyperx.moodgleam.common

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.common.network.HyperionThread
import com.bluehyperx.moodgleam.common.util.AppOptions
import com.bluehyperx.moodgleam.common.util.Preferences
import com.bluehyperx.moodgleam.common.util.TclBypass
import java.util.Objects

class ScreenGrabberService : Service() {

    private var mForegroundFailed = false
    private var mForegroundStarted = false
    private var mTclBlocked = false
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null
    private var mHandler: Handler? = null

    private var mReconnectEnabled = false
    private var mHasConnected = false
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mHyperionThread: HyperionThread? = null
    private var mFrameRate: Int = 0
    private var mCaptureQuality: Int = 0
    private var mHorizontalLEDCount: Int = 0
    private var mVerticalLEDCount: Int = 0
    private var mSendAverageColor: Boolean = false
    private var mScreenEncoder: ScreenEncoder? = null
    private var mScreencapEncoder: ScreencapEncoder? = null
    private var mAdbEncoder: AdbEncoder? = null
    private var mScreenrecordEncoder: ScreenrecordEncoder? = null
    private var mScrcpyEncoder: ScrcpyEncoder? = null
    private var mAccessibilityEncoder: AccessibilityEncoder? = null
    private var mCameraEncoder: CameraCaptureController? = null
    private var mEffectsEncoder: EffectsEncoder? = null
    private var mMusicEncoder: MusicEncoder? = null
    private var mMtkThalCaptureEncoder: MtkThalCaptureEncoder? = null
    private var mCaptureSource: String = "screen" // "screen" or "camera"
    private var mNotificationManager: NotificationManager? = null
    private var mStartError: String? = null
    private var mConnectionType = "hyperion"
    private var mProjectionResultCode: Int? = null
    private var mProjectionDataExtras: android.os.Bundle? = null

    // Holds the AppOptions handed to the active encoder, so color-pref edits made
    // mid-capture can push new values into it without restarting the session.
    @Volatile
    private var mActiveOptions: AppOptions? = null
    private var mPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? =
        null

    private val mReceiver = object : HyperionThreadBroadcaster {
        override fun onConnected() {
            if (DEBUG) Log.d(TAG, "Connected to Hyperion server")
            mHasConnected = true
            notifyActivity()
        }

        override fun onConnectionError(errorID: Int, error: String?) {
            Log.e(TAG, "Connection error: " + (error ?: "unknown"))
            if (!mHasConnected) {
                // Use appropriate error message based on connection type
                if ("adalight".equals(mConnectionType, ignoreCase = true)) {
                    mStartError = resources.getString(R.string.error_adalight_unreachable)
                } else {
                    mStartError = resources.getString(R.string.error_server_unreachable)
                }
                haltStartup()
            } else if (mReconnectEnabled) {
                Log.i(TAG, "Attempting automatic reconnect...")
            } else {
                // Use appropriate error message based on connection type
                if ("adalight".equals(mConnectionType, ignoreCase = true)) {
                    mStartError = resources.getString(R.string.error_adalight_connection_lost)
                } else {
                    mStartError = resources.getString(R.string.error_connection_lost)
                }
                stopSelf()
            }
        }

        override fun onReceiveStatus(isCapturing: Boolean) {
            if (DEBUG) Log.v(TAG, "Received status: capturing=$isCapturing")
            notifyActivity()
        }
    }

    private val mEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (Objects.requireNonNull(intent.action)) {
                Intent.ACTION_SCREEN_ON -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_ON intent received")
                    releaseWakeLock()
                    releaseWifiLock()

                    // Reset WLED client data send block after EPERM error to resume sending on screen wake
                    mHyperionThread?.resetBlockedIfWLED()

                    if (mCaptureSource == "camera") {
                        // Camera mode: just resume camera if not capturing
                        if (mCameraEncoder != null && !isCapturing) {
                            mCameraEncoder!!.resumeRecording()
                        }
                    } else {
                        // Resume whichever encoder is active
                        if (!isCapturing) {
                            if (mScreencapEncoder != null) {
                                if (DEBUG) Log.v(TAG, "Resuming screencap encoder")
                                mScreencapEncoder!!.resumeRecording()
                            } else if (mAdbEncoder != null) {
                                if (DEBUG) Log.v(TAG, "Resuming adb encoder")
                                mAdbEncoder!!.resumeRecording()
                            } else if (mScreenrecordEncoder != null) {
                                if (DEBUG) Log.v(TAG, "Resuming screenrecord encoder")
                                mScreenrecordEncoder!!.resumeRecording()
                            } else if (mScrcpyEncoder != null) {
                                if (DEBUG) Log.v(TAG, "Resuming scrcpy encoder")
                                mScrcpyEncoder!!.resumeRecording()
                            } else if (mAccessibilityEncoder != null) {
                                if (DEBUG) Log.v(TAG, "Resuming accessibility encoder")
                                mAccessibilityEncoder!!.resumeRecording()
                            } else if (mMtkThalCaptureEncoder != null) {
                                if (DEBUG) Log.v(TAG, "Resuming MTK THAL Capture encoder")
                                mMtkThalCaptureEncoder!!.resumeRecording()
                            } else if (mScreenEncoder != null) {
                                if (DEBUG) Log.v(TAG, "Resuming media projection encoder")
                                mScreenEncoder!!.resumeRecording()
                            }
                        }

                        // If MediaProjection was stopped by system (sleep), resumeRecording() won't help.
                        // Recreate encoder from saved projection data.
                        if (!isCapturing && mScreenEncoder == null && mScreencapEncoder == null && mAdbEncoder == null && mScreenrecordEncoder == null && mScrcpyEncoder == null && mAccessibilityEncoder == null && mMtkThalCaptureEncoder == null) {
                            if (DEBUG) Log.v(
                                TAG,
                                "No encoder active, trying restartEncoderFromSavedProjection"
                            )
                            restartEncoderFromSavedProjection()
                        }
                    }
                    notifyActivity()
                }

                Intent.ACTION_SCREEN_OFF -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_OFF intent received")
                    // On some TVs CPU goes into deep sleep and keepalive threads stop sending packets,
                    // causing WLED to revert to default effect after ~10s. PARTIAL_WAKE_LOCK keeps CPU alive for keepalive.
                    acquireWakeLock()
                    acquireWifiLock()
                    if (mScreenEncoder != null) mScreenEncoder!!.clearLights()
                    if (mScreencapEncoder != null) mScreencapEncoder!!.clearLights()
                    if (mAdbEncoder != null) mAdbEncoder!!.clearLights()
                    if (mScreenrecordEncoder != null) mScreenrecordEncoder!!.clearLights()
                    if (mScrcpyEncoder != null) mScrcpyEncoder!!.clearLights()
                    if (mAccessibilityEncoder != null) mAccessibilityEncoder!!.clearLights()
                    if (mMtkThalCaptureEncoder != null) mMtkThalCaptureEncoder!!.clearLights()
                    // Camera mode: keep running — camera captures external TV, screen sleep is irrelevant
                }

                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    if (DEBUG) Log.v(TAG, "ACTION_CONFIGURATION_CHANGED intent received")
                    if (mScreenEncoder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mScreenEncoder!!.setOrientation(resources.configuration.orientation)
                    }
                    mScreencapEncoder?.setOrientation(resources.configuration.orientation)
                    mAdbEncoder?.setOrientation(resources.configuration.orientation)
                    mScreenrecordEncoder?.setOrientation(resources.configuration.orientation)
                    mScrcpyEncoder?.setOrientation(resources.configuration.orientation)
                    mAccessibilityEncoder?.setOrientation(resources.configuration.orientation)
                    mCameraEncoder?.setOrientation(resources.configuration.orientation)
                    mMtkThalCaptureEncoder?.setOrientation(resources.configuration.orientation)
                }

                Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SHUTDOWN|ACTION_REBOOT intent received")
                    stopAllCapture()
                }
            }
        }
    }

    override fun onCreate() {
        sInstanceRunning = true
        mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        mHandler = Handler(Looper.getMainLooper())

        // Try shell bypass on startup for TCL devices
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.i(TAG, "Detected restricted manufacturer, attempting shell bypass")
            TclBypass.tryShellBypass(this)
        }

        super.onCreate()
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun prepared(): Boolean {
        val prefs = Preferences(baseContext)
        mConnectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        val host = prefs.getString(R.string.pref_key_host, null)
        val port = prefs.getInt(R.string.pref_key_port, -1)
        val priority =
            prefs.getString(R.string.pref_key_priority, "100")?.takeIf { it.isNotBlank() } ?: "100"
        mFrameRate = prefs.getInt(R.string.pref_key_framerate).coerceAtLeast(1)

        try {
            val captureQualityStr = prefs.getString(R.string.pref_key_capture_quality, "128")
                ?.takeIf { it.isNotBlank() } ?: "128"
            mCaptureQuality = Integer.parseInt(captureQualityStr)
        } catch (e: NumberFormatException) {
            mCaptureQuality = 128
        }

        mHorizontalLEDCount = prefs.getInt(R.string.pref_key_x_led)
        mVerticalLEDCount = prefs.getInt(R.string.pref_key_y_led)
        mSendAverageColor = prefs.getBoolean(R.string.pref_key_use_avg_color)
        mReconnectEnabled = prefs.getBoolean(R.string.pref_key_reconnect)
        val delay = prefs.getInt(R.string.pref_key_reconnect_delay)
        val baudRate = prefs.getInt(R.string.pref_key_adalight_baudrate)
        val wledColorOrder = prefs.getString(R.string.pref_key_wled_color_order, "rgb")

        val wledProtocol = prefs.getString(R.string.pref_key_wled_protocol, "ddp") ?: "ddp"
        val wledRgbw = prefs.getBoolean(R.string.pref_key_wled_rgbw, false)
        val wledBrightness = prefs.getInt(R.string.pref_key_wled_brightness, 255)

        val adalightProtocol = prefs.getString(R.string.pref_key_adalight_protocol, "ada") ?: "ada"

        val smoothingEnabled = prefs.getBoolean(R.string.pref_key_smoothing_enabled, false)
        val smoothingPreset = prefs.getString(R.string.pref_key_smoothing_preset, "off") ?: "off"
        val settlingTime = prefs.getInt(R.string.pref_key_settling_time, 50)
        val outputDelayMs = prefs.getInt(R.string.pref_key_output_delay, 0).toLong()
        val updateFrequency = prefs.getInt(R.string.pref_key_update_frequency, 60)

        // For Adalight and Bluetooth, host and port are not required
        if (!"adalight".equals(mConnectionType, ignoreCase = true) && 
            !"bluetooth".equals(mConnectionType, ignoreCase = true)) {
            if (host == null || host == "0.0.0.0" || host == "") {
                mStartError = resources.getString(R.string.error_empty_host)
                return false
            }
            if (port == -1) {
                mStartError = resources.getString(R.string.error_empty_port)
                return false
            }
            // Validate port range (1-65535)
            if (port < 1 || port > 65535) {
                mStartError = "Invalid port: $port (must be between 1 and 65535)"
                return false
            }
        }

        if (mHorizontalLEDCount <= 0 || mVerticalLEDCount <= 0) {
            mStartError = resources.getString(R.string.error_invalid_led_counts)
            return false
        }
        mMediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")
        if (mMediaProjectionManager == null && method == "media_projection") {
            mStartError = resources.getString(R.string.error_media_projection_denied)
            return false
        }
        // Безопасный парсинг приоритета (на случай пустой или некорректной строки)
        val priorityValue = try {
            priority.toInt()
        } catch (e: NumberFormatException) {
            100
        }

        val finalHost = host ?: "localhost"
        val finalPort = if (port > 0) port else 19400

        mHyperionThread = HyperionThread(
            mReceiver, finalHost, finalPort, priorityValue,
            mReconnectEnabled, delay, mConnectionType, baseContext, baudRate, wledColorOrder,
            wledProtocol, wledRgbw, wledBrightness, adalightProtocol,
            smoothingEnabled, smoothingPreset, settlingTime, outputDelayMs, updateFrequency
        )
        mHyperionThread!!.start()
        mStartError = null
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) Log.v(TAG, "Start command received")

        // Must call startForeground within ~5s of every startForegroundService call
        // regardless of action, otherwise Android throws ForegroundServiceDidNotStartInTime.
        ensureForegroundStarted(initialForegroundTypeFor(intent?.action))

        super.onStartCommand(intent, flags, startId)
        if (intent == null || intent.action == null) {
            val nullItem = if (intent == null) "intent" else "action"
            if (DEBUG) Log.v(TAG, "Null $nullItem provided to start command")
            stopSelf()
            return START_NOT_STICKY
        } else {
            val action = intent.action
            if (DEBUG) Log.v(TAG, "Start command action: " + action.toString())
            when (action) {
                ACTION_START -> if (mHyperionThread == null) {
                    mCaptureSource = "screen"
                    val prefs = Preferences(this)
                    val method =
                        prefs.getString(R.string.pref_key_capture_method, "media_projection")

                    val useMediaProjection = method == "media_projection"
                    val foregroundType = if (useMediaProjection)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    else
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

                    // Start foreground with appropriate type
                    val foregroundStarted = tryStartForegroundCompat(foregroundType)

                    val isPrepared = prepared()
                    if (isPrepared) {
                        if (!foregroundStarted && mTclBlocked) {
                            acquireWakeLock()
                        }

                        if (useMediaProjection) {
                            try {
                                startScreenRecord(intent)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Failed to start screen recording: " + e.message)
                                mStartError =
                                    resources.getString(R.string.error_media_projection_denied)
                                haltStartup()
                                return START_STICKY
                            }
                        } else {
                            startAlternativeRecord(method!!)
                        }

                        registerEventReceiver()
                    } else {
                        haltStartup()
                    }
                }

                ACTION_START_CAMERA -> if (mHyperionThread == null) {
                    mCaptureSource = "camera"
                    val foregroundStarted = tryStartForegroundCamera()

                    val isPrepared = prepared()
                    if (isPrepared) {
                        if (!foregroundStarted && mTclBlocked) {
                            acquireWakeLock()
                        }

                        startCameraCapture()
                        registerEventReceiver()
                    } else {
                        haltStartup()
                    }
                }

                ACTION_START_EFFECTS -> if (mHyperionThread == null) {
                    mCaptureSource = "effects"
                    val foregroundStarted = tryStartForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

                    val isPrepared = prepared()
                    if (isPrepared) {
                        if (!foregroundStarted && mTclBlocked) {
                            acquireWakeLock()
                        }

                        startEffectsCapture()
                        registerEventReceiver()
                    } else {
                        haltStartup()
                    }
                }

                ACTION_START_MUSIC -> if (mHyperionThread == null) {
                    mCaptureSource = "music"
                    
                    val prefs = Preferences(this)
                    val musicMethod = prefs.getString(R.string.pref_key_music_capture_method, "mic") ?: "mic"
                    
                    val foregroundType = if (musicMethod == "system_audio" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    }

                    val foregroundStarted = tryStartForegroundCompat(foregroundType)

                    val isPrepared = prepared()
                    if (isPrepared) {
                        if (!foregroundStarted && mTclBlocked) {
                            acquireWakeLock()
                        }

                        startMusicRecord(intent)
                        registerEventReceiver()
                    } else {
                        haltStartup()
                    }
                }

                ACTION_STOP -> stopAllCapture()
                ACTION_UPDATE_EFFECTS -> {
                    if (DEBUG) Log.v(TAG, "Updating effects encoder settings")
                    mEffectsEncoder?.refreshSettings()
                    mMusicEncoder?.refreshSettings()
                }
                ACTION_CLEAR -> {
                    // Send one black frame but keep connection
                    if (mScreenEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (screen)")
                        mScreenEncoder!!.clearLights()
                    }
                    if (mScreencapEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (screencap)")
                        mScreencapEncoder!!.clearLights()
                    }
                    if (mAdbEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (adb)")
                        mAdbEncoder!!.clearLights()
                    }
                    if (mScreenrecordEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (screenrecord)")
                        mScreenrecordEncoder!!.clearLights()
                    }
                    if (mScrcpyEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (scrcpy)")
                        mScrcpyEncoder!!.clearLights()
                    }
                    if (mAccessibilityEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (accessibility)")
                        mAccessibilityEncoder!!.clearLights()
                    }
                    if (mCameraEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (camera)")
                        mCameraEncoder!!.clearLights()
                    }
                    if (mMtkThalCaptureEncoder != null) {
                        if (DEBUG) Log.v(
                            TAG,
                            "ACTION_CLEAR: clearing lights once (mtk_thal_capture)"
                        )
                        mMtkThalCaptureEncoder!!.clearLights()
                    }
                }

                GET_STATUS -> notifyActivity()
                ACTION_EXIT -> stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        if (DEBUG) Log.v(TAG, "Ending service")

        try {
            unregisterReceiver(mEventReceiver)
        } catch (e: Exception) {
            if (DEBUG) Log.v(TAG, "Wake receiver not registered")
        }

        unregisterSettingsPrefsListener()
        mActiveOptions = null

        releaseWakeLock()
        stopAllCapture()
        stopForeground(true)
        mForegroundStarted = false
        notifyActivity()
        sInstanceRunning = false

        super.onDestroy()
    }

    private fun registerEventReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_SCREEN_ON)
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        intentFilter.addAction(Intent.ACTION_REBOOT)
        intentFilter.addAction(Intent.ACTION_SHUTDOWN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mEventReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mEventReceiver, intentFilter)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun initialForegroundTypeFor(action: String?): Int = when (action) {
        ACTION_START_CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        ACTION_START -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ACTION_START_MUSIC -> {
            val prefs = Preferences(this)
            val musicMethod = prefs.getString(R.string.pref_key_music_capture_method, "mic") ?: "mic"
            if (musicMethod == "system_audio") {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    /** Idempotent startForeground — safe to call multiple times. */
    private fun ensureForegroundStarted(type: Int) {
        if (mForegroundStarted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            mForegroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "ensureForegroundStarted failed (type=$type): ${e.message}")
            val msg = e.message
            if (msg != null && (msg.contains("TclAppBoot") || msg.contains("forbid"))) {
                mTclBlocked = true
            }
        }
    }

    private fun tryStartForegroundCompat(type: Int): Boolean {
        if (mForegroundStarted) {
            mForegroundFailed = false
            mTclBlocked = false
            return true
        }
        mForegroundFailed = false
        mTclBlocked = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    type
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            mForegroundStarted = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start failed: " + e.message)
            mForegroundFailed = true
            val msg = e.message
            if (msg != null && (msg.contains("TclAppBoot") || msg.contains("forbid"))) {
                mTclBlocked = true
            }
        }

        if (mForegroundFailed) {
            try {
                Thread.sleep(100)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, notification,
                        type
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                mForegroundFailed = false
                mTclBlocked = false
                mForegroundStarted = true
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Foreground retry failed: " + e.message)
                mTclBlocked = true
            }
        }

        notifyTclBlocked()
        return false
    }

    private fun tryStartForegroundCamera(): Boolean {
        if (mForegroundStarted) {
            mForegroundFailed = false
            mTclBlocked = false
            return true
        }
        mForegroundFailed = false
        mTclBlocked = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            mForegroundStarted = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start (camera) failed: " + e.message)
            mForegroundFailed = true
            val msg = e.message
            if (msg != null && (msg.contains("TclAppBoot") || msg.contains("forbid"))) {
                mTclBlocked = true
            }
        }

        // Retry
        if (mForegroundFailed) {
            try {
                Thread.sleep(100)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                mForegroundFailed = false
                mTclBlocked = false
                mForegroundStarted = true
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Foreground retry (camera) failed: " + e.message)
                mTclBlocked = true
            }
        }

        notifyTclBlocked()
        return false
    }

    private fun startCameraCapture() {
        if (DEBUG) Log.v(TAG, "Starting camera capture")
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start camera capture")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }

        val prefs = Preferences(this)
        val options = buildAppOptions(prefs)
        val cameraInputSource =
            prefs.getString(R.string.pref_key_camera_input_source, "internal") ?: "internal"

        val cornersStr = prefs.getString(R.string.pref_key_camera_corners, null)
        val corners = CameraEncoder.parseCornersString(cornersStr)

        try {
            mCameraEncoder = if (RemoteStreamSupport.isRemoteSource(cameraInputSource)) {
                val streamUrl = prefs.getString(R.string.pref_key_camera_rtsp_url, "")?.trim().orEmpty()
                val remoteCaptureWidth = RemoteStreamSupport.resolveRemoteCaptureWidth(this, options.captureQuality)
                if (streamUrl.isBlank()) {
                    Log.w(TAG, "Remote camera source selected but URL is blank")
                    mStartError = resources.getString(
                        R.string.pref_error_missing_field,
                        resources.getString(R.string.pref_title_camera_rtsp_url)
                    )
                    haltStartup()
                    return
                } else if (RemoteStreamSupport.normalizeSource(cameraInputSource) == RemoteStreamSupport.SOURCE_MJPEG) {
                    MjpegCameraEncoder(
                        this,
                        thread.receiver,
                        options,
                        corners,
                        streamUrl,
                        remoteCaptureWidth
                    ) { error -> stopCameraCaptureWithError(error) }
                } else {
                    RemoteStreamCameraEncoder(
                        this,
                        thread.receiver,
                        options,
                        corners,
                        cameraInputSource,
                        streamUrl,
                        remoteCaptureWidth
                    ) { error -> stopCameraCaptureWithError(error) }
                }
            } else {
                CameraEncoder(this, thread.receiver, options, corners)
            }
            mCameraEncoder!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera capture", e)
            val details = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            mStartError = if (RemoteStreamSupport.isRemoteSource(cameraInputSource)) {
                resources.getString(R.string.camera_remote_stream_error, details)
            } else {
                details
            }
            haltStartup()
        }
    }

    private fun startEffectsCapture() {
        if (DEBUG) Log.v(TAG, "Starting effects capture")
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start effects capture")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }

        mEffectsEncoder = EffectsEncoder(
            this,
            thread,
            mFrameRate
        )
        mEffectsEncoder!!.start()
    }

    private fun startMusicCapture() {
        if (DEBUG) Log.v(TAG, "Starting music capture")
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start music capture")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }

        mMusicEncoder = MusicEncoder(
            this,
            thread,
            sMediaProjection, // Use existing projection for digital audio
            mFrameRate
        )
        mMusicEncoder!!.start()
    }

    private fun acquireWakeLock() {
        if (mWakeLock == null) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "ScreenGrabberService::ScreenCapture"
                    )
                    mWakeLock!!.acquire(60 * 60 * 1000L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock", e)
            }
        }
    }

    private fun releaseWakeLock() {
        if (mWakeLock != null && mWakeLock!!.isHeld) {
            try {
                mWakeLock!!.release()
                Log.i(TAG, "Wake lock released")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release wake lock", e)
            }
            mWakeLock = null
        }
    }

    private fun acquireWifiLock() {
        if (mWifiLock != null && mWifiLock!!.isHeld) return
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                // HighPerf to prevent UDP throttling during idle (helps on some Android TV firmwares)
                mWifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "ScreenGrabberService::Wifi"
                )
                mWifiLock?.setReferenceCounted(false)
                mWifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifi lock", e)
        }
    }

    private fun releaseWifiLock() {
        val lock = mWifiLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wifi lock", e)
        }
        mWifiLock = null
    }

    private fun notifyTclBlocked() {
        val intent = Intent(BROADCAST_FILTER)
        intent.putExtra(BROADCAST_TAG, false)
        intent.putExtra(BROADCAST_TCL_BLOCKED, true)
        intent.putExtra(BROADCAST_ERROR, "Foreground service blocked by device manufacturer")
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun haltStartup() {
        // Try to start foreground to show error, but don't fail if blocked
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground during halt: " + e.message)
        }

        notifyActivity()

        if (mHyperionThread != null) {
            mHyperionThread!!.interrupt()
            mHyperionThread = null
        }

        stopSelf()
    }

    private fun stopCameraCaptureWithError(error: String) {
        mStartError = error
        notifyActivity()
        stopAllCapture()
        stopSelf()
    }

    private fun buildExitButton(): Intent {
        val notificationIntent = Intent(this, this.javaClass)
        notificationIntent.flags = Intent.FLAG_RECEIVER_FOREGROUND
        notificationIntent.action = ACTION_EXIT
        return notificationIntent
    }

    val notification: Notification
        get() {
            val mgr = mNotificationManager
                ?: (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            if (mgr == null) {
                throw IllegalStateException("NotificationManager is null")
            }
            val notification = AppNotification(this, mgr)
            val label = getString(R.string.notification_exit_button)
            notification.setAction(NOTIFICATION_EXIT_INTENT_ID, label, buildExitButton())
            return notification.buildNotification()
        }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startScreenRecord(intent: Intent) {
        if (DEBUG) Log.v(TAG, "Starting screen recorder")
        val projectionManager = mMediaProjectionManager
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager is null; cannot start screen recording")
            mStartError = resources.getString(R.string.error_media_projection_denied)
            haltStartup()
            return
        }
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start screen recording")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        // Use the passed projection intent directly if available (safer for restricted devices)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        // Save projection data to restore after sleep/wake on TV
        if (resultData != null) {
            saveProjectionData(resultCode, resultData.extras)
        } else {
            // Fallback for older version/unexpected call
            saveProjectionData(resultCode, intent.extras)
        }

        val projectionDataIntent = buildProjectionDataIntent()
        val projectionIntent = resultData ?: projectionDataIntent ?: intent

        val projection = projectionManager.getMediaProjection(
            resultCode,
            projectionIntent
        )
        val window = getSystemService(WINDOW_SERVICE) as WindowManager

        if (projection != null && window != null) {
            sMediaProjection = projection
            val metrics = DisplayMetrics()
            window.defaultDisplay.getRealMetrics(metrics)

            val prefs = Preferences(this)
            val options = buildAppOptions(prefs)

            if (DEBUG) Log.v(
                TAG,
                "Creating encoder: " + metrics.widthPixels + "x" + metrics.heightPixels
            )
            mScreenEncoder = ScreenEncoder(
                thread.receiver,
                projection,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                options
            )
            mScreenEncoder!!.sendStatus()
        } else {
            if (projection == null) {
                Log.e(
                    TAG,
                    "MediaProjection is null (resultCode=$resultCode). Permission likely missing/invalid."
                )
                mStartError = resources.getString(R.string.error_media_projection_denied)
                haltStartup()
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startMusicRecord(intent: Intent) {
        val prefs = Preferences(this)
        val musicMethod = prefs.getString(R.string.pref_key_music_capture_method, "mic") ?: "mic"
        
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start music recording")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }

        if (musicMethod == "system_audio") {
            if (DEBUG) Log.v(TAG, "Starting music recorder (digital)")
            val projectionManager = mMediaProjectionManager
            if (projectionManager == null) {
                Log.e(TAG, "MediaProjectionManager is null; cannot start music recording")
                mStartError = resources.getString(R.string.error_media_projection_denied)
                haltStartup()
                return
            }
            
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            
            // If the user denied the screen capture prompt, resultCode will not be RESULT_OK (-1)
            if (resultCode != android.app.Activity.RESULT_OK) {
                Log.e(TAG, "MediaProjection permission denied by user (resultCode=$resultCode)")
                mStartError = resources.getString(R.string.error_media_projection_denied)
                haltStartup()
                return
            }

            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultData != null) {
                saveProjectionData(resultCode, resultData.extras)
            } else {
                saveProjectionData(resultCode, intent.extras)
            }

            val projectionDataIntent = buildProjectionDataIntent()
            val projectionIntent = resultData ?: projectionDataIntent ?: intent

            val projection = try {
                projectionManager.getMediaProjection(resultCode, projectionIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get MediaProjection", e)
                null
            }

            if (projection != null) {
                sMediaProjection = projection
                startMusicCapture()
            } else {
                Log.e(TAG, "MediaProjection for Music is null. Permission denied.")
                mStartError = resources.getString(R.string.error_media_projection_denied)
                haltStartup()
            }
        } else {
            if (DEBUG) Log.v(TAG, "Starting music recorder (microphone)")
            // Microphone mode doesn't need MediaProjection, skip all projection checks
            sMediaProjection = null
            mStartError = null // Clear any residual errors
            startMusicCapture()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startAlternativeRecord(method: String) {
        if (DEBUG) Log.v(TAG, "Starting alternative recorder: $method")
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start recording")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }
        val window = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        window.defaultDisplay.getRealMetrics(metrics)

        val prefs = Preferences(this)
        val options = buildAppOptions(prefs)

        if (method == "accessibility") {
            val accessibilityService = AccessibilityCaptureService.getInstance()
            if (accessibilityService != null) {
                if (DEBUG) Log.v(TAG, "Creating Accessibility encoder")
                mAccessibilityEncoder = AccessibilityEncoder(
                    accessibilityService,
                    thread.receiver,
                    metrics.widthPixels,
                    metrics.heightPixels,
                    options
                )
                mAccessibilityEncoder!!.sendStatus()
            } else {
                Log.e(TAG, "Accessibility Service not connected!")
                mStartError = "Accessibility Service not enabled"
                haltStartup()
            }
            return
        }

        if (method == "adb_local") {
            val adbPort = prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555
            if (DEBUG) Log.v(TAG, "Creating ADB encoder on port $adbPort")
            mAdbEncoder = AdbEncoder(
                this.applicationContext,
                thread.receiver,
                metrics.widthPixels,
                metrics.heightPixels,
                options,
                adbPort
            )
            mAdbEncoder!!.sendStatus()
            return
        }

        if (method == "adb_stream") {
            val adbPort = prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555
            if (DEBUG) Log.v(TAG, "Creating screenrecord (H.264 stream) encoder on port $adbPort")
            mScreenrecordEncoder = ScreenrecordEncoder(
                this.applicationContext,
                thread.receiver,
                metrics.widthPixels,
                metrics.heightPixels,
                options,
                adbPort,
                onFatalError = { errorMsg ->
                    mStartError = errorMsg
                    haltStartup()
                }
            )
            mScreenrecordEncoder!!.sendStatus()
            return
        }

        if (method == "scrcpy") {
            val adbPort = prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555
            if (DEBUG) Log.v(TAG, "Creating scrcpy encoder on port $adbPort")
            mScrcpyEncoder = ScrcpyEncoder(
                this.applicationContext,
                thread.receiver,
                metrics.widthPixels,
                metrics.heightPixels,
                options,
                adbPort,
                onFatalError = { errorMsg ->
                    mStartError = errorMsg
                    haltStartup()
                }
            )
            mScrcpyEncoder!!.sendStatus()
            return
        }

        if (method == "mtk_thal_capture") {
            if (DEBUG) Log.v(TAG, "Creating MTK THAL Capture encoder")
            mMtkThalCaptureEncoder = MtkThalCaptureEncoder(
                this.applicationContext,
                thread.receiver,
                metrics.widthPixels,
                metrics.heightPixels,
                options,
                onFatalError = { errorMsg ->
                    mStartError = errorMsg
                    haltStartup()
                }
            )
            mMtkThalCaptureEncoder!!.sendStatus()
            return
        }

        val useRoot = method == "screencap_root"
        if (DEBUG) Log.v(TAG, "Creating screencap encoder (root=$useRoot)")
        mScreencapEncoder = ScreencapEncoder(
            this.applicationContext,
            thread.receiver,
            metrics.widthPixels,
            metrics.heightPixels,
            options,
            useRoot,
            onFatalError = { errorMsg ->
                mStartError = errorMsg
                haltStartup()
            }
        )
        mScreencapEncoder!!.sendStatus()
    }

    private fun saveProjectionData(resultCode: Int, extras: android.os.Bundle?) {
        mProjectionResultCode = resultCode
        if (extras != null) {
            val copy = android.os.Bundle(extras)
            copy.remove(EXTRA_RESULT_CODE)
            mProjectionDataExtras = copy
        }
    }

    private fun buildProjectionDataIntent(): Intent? {
        val extras = mProjectionDataExtras ?: return null
        return Intent().apply { replaceExtras(extras) }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun restartEncoderFromSavedProjection() {
        // In screencap mode there is no projection to restore — just resume the capture loop
        if (mScreencapEncoder != null) {
            mScreencapEncoder!!.resumeRecording()
            return
        }
        val resultCode = mProjectionResultCode ?: return
        val projectionIntent = buildProjectionDataIntent() ?: return
        if (mMediaProjectionManager == null) return
        if (mHyperionThread == null) return

        // Stop old encoder without disconnecting (important for WLED keepalive)
        try {
            mScreenEncoder?.stopRecordingNoDisconnect()
        } catch (e: Exception) {
            // no-op
        }
        mScreenEncoder = null

        releaseResource()

        try {
            val projection =
                mMediaProjectionManager!!.getMediaProjection(resultCode, projectionIntent)
            val window = getSystemService(WINDOW_SERVICE) as WindowManager
            if (projection == null || window == null) {
                return
            }

            sMediaProjection = projection
            val metrics = DisplayMetrics()
            window.defaultDisplay.getRealMetrics(metrics)

            val prefs = Preferences(this)
            val options = buildAppOptions(prefs)

            mScreenEncoder = ScreenEncoder(
                mHyperionThread!!.receiver,
                projection,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                options
            )
            mScreenEncoder!!.sendStatus()
        } catch (e: SecurityException) {
            // MediaProjection token may have expired or been revoked by system.
            // Don't crash from broadcast receiver, just log error and stop.
            Log.e(TAG, "Failed to restart encoder from saved projection: ${e.message}", e)
            mStartError = resources.getString(R.string.error_media_projection_denied)
            mProjectionResultCode = null
            mProjectionDataExtras = null
            releaseResource()
        }
    }

    private fun stopAllCapture() {
        if (DEBUG) Log.v(TAG, "Stopping all capture")
        mReconnectEnabled = false
        mNotificationManager?.cancel(NOTIFICATION_ID)

        if (mScreenEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping screen encoder")
            mScreenEncoder!!.stopRecording()
            mScreenEncoder = null
        }

        if (mScreencapEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping screencap encoder")
            mScreencapEncoder!!.stopRecording()
            mScreencapEncoder = null
        }

        if (mAdbEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping adb encoder")
            mAdbEncoder!!.stopRecording()
            mAdbEncoder = null
        }

        if (mScreenrecordEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping screenrecord encoder")
            mScreenrecordEncoder!!.stopRecording()
            mScreenrecordEncoder = null
        }

        if (mScrcpyEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping scrcpy encoder")
            mScrcpyEncoder!!.stopRecording()
            mScrcpyEncoder = null
        }

        if (mAccessibilityEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping accessibility encoder")
            mAccessibilityEncoder!!.stopRecording()
            mAccessibilityEncoder = null
        }

        if (mCameraEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping camera encoder")
            mCameraEncoder!!.stopRecording()
            mCameraEncoder = null
        }

        if (mMtkThalCaptureEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping MTK THAL Capture encoder")
            mMtkThalCaptureEncoder!!.stopRecording()
            mMtkThalCaptureEncoder = null
        }

        if (mEffectsEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping effects encoder")
            mEffectsEncoder!!.stop()
            mEffectsEncoder = null
        }

        if (mMusicEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping music encoder")
            mMusicEncoder!!.stop()
            mMusicEncoder = null
        }

        // Graceful LED shutdown (send final black frame)
        // Now called AFTER all encoders are stopped, so no new frames will overwrite this
        if (mHyperionThread != null && mHasConnected) {
            try {
                mHyperionThread!!.clearAllImmediate()
                Thread.sleep(250) // Wait for hardware to process
            } catch (e: Exception) {
                Log.e(TAG, "Graceful shutdown error", e)
            }
        }

        releaseResource()

        if (mHyperionThread != null) {
            mHyperionThread!!.receiver.disconnect()
            mHyperionThread!!.interrupt()
            mHyperionThread = null
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun releaseResource() {
        if (sMediaProjection != null) {
            sMediaProjection!!.stop()
            sMediaProjection = null
        }
    }

    val isCapturing: Boolean
        get() = (mScreenEncoder != null && mScreenEncoder!!.isCapturing()) ||
                (mScreencapEncoder != null && mScreencapEncoder!!.isCapturing()) ||
                (mAdbEncoder != null && mAdbEncoder!!.isCapturing()) ||
                (mScreenrecordEncoder != null && mScreenrecordEncoder!!.isCapturing()) ||
                (mScrcpyEncoder != null && mScrcpyEncoder!!.isCapturing()) ||
                (mAccessibilityEncoder != null && mAccessibilityEncoder!!.isCapturing()) ||
                (mCameraEncoder != null && mCameraEncoder!!.isCapturing()) ||
                (mMtkThalCaptureEncoder != null && mMtkThalCaptureEncoder!!.isCapturing()) ||
                (mEffectsEncoder != null) ||
                (mMusicEncoder != null)

    val isCommunicating: Boolean
        get() = isCapturing && mHasConnected

    private fun notifyActivity() {
        val intent = Intent(BROADCAST_FILTER)
        intent.putExtra(BROADCAST_TAG, isCommunicating)
        intent.putExtra(BROADCAST_ERROR, mStartError)
        
        // Include device name/host for the UI from the active client
        val deviceDetail = mHyperionThread?.getConnectedDeviceName()
        intent.putExtra(BROADCAST_DEVICE_NAME, deviceDetail)

        if (DEBUG) {
            Log.v(
                TAG, "Broadcasting status: communicating=" + isCommunicating +
                        if (mStartError != null) ", error=$mStartError" else ""
            )
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun buildAppOptions(prefs: Preferences): AppOptions {
        val opts = AppOptions(
            mHorizontalLEDCount, mVerticalLEDCount, mFrameRate, mSendAverageColor, mCaptureQuality,
            brightness = prefs.getInt(R.string.pref_key_color_brightness, 100),
            contrast = prefs.getInt(R.string.pref_key_color_contrast, 100),
            blackLevel = prefs.getInt(R.string.pref_key_color_black_level, 0),
            whiteLevel = prefs.getInt(R.string.pref_key_color_white_level, 100),
            saturation = prefs.getInt(R.string.pref_key_color_saturation, 100),
            colorProcessingEnabled = prefs.getBoolean(
                R.string.pref_key_color_processing_enabled,
                true
            ),
            brightnessR = prefs.getInt(R.string.pref_key_color_brightness_r, 100),
            brightnessG = prefs.getInt(R.string.pref_key_color_brightness_g, 100),
            brightnessB = prefs.getInt(R.string.pref_key_color_brightness_b, 100),
            gammaR = prefs.getInt(R.string.pref_key_color_gamma_r, 100),
            gammaG = prefs.getInt(R.string.pref_key_color_gamma_g, 100),
            gammaB = prefs.getInt(R.string.pref_key_color_gamma_b, 100),
            borderDetectionEnabled = prefs.getBoolean(
                R.string.pref_key_border_detection_enabled,
                false
            ),
            borderThreshold = prefs.getInt(R.string.pref_key_border_threshold, 18).coerceIn(0, 64),
            borderCheckIntervalFrames = prefs.getInt(R.string.pref_key_border_check_interval, 60)
                .coerceIn(1, 300)
        )
        mActiveOptions = opts
        registerSettingsPrefsListener()
        return opts
    }

    private fun registerSettingsPrefsListener() {
        if (mPrefsListener != null) return
        val sharedPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        
        val keyBrightness = getString(R.string.pref_key_color_brightness)
        val keyContrast = getString(R.string.pref_key_color_contrast)
        val keyBlack = getString(R.string.pref_key_color_black_level)
        val keyWhite = getString(R.string.pref_key_color_white_level)
        val keySaturation = getString(R.string.pref_key_color_saturation)
        val keyEnabled = getString(R.string.pref_key_color_processing_enabled)
        val keyBr = getString(R.string.pref_key_color_brightness_r)
        val keyBg = getString(R.string.pref_key_color_brightness_g)
        val keyBb = getString(R.string.pref_key_color_brightness_b)
        val keyGr = getString(R.string.pref_key_color_gamma_r)
        val keyGg = getString(R.string.pref_key_color_gamma_g)
        val keyGb = getString(R.string.pref_key_color_gamma_b)
        val keyBorderOn = getString(R.string.pref_key_border_detection_enabled)
        val keyBorderTh = getString(R.string.pref_key_border_threshold)
        val keyBorderIv = getString(R.string.pref_key_border_check_interval)
        
        val colorKeys = setOf(
            keyBrightness, keyContrast, keyBlack, keyWhite, keySaturation, keyEnabled,
            keyBr, keyBg, keyBb, keyGr, keyGg, keyGb
        )
        val borderKeys = setOf(keyBorderOn, keyBorderTh, keyBorderIv)

        // Keys that require restarting the capture session (encoders)
        val captureKeys = mutableSetOf(
            getString(R.string.pref_key_capture_source),
            getString(R.string.pref_key_camera_input_source),
            getString(R.string.pref_key_camera_rtsp_url),
            getString(R.string.pref_key_camera_corners),
            getString(R.string.pref_key_capture_method),
            getString(R.string.pref_key_music_capture_method),
            getString(R.string.pref_key_framerate),
            getString(R.string.pref_key_capture_quality),
            getString(R.string.pref_key_latency_rtsp_udp_preferred),
            getString(R.string.pref_key_latency_low_buffering),
            getString(R.string.pref_key_latency_ll_hls),
            getString(R.string.pref_key_latency_remote_optimize),
            getString(R.string.pref_key_latency_remote_resolution),
            getString(R.string.pref_key_use_avg_color),
            getString(R.string.pref_key_x_led),
            getString(R.string.pref_key_y_led)
        )
        
        // Add all possible per-effect music sensitivity keys to capture reload
        listOf("vu_meter", "pulse", "frequency_analyzer", "beat_flash", "waterfall", "neon_trails", "spectrogram", "color_wave").forEach {
            captureKeys.add("pref_key_music_sensitivity_$it")
        }

        // Keys that require a full restart (including network thread)
        val networkKeys = setOf(
            getString(R.string.pref_key_connection_type),
            getString(R.string.pref_key_host),
            getString(R.string.pref_key_port),
            getString(R.string.pref_key_priority),
            getString(R.string.pref_key_reconnect),
            getString(R.string.pref_key_reconnect_delay),
            getString(R.string.pref_key_adalight_baudrate),
            getString(R.string.pref_key_wled_color_order),
            getString(R.string.pref_key_wled_protocol),
            getString(R.string.pref_key_wled_rgbw),
            getString(R.string.pref_key_wled_brightness),
            getString(R.string.pref_key_adalight_protocol),
            getString(R.string.pref_key_latency_prefer_ddp),
            getString(R.string.pref_key_smoothing_enabled),
            getString(R.string.pref_key_smoothing_preset),
            getString(R.string.pref_key_settling_time),
            getString(R.string.pref_key_output_delay),
            getString(R.string.pref_key_update_frequency)
        )

        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            val prefs = Preferences(this)
            
            when {
                key in colorKeys -> mActiveOptions?.refreshColorSettings(prefs)
                key in borderKeys -> mActiveOptions?.refreshBorderSettings(prefs)
                key in networkKeys -> {
                    if (DEBUG) Log.d(TAG, "Network setting changed ($key), restarting everything")
                    mHandler?.post { reloadAll() }
                }
                key in captureKeys -> {
                    if (DEBUG) Log.d(TAG, "Capture setting changed ($key), reloading capture")
                    mHandler?.post { reloadCapture() }
                }
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        mPrefsListener = listener
    }

    private fun reloadAll() {
        if (!mForegroundStarted) return
        if (DEBUG) Log.i(TAG, "Reloading all settings and restarting connections")
        
        stopAllCapture()
        
        // Wait a bit for sockets to close
        mHandler?.postDelayed({
            if (prepared()) {
                startBasedOnCurrentSource()
            } else {
                haltStartup()
            }
        }, 500)
    }

    private fun reloadCapture() {
        if (!mForegroundStarted || mHyperionThread == null) return
        if (DEBUG) Log.i(TAG, "Reloading capture session with new settings")
        
        // Update basic capture settings that prepared() also reads
        val prefs = Preferences(this)
        mFrameRate = prefs.getInt(R.string.pref_key_framerate).coerceAtLeast(1)
        try {
            val captureQualityStr = prefs.getString(R.string.pref_key_capture_quality, "128")
                ?.takeIf { it.isNotBlank() } ?: "128"
            mCaptureQuality = Integer.parseInt(captureQualityStr)
        } catch (e: NumberFormatException) {
            mCaptureQuality = 128
        }
        mHorizontalLEDCount = prefs.getInt(R.string.pref_key_x_led)
        mVerticalLEDCount = prefs.getInt(R.string.pref_key_y_led)
        mSendAverageColor = prefs.getBoolean(R.string.pref_key_use_avg_color)

        // Stop current encoders but KEEP network alive
        stopEncodersOnly()
        
        // Restart based on new source
        startBasedOnCurrentSource()
    }

    private fun stopEncodersOnly() {
        mScreenEncoder?.stopRecordingNoDisconnect()
        mScreenEncoder = null
        mScreencapEncoder?.stopRecording()
        mScreencapEncoder = null
        mAdbEncoder?.stopRecording()
        mAdbEncoder = null
        mScreenrecordEncoder?.stopRecording()
        mScreenrecordEncoder = null
        mScrcpyEncoder?.stopRecording()
        mScrcpyEncoder = null
        mAccessibilityEncoder?.stopRecording()
        mAccessibilityEncoder = null
        mCameraEncoder?.stopRecording()
        mCameraEncoder = null
        mMtkThalCaptureEncoder?.stopRecording()
        mMtkThalCaptureEncoder = null
        mEffectsEncoder?.stop()
        mEffectsEncoder = null
        mMusicEncoder?.stop()
        mMusicEncoder = null
        
        releaseResource()
    }

    private fun startBasedOnCurrentSource() {
        val prefs = Preferences(this)
        mCaptureSource = prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"
        
        when (mCaptureSource) {
            "screen" -> {
                val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")
                if (method == "media_projection") {
                    restartEncoderFromSavedProjection()
                } else {
                    startAlternativeRecord(method!!)
                }
            }
            "camera" -> startCameraCapture()
            "effects" -> startEffectsCapture()
            "music" -> {
                val musicMethod = prefs.getString(R.string.pref_key_music_capture_method, "mic") ?: "mic"
                if (musicMethod == "system_audio") {
                    // Try to use existing projection if available
                    if (mProjectionResultCode != null && mProjectionDataExtras != null) {
                        val resultCode = mProjectionResultCode!!
                        val projectionIntent = buildProjectionDataIntent()!!
                        val projection = mMediaProjectionManager?.getMediaProjection(resultCode, projectionIntent)
                        if (projection != null) {
                            sMediaProjection = projection
                            startMusicCapture()
                        } else {
                            notifyActivityNeedsPermission()
                        }
                    } else {
                        notifyActivityNeedsPermission()
                    }
                } else {
                    sMediaProjection = null
                    startMusicCapture()
                }
            }
        }
        notifyActivity()
    }

    private fun notifyActivityNeedsPermission() {
        val intent = Intent(BROADCAST_FILTER)
        intent.putExtra(BROADCAST_TAG, false)
        intent.putExtra(BROADCAST_ERROR, "Permission required for system audio capture")
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        // Activity will see this and can prompt for permission if it's in foreground
    }

    private fun unregisterSettingsPrefsListener() {
        val listener = mPrefsListener ?: return
        try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(listener)
        } catch (_: Exception) {
        }
        mPrefsListener = null
    }

    fun sendScene(sceneId: Int, speed: Int = 100) {
        mHyperionThread?.receiver?.sendScene(sceneId, speed)
    }

    interface HyperionThreadBroadcaster {
        //        void onResponse(String response);
        fun onConnected()
        fun onConnectionError(errorID: Int, error: String?)
        fun onReceiveStatus(isCapturing: Boolean)
    }

    companion object {
        const val BROADCAST_ERROR = "SERVICE_ERROR"
        const val BROADCAST_TAG = "SERVICE_STATUS"
        const val BROADCAST_FILTER = "SERVICE_FILTER"
        const val BROADCAST_TCL_BLOCKED = "TCL_BLOCKED"
        const val BROADCAST_DEVICE_NAME = "DEVICE_NAME"
        private const val DEBUG = false
        private const val TAG = "ScreenGrabberService"

        private const val BASE = "com.bluehyperx.moodgleam.service."
        const val ACTION_START = BASE + "ACTION_START"
        const val ACTION_START_CAMERA = BASE + "ACTION_START_CAMERA"
        const val ACTION_START_EFFECTS = BASE + "ACTION_START_EFFECTS"
        const val ACTION_START_MUSIC = BASE + "ACTION_START_MUSIC"
        const val ACTION_UPDATE_EFFECTS = BASE + "ACTION_UPDATE_EFFECTS"
        const val ACTION_STOP = BASE + "ACTION_STOP"
        const val ACTION_CLEAR = BASE + "ACTION_CLEAR"
        const val ACTION_EXIT = BASE + "ACTION_EXIT"
        const val GET_STATUS = BASE + "ACTION_STATUS"
        const val EXTRA_RESULT_CODE = BASE + "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = BASE + "EXTRA_RESULT_DATA"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_EXIT_INTENT_ID = 2

        private var sMediaProjection: MediaProjection? = null

        /** True while the service instance is alive (onCreate→onDestroy). */
        @Volatile
        @JvmStatic
        var sInstanceRunning: Boolean = false
            internal set
    }
}
