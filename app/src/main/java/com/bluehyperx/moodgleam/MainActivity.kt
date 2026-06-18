package com.bluehyperx.moodgleam

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.compose.rememberNavController
import com.bluehyperx.moodgleam.common.AccessibilityCaptureService
import com.bluehyperx.moodgleam.common.BootActivity
import com.bluehyperx.moodgleam.common.ScreenGrabberService
import com.bluehyperx.moodgleam.common.util.LocaleHelper
import com.bluehyperx.moodgleam.common.util.PermissionHelper
import com.bluehyperx.moodgleam.common.util.Preferences
import com.bluehyperx.moodgleam.common.util.TclBypass
import com.bluehyperx.moodgleam.common.util.UsbSerialPermissionHelper
import com.bluehyperx.moodgleam.common.util.openAccessibilitySettings
import com.bluehyperx.moodgleam.ui.navigation.AppNavHost
import com.bluehyperx.moodgleam.ui.theme.AppTheme
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private var mRecorderRunning by mutableStateOf(false)
    private var mConnectedDeviceName by mutableStateOf<String?>(null)
    private var showBugReportDialog by mutableStateOf(false)
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mPermissionDeniedCount = 0
    private var mTclWarningShown = false
    private var currentEffect by mutableStateOf("rainbow")
    private var mSessionStartTime: Long? = null
    private var mSessionEverConnected: Boolean = false
    private var mSessionMethod: String? = null
    private var mSessionSource: String? = null
    private var mSessionProtocol: String? = null

    private var usbPermissionReceiverRegistered = false
    private var usbAttachReceiverRegistered = false

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED != intent.action) return

            val prefs = Preferences(this@MainActivity)
            val connectionType =
                prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
            if (!"adalight".equals(connectionType, ignoreCase = true)) return

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    UsbManager.EXTRA_DEVICE,
                    android.hardware.usb.UsbDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
                context = this@MainActivity,
                device = device,
                onReady = { /* permission granted */ },
                onDenied = null,
                showToast = true
            )
        }
    }

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val checked = intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TAG, false)
            val wasRunning = mRecorderRunning
            mRecorderRunning = checked
            
            mConnectedDeviceName = intent.getStringExtra(ScreenGrabberService.BROADCAST_DEVICE_NAME)

            val error = intent.getStringExtra(ScreenGrabberService.BROADCAST_ERROR)
            val tclBlocked =
                intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TCL_BLOCKED, false)

            if (checked) mSessionEverConnected = true

            if (wasRunning && !checked) {
                val reason = when {
                    tclBlocked -> "tcl_blocked"
                    error != null -> "error"
                    else -> "service_stop"
                }
                finishCaptureSession(reason)
            }

            if (tclBlocked && !mTclWarningShown) {
                mTclWarningShown = true
                window.decorView.post {
                    TclBypass.showTclHelpDialog(this@MainActivity) { requestScreenCapture() }
                }
            } else if (error != null && !QuickTileService.isListening) {
                if (error == "Permission required for system audio capture") {
                    // Trigger permission request if in foreground
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        Toast.makeText(baseContext, getString(R.string.error_media_projection_denied), Toast.LENGTH_SHORT).show()
                        requestSystemAudioCapture()
                    }
                } else {
                    val errorMessage = error
                    window.decorView.post {
                        Toast.makeText(baseContext, errorMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun requestSystemAudioCapture() {
        ensureAudioPermissions {
            ensureBluetoothPermissions {
                ensureUsbPermissionForAdalight {
                    try {
                        val captureIntent = mMediaProjectionManager!!.createScreenCaptureIntent()
                        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Audio Capture request failed: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        mMediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        window.decorView.post { checkForUpdates() }

        LocalBroadcastManager.getInstance(this).registerReceiver(
            mMessageReceiver, IntentFilter(ScreenGrabberService.BROADCAST_FILTER)
        )
        checkForInstance()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }

        maybeRequestBatteryOptimizationExemption()

        setContent {
            AppTheme {
                val navController = rememberNavController()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showEffectDialog by remember { mutableStateOf(false) }

                    if (showEffectDialog) {
                        EffectSettingsDialog(
                            onDismiss = { showEffectDialog = false },
                            context = this@MainActivity,
                            onEffectUpdate = { notifyEffectUpdate() }
                        )
                    }

                    if (showBugReportDialog) {
                        BugReportDialog(
                            onDismiss = { showBugReportDialog = false },
                            onReport = {
                                com.bluehyperx.moodgleam.common.util.BugReportHelper.launchBugReport(this@MainActivity)
                                showBugReportDialog = false
                            }
                        )
                    }

                    AppNavHost(
                        navController = navController,
                        isRunning = mRecorderRunning,
                        connectedDeviceName = mConnectedDeviceName,
                        onToggleClick = { toggleScreenCapture() },
                        onEffectsClick = {
                            val prefs = Preferences(this@MainActivity)
                            val captureSource = prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"
                            
                            if (captureSource == "effects") {
                                val effects = listOf("rainbow", "sunset", "ocean", "fireplace", "aurora", "solid", "breathing", "color_wave", "knight_rider", "police")
                                val current = prefs.getString(R.string.pref_key_current_effect, "rainbow") ?: "rainbow"
                                val nextIndex = (effects.indexOf(current) + 1) % effects.size
                                val nextEffect = effects[nextIndex]
                                prefs.putString(R.string.pref_key_current_effect, nextEffect)
                                currentEffect = nextEffect
                                notifyEffectUpdate()
                            } else if (captureSource == "music") {
                                val effects = listOf("vu_meter", "pulse", "frequency_analyzer", "beat_flash", "waterfall", "neon_trails", "spectrogram", "color_wave")
                                val current = prefs.getString(R.string.pref_key_current_music_effect, "vu_meter") ?: "vu_meter"
                                val nextIndex = (effects.indexOf(current) + 1) % effects.size
                                val nextEffect = effects[nextIndex]
                                prefs.putString(R.string.pref_key_current_music_effect, nextEffect)
                                currentEffect = nextEffect
                                notifyEffectUpdate()
                            }
                        },
                        onEffectsLongClick = {
                            showEffectDialog = true
                        },
                        onReportIssueClick = {
                            showBugReportDialog = true
                        },
                        effectMode = currentEffect
                    )
                }
            }
        }
    }

    private fun notifyEffectUpdate() {
        if (mRecorderRunning) {
            val intent = Intent(this, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.ACTION_UPDATE_EFFECTS
            startService(intent)
        }
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        if (PermissionHelper.isIgnoringBatteryOptimizations(this)) return
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val keyLastAttempt = "battery_opt_exemption_last_attempt_ms"
        val lastAttempt = prefs.getLong(keyLastAttempt, 0L)
        val now = System.currentTimeMillis()
        if (now - lastAttempt < 24L * 60L * 60L * 1000L) return
        prefs.edit { putLong(keyLastAttempt, now) }
        PermissionHelper.requestIgnoreBatteryOptimizations(this)
    }

    override fun onResume() {
        super.onResume()
        val prefs = Preferences(this)
        val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        if ("adalight".equals(connectionType, ignoreCase = true)) {
            UsbSerialPermissionHelper.ensurePermissionForSerialDevice(this, null, {}, null, false)
        }

        if (!usbAttachReceiverRegistered) {
            val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            ContextCompat.registerReceiver(this, usbAttachReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            usbAttachReceiverRegistered = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver)
        if (usbAttachReceiverRegistered) {
            try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
            usbAttachReceiverRegistered = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val askedBefore = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_NOTIF_PERMISSION_ASKED, false)
        if (askedBefore && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) return
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit { putBoolean(PREF_NOTIF_PERMISSION_ASKED, true) }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
    }

    private fun beginCaptureSession(source: String, method: String, protocol: String) {
        mSessionStartTime = System.currentTimeMillis()
        mSessionEverConnected = false
        mSessionSource = source
        mSessionMethod = method
        mSessionProtocol = protocol
    }

    private fun finishCaptureSession(endReason: String) {
        val start = mSessionStartTime ?: return
        val durationSeconds = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        mSessionStartTime = null
        mSessionEverConnected = false
    }

    private fun toggleScreenCapture() {
        if (!mRecorderRunning) {
            val prefs = Preferences(this)
            prefs.putBoolean(R.string.pref_key_lighting_was_active, true)
            val captureSource = prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"

            if (captureSource == "camera") {
                requestCameraCapture()
            } else if (captureSource == "effects") {
                ensureBluetoothPermissions {
                    ensureUsbPermissionForAdalight {
                        BootActivity.startEffectsRecorder(this)
                        mRecorderRunning = true
                        beginCaptureSession("effects", "effects", prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion")
                    }
                }
            } else if (captureSource == "music") {
                val musicMethod = prefs.getString(R.string.pref_key_music_capture_method, "mic") ?: "mic"
                ensureAudioPermissions {
                    ensureBluetoothPermissions {
                        ensureUsbPermissionForAdalight {
                            if (musicMethod == "system_audio") {
                                // Request MediaProjection directly for system audio, bypassing Screen Capture method checks
                                try {
                                    val captureIntent = mMediaProjectionManager!!.createScreenCaptureIntent()
                                    startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
                                } catch (e: Exception) {
                                    Toast.makeText(this, "Audio Capture request failed: " + e.message, Toast.LENGTH_LONG).show()
                                }
                            } else {
                                BootActivity.startMusicRecorder(this, 0, Intent()) // 0/empty for Mic
                                mRecorderRunning = true
                                beginCaptureSession("music", "mic", prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion")
                            }
                        }
                    }
                }
            } else {
                ensureBluetoothPermissions {
                    ensureUsbPermissionForAdalight { requestScreenCapture() }
                }
            }
        } else {
            Preferences(this).putBoolean(R.string.pref_key_lighting_was_active, false)
            stopScreenRecorder()
            mRecorderRunning = false
            finishCaptureSession("user_stop")
        }
    }

    private fun ensureBluetoothPermissions(onReady: () -> Unit) {
        if (Preferences(this).getString(R.string.pref_key_connection_type, "hyperion") != "bluetooth") {
            onReady()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
                return
            }
        }
        onReady()
    }

    private fun ensureAudioPermissions(onReady: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
        } else {
            onReady()
        }
    }

    private fun requestScreenCapture() {
        val prefs = Preferences(this)
        val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")
        if (method == "accessibility" && AccessibilityCaptureService.getInstance() == null) {
            Toast.makeText(this, getString(R.string.accessibility_enable_prompt), Toast.LENGTH_LONG).show()
            openAccessibilitySettings(this)
            return
        }
        if (method != "media_projection") {
            BootActivity.startAlternativeRecorder(this)
            mRecorderRunning = true
            beginCaptureSession("screen", method ?: "unknown", prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion")
            return
        }
        TclBypass.tryShellBypass(this)
        PermissionHelper.tryGrantProjectMediaViaShell(this)
        if (mPermissionDeniedCount == 0 && !PermissionHelper.canDrawOverlays(this)) {
            PermissionHelper.requestOverlayPermission(this, REQUEST_OVERLAY_PERMISSION)
            return
        }
        try {
            val captureIntent = mMediaProjectionManager!!.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Toast.makeText(this, "Capture request failed: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestCameraCapture() {
        val prefs = Preferences(this)
        val cameraInputSource =
            prefs.getString(R.string.pref_key_camera_input_source, "internal") ?: "internal"
        if (cameraInputSource == "rtsp") {
            startCameraGrabber()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else {
            startCameraGrabber()
        }
    }

    private fun startCameraGrabber() {
        val protocol = Preferences(this).getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        val intent = Intent(this, ScreenGrabberService::class.java).apply { action = ScreenGrabberService.ACTION_START_CAMERA }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        mRecorderRunning = true
        beginCaptureSession("camera", "camera", protocol)
    }

    private fun ensureUsbPermissionForAdalight(onReady: () -> Unit) {
        if (Preferences(this).getString(R.string.pref_key_connection_type, "hyperion") != "adalight") {
            onReady(); return
        }
        UsbSerialPermissionHelper.ensurePermissionForSerialDevice(this, null, onReady, null, true, true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            val prefs = Preferences(this)
            val captureSource = prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"
            
            if (captureSource == "music") {
                BootActivity.startMusicRecorder(this, resultCode, data)
                mRecorderRunning = true
                beginCaptureSession("music", "system_audio", prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion")
            } else {
                startScreenRecorder(resultCode, data)
                mRecorderRunning = true
                beginCaptureSession("screen", "media_projection", prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            when (requestCode) {
                REQUEST_CAMERA_PERMISSION -> startCameraGrabber()
                REQUEST_AUDIO_PERMISSION -> toggleScreenCapture()
                REQUEST_BLUETOOTH_PERMISSIONS -> toggleScreenCapture()
            }
        }
    }

    fun startScreenRecorder(resultCode: Int, data: Intent) = BootActivity.startScreenRecorder(this, resultCode, data)
    fun stopScreenRecorder() {
        if (mRecorderRunning) startService(Intent(this, ScreenGrabberService::class.java).apply { action = ScreenGrabberService.ACTION_EXIT })
    }
    private fun checkForInstance() {
        if (ScreenGrabberService.sInstanceRunning) startService(Intent(this, ScreenGrabberService::class.java).apply { action = ScreenGrabberService.GET_STATUS })
    }
    private fun checkForUpdates() { }

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1
        private const val REQUEST_NOTIFICATION_PERMISSION = 2
        private const val REQUEST_OVERLAY_PERMISSION = 3
        private const val REQUEST_UPDATE_CODE = 4
        private const val REQUEST_CAMERA_PERMISSION = 5
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 6
        private const val REQUEST_AUDIO_PERMISSION = 7
        private const val TAG = "MainActivity"
        private const val PREF_NOTIF_PERMISSION_ASKED = "notif_permission_asked"
    }
}

@Composable
fun MainScreen(
    isRunning: Boolean,
    onToggleClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEffectsClick: () -> Unit,
    onEffectsLongClick: () -> Unit = {},
    effectMode: String,
    captureSource: String = "screen",
    connectedDeviceName: String? = null,
    onHelpClick: () -> Unit = {},
    onReportIssueClick: () -> Unit = {},
) {
    AppTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            if (captureSource == "camera") {
                com.bluehyperx.moodgleam.ui.camera.CameraPreviewBackground(isCapturing = isRunning)
            }

            if (isRunning && captureSource == "screen") {
                val infiniteTransition = rememberInfiniteTransition(label = "effects")
                when (effectMode) {
                    "rainbow" -> {
                        val angle by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "rotation")
                        Spacer(modifier = Modifier.fillMaxSize().drawBehind {
                            val diagonal = sqrt(size.width * size.width + size.height * size.height)
                            rotate(angle) {
                                drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red)), radius = diagonal / 2)
                            }
                        })
                    }
                    "solid" -> {
                        val baseColor = Preferences(androidx.compose.ui.platform.LocalContext.current).getInt(R.string.pref_key_effect_color, Color.White.toArgb())
                        Spacer(modifier = Modifier.fillMaxSize().background(Color(baseColor)))
                    }
                    "breathing" -> {
                        val alpha by infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse), label = "breathing")
                        Spacer(modifier = Modifier.fillMaxSize().background(Color.Cyan.copy(alpha = alpha)))
                    }
                    "knight_rider" -> {
                        val offset by infiniteTransition.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "knight_rider")
                        Spacer(modifier = Modifier.fillMaxSize().drawBehind {
                            val w = size.width
                            val h = size.height
                            val barWidth = w * 0.12f
                            val x = (w + barWidth) * offset - barWidth
                            drawRect(Brush.verticalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta)), topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(barWidth, h))
                        })
                    }
                }
            }

            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (isRunning) {
                    val modeLabel = when(captureSource) {
                        "screen" -> "Screen Sync Active"
                        "camera" -> "Camera Mode Active"
                        "effects" -> "Dynamic Effects Active"
                        "music" -> "Music Sync Active"
                        else -> "Ambient Light Active"
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape).padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(text = modeLabel, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }

                var effectsFocused by remember { mutableStateOf(false) }
                var powerFocused by remember { mutableStateOf(false) }
                var settingsFocused by remember { mutableStateOf(false) }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (captureSource != "screen" && captureSource != "camera") {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), CircleShape).border(if (effectsFocused) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp).background(MaterialTheme.colorScheme.background, CircleShape).clip(CircleShape)) {
                            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(72.dp)
                                    .onFocusChanged { effectsFocused = it.isFocused }
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = androidx.compose.foundation.LocalIndication.current,
                                        onClick = onEffectsClick,
                                        onLongClick = onEffectsLongClick
                                    )
                            ) {
                                Icon(imageVector = Icons.Default.Palette, contentDescription = "Effects", modifier = Modifier.size(40.dp), tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                            }
                        }
                    }

                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp).background(if (isRunning) Brush.sweepGradient(listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red)) else Brush.linearGradient(listOf(Color.Gray, Color.Gray)), CircleShape).border(if (powerFocused) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp).background(MaterialTheme.colorScheme.background, CircleShape)) {
                        IconButton(onClick = onToggleClick, modifier = Modifier.size(112.dp).onFocusChanged { powerFocused = it.isFocused }) {
                            Icon(imageVector = Icons.Default.PowerSettingsNew, contentDescription = "Toggle Power", modifier = Modifier.size(64.dp).alpha(if (isRunning) 1f else 0.25f), tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                        }
                    }

                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), CircleShape).border(if (settingsFocused) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp).background(MaterialTheme.colorScheme.background, CircleShape)) {
                        IconButton(onClick = onSettingsClick, modifier = Modifier.size(72.dp).onFocusChanged { settingsFocused = it.isFocused }) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(40.dp), tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                val context = androidx.compose.ui.platform.LocalContext.current
                val prefs = remember { Preferences(context) }
                val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
                val connectionLabel = when(connectionType) {
                    "hyperion" -> "Hyperion"
                    "wled" -> "WLED"
                    "adalight" -> "Adalight (USB)"
                    "bluetooth" -> "Adalight (Bluetooth)"
                    else -> connectionType.replaceFirstChar { it.uppercase() }
                }
                
                val deviceDetail = if (isRunning && connectedDeviceName != null) connectedDeviceName else {
                    when(connectionType) {
                        "hyperion", "wled" -> prefs.getString(R.string.pref_key_host, "") ?: ""
                        "bluetooth" -> {
                            val mac = prefs.getString(R.string.pref_key_bluetooth_device, "") ?: ""
                            if (mac.isEmpty()) "Auto-detect" else {
                                try {
                                    val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                                    btAdapter?.getRemoteDevice(mac)?.name ?: mac
                                } catch (e: Exception) { mac }
                            }
                        }
                        else -> ""
                    }
                }

                val isBtConnecting = isRunning && connectionType == "bluetooth" && connectedDeviceName == null
                val dotColor = if (isRunning) {
                    if (isBtConnecting) Color.Yellow else Color.Green
                } else {
                    Color.Gray
                }
                val statusText = if (isRunning) {
                    if (isBtConnecting) "Connecting to $connectionLabel" else "Connected to $connectionLabel"
                } else {
                    "Disconnected"
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = statusText, style = MaterialTheme.typography.bodyLarge, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (deviceDetail != null && deviceDetail.isNotEmpty()) {
                        Text(text = deviceDetail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedButton(onClick = onHelpClick, modifier = Modifier.fillMaxWidth()) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Help, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.help))
                    }
                    OutlinedButton(onClick = onReportIssueClick, modifier = Modifier.fillMaxWidth()) {
                        Icon(imageVector = Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.report_issue))
                    }
                }
            }
        }
    }
}

@Composable
fun EffectSettingsDialog(onDismiss: () -> Unit, context: Context, onEffectUpdate: () -> Unit = {}) {
    val prefs = remember { Preferences(context) }
    val captureSource = prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"
    val isMusic = captureSource == "music"
    val effectKey = if (isMusic) R.string.pref_key_current_music_effect else R.string.pref_key_current_effect
    
    val effects = if (isMusic) {
        listOf("vu_meter", "pulse", "frequency_analyzer", "beat_flash", "waterfall", "neon_trails", "spectrogram", "color_wave")
    } else {
        listOf("rainbow", "sunset", "ocean", "fireplace", "aurora", "solid", "breathing", "color_wave", "knight_rider", "police")
    }
    val effectNames = if (isMusic) {
        listOf("VU Meter", "Center Pulse", "Frequency Analyzer", "Beat Flash", "RGB Waterfall", "Neon Trails", "Spectrogram", "Color Wave")
    } else {
        listOf("Rainbow Swirl", "Sunset Glow", "Ocean Wave", "Fireplace Flicker", "Aurora Borealis", "Solid Color", "Breathing", "Color Wave", "Knight Rider", "Police")
    }
    
    var currentEffect by remember { mutableStateOf(prefs.getString(effectKey, if (isMusic) "vu_meter" else "rainbow") ?: "") }
    
    // Use per-effect speed key for dynamic effects and per-effect sensitivity key for music
    val dynamicSpeedKey = if (isMusic) {
        "pref_key_music_sensitivity_$currentEffect"
    } else {
        "pref_key_effect_speed_$currentEffect"
    }

    var speed by remember(currentEffect) { 
        mutableStateOf(prefs.getString(dynamicSpeedKey, "50") ?: "50")
    }
    
    // Per-effect color logic
    val dynamicColor1Key = if (isMusic) {
        context.getString(R.string.pref_key_effect_color)
    } else {
        "pref_key_effect_color1_$currentEffect"
    }
    val dynamicColor2Key = "pref_key_effect_color2_$currentEffect"
    
    val supportsDualColor = !isMusic && (currentEffect == "color_wave" || currentEffect == "police")
    val hasCustomColorSupport = !isMusic && (currentEffect == "solid" || currentEffect == "breathing" || currentEffect == "color_wave" || currentEffect == "knight_rider" || currentEffect == "police")
    
    var color1 by remember(currentEffect) { 
        mutableStateOf(prefs.getInt(dynamicColor1Key, prefs.getInt(R.string.pref_key_effect_color, Color.Cyan.toArgb())))
    }
    var color2 by remember(currentEffect) { 
        val defaultColor2 = if (currentEffect == "police") Color.Red.toArgb() else Color.Magenta.toArgb()
        mutableStateOf(prefs.getInt(dynamicColor2Key, defaultColor2))
    }
    
    val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion")
    val isWiz = connectionType == "wiz"
    val wizScenesEnabled = prefs.getBoolean(R.string.pref_key_wiz_scenes_enabled, true)
    
    val wizScenes = listOf(
        1 to "Ocean", 2 to "Romance", 3 to "Sunset", 4 to "Party", 5 to "Fireplace", 
        6 to "Cozy", 7 to "Forest", 8 to "Pastel Colors", 9 to "Wake up", 10 to "Bedtime", 
        11 to "Warm White", 12 to "Daylight", 13 to "Cool White", 14 to "Night light", 
        15 to "Focus", 16 to "Relax", 17 to "True colors", 18 to "TV time", 19 to "Plantgrowth", 
        20 to "Spring", 21 to "Summer", 22 to "Autumn", 23 to "Deepdive", 24 to "Jungle", 
        25 to "Mojito", 26 to "Club", 27 to "Christmas", 28 to "Halloween", 29 to "Candlelight", 
        30 to "Golden white", 31 to "Pulse", 32 to "Steampunk"
    )

    val presetColors = listOf(
        Color.Red,
        Color.Green,
        Color.Blue,
        Color.Cyan,
        Color.Magenta,
        Color.Yellow,
        Color(0xFFFFBF00), // Amber Gold
        Color(0xFF2C3E50), // Slate Charcoal
        Color(0xFFA2FFB3), // Neo-Mint
        Color(0xFFE6006F), // Electric Magenta
        Color(0xFF26E6CD), // Cyberpunk Teal
        Color(0xFF7A9488), // Eucalyptus Green
        Color(0xFFD45D3D), // Terracotta Warmth
        Color(0xFFE2B8A2), // Dusty Rose
        Color(0xFF412042), // Twilight Indigo
        Color(0xFFECE3C5), // Champagne Gold
        Color(0xFFBCE2FE), // Glacier Ice Blue
        Color(0xFF4C1D34)  // Plum Velvet
    )

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isMusic) "Music Visualizations" else "Dynamic Effects") }, text = {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("Select Style:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp)) }
            items(effects.size) { index ->
                val effect = effects[index]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { 
                    currentEffect = effect
                    prefs.putString(effectKey, effect)
                    if (isWiz) prefs.remove("pref_key_wiz_scene")
                    onEffectUpdate() 
                }) {
                    androidx.compose.material3.RadioButton(selected = currentEffect == effect, onClick = { 
                        currentEffect = effect
                        prefs.putString(effectKey, effect)
                        if (isWiz) prefs.remove("pref_key_wiz_scene")
                        onEffectUpdate() 
                    }, modifier = Modifier.size(38.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(effectNames[index], style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                val speedFloat = speed.toFloatOrNull() ?: 50f
                Spacer(modifier = Modifier.height(12.dp))
                if (isMusic || (currentEffect != "solid")) {
                    Text(if (isMusic) "Sync Sensitivity: ${speedFloat.toInt()}" else "Effect Pace (${effectNames[effects.indexOf(currentEffect)]}): ${speedFloat.toInt()}", fontWeight = FontWeight.Bold)
                    androidx.compose.material3.Slider(
                        value = speedFloat, 
                        onValueChange = { 
                            speed = it.toInt().toString()
                            prefs.putString(dynamicSpeedKey, speed)
                            onEffectUpdate() 
                        }, 
                        valueRange = 10f..200f
                    )
                }
            }

            if (isWiz && !isMusic && wizScenesEnabled) {
                item {
                    Text("WiZ Scenes:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        wizScenes.forEach { (id, name) ->
                            OutlinedButton(
                                onClick = { 
                                    prefs.putInt("pref_key_wiz_scene", id)
                                    onEffectUpdate()
                                },
                                modifier = Modifier.height(32.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text(name, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (hasCustomColorSupport) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(if (supportsDualColor) "Color 1:" else "Custom Color:", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        if (currentEffect == "color_wave" || currentEffect == "police") {
                            TextButton(onClick = { 
                                prefs.remove(dynamicColor1Key)
                                prefs.remove(dynamicColor2Key)
                                color1 = prefs.getInt(R.string.pref_key_effect_color, Color.Cyan.toArgb())
                                color2 = if (currentEffect == "police") Color.Red.toArgb() else Color.Magenta.toArgb()
                                onEffectUpdate()
                            }) {
                                Text("Reset to Default")
                            }
                        }
                    }
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)
                    ) {
                        presetColors.forEach { preset ->
                            Box(modifier = Modifier.size(36.dp).background(preset, CircleShape).border(if (color1 == preset.toArgb()) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { 
                                color1 = preset.toArgb()
                                prefs.putInt(dynamicColor1Key, preset.toArgb())
                                onEffectUpdate() 
                            })
                        }
                    }
                }
                
                if (supportsDualColor) {
                    item {
                        Text("Color 2:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)
                        ) {
                            presetColors.forEach { preset ->
                                Box(modifier = Modifier.size(36.dp).background(preset, CircleShape).border(if (color2 == preset.toArgb()) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { 
                                    color2 = preset.toArgb()
                                    prefs.putInt(dynamicColor2Key, preset.toArgb())
                                    onEffectUpdate() 
                                })
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
fun HelpDialog(
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help & Documentation") },
        text = { Text("For detailed instructions on setting up your lighting hardware (Hyperion, WLED, WiZ, or Adalight) and using the various capture modes, please visit our project page on GitHub.") },
        confirmButton = {
            TextButton(onClick = onOpenGitHub) { Text("Visit GitHub") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_close)) }
        }
    )
}

fun openGitHubIssues(context: Context) {
    val url = context.getString(R.string.github_issues_url)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to open GitHub issues", e)
    }
}

@Composable
fun BugReportDialog(
    onDismiss: () -> Unit,
    onReport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_bug_title)) },
        text = { Text(stringResource(R.string.report_bug_message)) },
        confirmButton = {
            TextButton(onClick = onReport) { Text(stringResource(R.string.report_bug_open_github)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.guidedstep_cancel)) }
        }
    )
}

@Composable
fun UrlDialog(
    url: String,
    onDismiss: () -> Unit,
    onOpenLink: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.url_dialog_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.url_dialog_message), textAlign = TextAlign.Center)
                SelectionContainer {
                    Text(text = url, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {
            if (onOpenLink != null) {
                TextButton(onClick = onOpenLink) { Text(stringResource(R.string.url_dialog_open_link)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_close)) }
        }
    )
}
