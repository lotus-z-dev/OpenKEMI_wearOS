package com.supplices.kemimotowear.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.supplices.kemimotowear.R
import com.supplices.kemimotowear.ble.DirectJacketController
import com.supplices.kemimotowear.ble.Esp32GatewayController
import com.supplices.kemimotowear.ble.JacketController
import com.supplices.kemimotowear.ble.KemimotoBleManager
import com.supplices.kemimotowear.model.HeatingProfile
import com.supplices.kemimotowear.model.PredefinedProfiles
import com.supplices.kemimotowear.model.VestMode
import com.supplices.kemimotowear.model.VestState
import com.supplices.kemimotowear.routine.HeatingProfileManager
import com.supplices.kemimotowear.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hardened Android Foreground Service maintaining BLE communication, controller polymorphism,
 * and automated heating profile execution.
 *
 * Supports runtime switching between DIRECT mode (Watch <-> Jacket) and ESP32_GATEWAY mode.
 */
class KemimotoBleService : Service() {

    companion object {
        private const val PREFS_NAME = "openkemi_service_prefs"
        private const val KEY_SELECTED_MODE = "selected_vest_mode"
        private const val KEY_KEEPALIVE_ENABLED = "keepalive_enabled"
        private const val KEY_SAFETY_90M_ENABLED = "safety_90m_enabled"
    }

    private val binder = LocalBinder()

    lateinit var directController: DirectJacketController
        private set
    lateinit var gatewayController: Esp32GatewayController
        private set
    lateinit var activeController: JacketController
        private set

    // Backward-compatibility alias for legacy callers
    val bleManager: KemimotoBleManager by lazy { KemimotoBleManager(applicationContext) }

    lateinit var profileManager: HeatingProfileManager
        private set

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _combinedState = MutableStateFlow(VestState())
    val combinedState: StateFlow<VestState> = _combinedState.asStateFlow()

    private var stateCollectionJob: Job? = null
    private var keepAliveJob: Job? = null
    private var safetyMonitorJob: Job? = null
    private lateinit var prefs: SharedPreferences

    inner class LocalBinder : Binder() {
        fun getService(): KemimotoBleService = this@KemimotoBleService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        directController = DirectJacketController(applicationContext)
        gatewayController = Esp32GatewayController(applicationContext)

        // Restore previously selected operating mode, defaulting to DIRECT
        val savedModeOrdinal = prefs.getInt(KEY_SELECTED_MODE, VestMode.DIRECT.ordinal)
        val initialMode = VestMode.entries.getOrElse(savedModeOrdinal) { VestMode.DIRECT }

        val isKeepAlive = prefs.getBoolean(KEY_KEEPALIVE_ENABLED, false)
        val isSafety90m = prefs.getBoolean(KEY_SAFETY_90M_ENABLED, true)

        _combinedState.value = _combinedState.value.copy(
            isKeepAliveEnabled = isKeepAlive,
            isSafety90mEnabled = isSafety90m
        )

        activeController = if (initialMode == VestMode.ESP32_GATEWAY) gatewayController else directController

        profileManager = HeatingProfileManager(applicationContext) { neck, back, belly ->
            activeController.setLevels(neck, back, belly)
        }

        bindActiveControllerState()

        serviceScope.launch {
            profileManager.activeProfile.collect { profile ->
                _combinedState.value = _combinedState.value.copy(activeProfile = profile)
            }
        }

        serviceScope.launch {
            profileManager.currentStepLabelRes.collect { labelRes ->
                _combinedState.value = _combinedState.value.copy(currentStepLabelRes = labelRes)
            }
        }

        serviceScope.launch {
            profileManager.stepRemainingSeconds.collect { sec ->
                _combinedState.value = _combinedState.value.copy(stepRemainingSeconds = sec)
            }
        }

        startKeepAliveLoop()
        startSafetyMonitorLoop()
        startForegroundServiceNotification()
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEPALIVE_ENABLED, enabled).apply()
        _combinedState.value = _combinedState.value.copy(isKeepAliveEnabled = enabled)
    }

    fun setSafety90mEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SAFETY_90M_ENABLED, enabled).apply()
        _combinedState.value = _combinedState.value.copy(isSafety90mEnabled = enabled)
    }

    fun dismissSafetyAlert() {
        _combinedState.value = _combinedState.value.copy(isSafetyShutdownTriggered = false)
    }

    private fun startKeepAliveLoop() {
        keepAliveJob?.cancel()
        keepAliveJob = serviceScope.launch {
            var secondCounter = 0
            while (isActive) {
                delay(1000L)
                val state = _combinedState.value
                if (state.isKeepAliveEnabled &&
                    state.connectionStatus == com.supplices.kemimotowear.model.ConnectionStatus.CONNECTED &&
                    !state.isAnyZoneHeating &&
                    !state.isCriticalCutoff) {
                    secondCounter++
                    if (secondCounter >= 22) {
                        secondCounter = 0
                        // Send micro-pulse (600ms on level 1, then back to 0) to maintain power bank circuit
                        activeController.setZoneLevel("neck", 1)
                        delay(600L)
                        activeController.setZoneLevel("neck", 0)
                    }
                } else {
                    secondCounter = 0
                }
            }
        }
    }

    private fun startSafetyMonitorLoop() {
        safetyMonitorJob?.cancel()
        safetyMonitorJob = serviceScope.launch {
            var continuousHeatSeconds = 0L
            while (isActive) {
                delay(1000L)
                val state = _combinedState.value
                if (state.connectionStatus == com.supplices.kemimotowear.model.ConnectionStatus.CONNECTED && state.isAnyZoneHeating) {
                    continuousHeatSeconds++
                    _combinedState.value = _combinedState.value.copy(continuousHeatingSeconds = continuousHeatSeconds)
                    if (state.isSafety90mEnabled && continuousHeatSeconds >= 5400L) { // 90 consecutive minutes
                        continuousHeatSeconds = 0L
                        // Audible alarm beep on watch speaker
                        com.supplices.kemimotowear.util.SoundAlertHelper.playSafetyBeep(applicationContext)
                        // Cut off all heating
                        profileManager.stopProfile()
                        activeController.setLevels(0, 0, 0)
                        _combinedState.value = _combinedState.value.copy(
                            continuousHeatingSeconds = 0L,
                            isSafetyShutdownTriggered = true
                        )
                    }
                } else {
                    continuousHeatSeconds = 0L
                    if (_combinedState.value.continuousHeatingSeconds != 0L) {
                        _combinedState.value = _combinedState.value.copy(continuousHeatingSeconds = 0L)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Dynamically switches the active controller between Direct mode and ESP32 Gateway mode.
     */
    fun setOperatingMode(mode: VestMode) {
        if (_combinedState.value.mode == mode) return

        // Gracefully clean up current controller
        profileManager.stopProfile()
        activeController.disconnectAndClean()

        // Switch to the target controller
        activeController = when (mode) {
            VestMode.DIRECT -> directController
            VestMode.ESP32_GATEWAY -> gatewayController
        }

        prefs.edit().putInt(KEY_SELECTED_MODE, mode.ordinal).apply()
        bindActiveControllerState()

        // Auto-connect with the new controller
        activeController.startScanAndConnect()
    }

    /**
     * Forwards clear jacket MAC command to the active controller (ESP32 Gateway).
     */
    fun clearGatewayJacketBonding() {
        activeController.clearGatewayJacketBonding()
    }

    private fun bindActiveControllerState() {
        stateCollectionJob?.cancel()
        stateCollectionJob = serviceScope.launch {
            activeController.vestState.collect { controllerState ->
                updateNotification(controllerState)
                _combinedState.value = controllerState.copy(
                    activeProfile = profileManager.activeProfile.value,
                    currentStepLabelRes = profileManager.currentStepLabelRes.value,
                    stepRemainingSeconds = profileManager.stepRemainingSeconds.value,
                    isKeepAliveEnabled = _combinedState.value.isKeepAliveEnabled,
                    isSafety90mEnabled = _combinedState.value.isSafety90mEnabled,
                    continuousHeatingSeconds = _combinedState.value.continuousHeatingSeconds,
                    isSafetyShutdownTriggered = _combinedState.value.isSafetyShutdownTriggered
                )
            }
        }
    }

    /**
     * Dispatches commands originating from Gemini voice actions or Deep Links.
     */
    fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri != null && uri.scheme == "kemimoto" && uri.host == "action") {
            when (uri.getQueryParameter("command")) {
                "heat" -> {
                    val zone = uri.getQueryParameter("zone") ?: "all"
                    val level = uri.getQueryParameter("level")?.toIntOrNull() ?: 0
                    profileManager.stopProfile()
                    activeController.setZoneLevel(zone, level)
                }
                "profile" -> {
                    val profileId = uri.getQueryParameter("profileId") ?: ""
                    val profile = PredefinedProfiles.findById(profileId)
                    if (profile != null) {
                        profileManager.startProfile(profile)
                    }
                }
                "stealth" -> {
                    val enabled = uri.getQueryParameter("enabled")?.toBoolean() ?: true
                    activeController.setStealthMode(enabled)
                }
                "stop" -> {
                    profileManager.stopProfile()
                    activeController.setLevels(0, 0, 0)
                }
                "mode" -> {
                    val modeStr = uri.getQueryParameter("target") ?: "direct"
                    val targetMode = if (modeStr.equals("gateway", ignoreCase = true)) {
                        VestMode.ESP32_GATEWAY
                    } else {
                        VestMode.DIRECT
                    }
                    setOperatingMode(targetMode)
                }
                "reset" -> {
                    activeController.resetBattery()
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "openkemi_heat_channel"
        val channel = NotificationChannel(
            channelId,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = buildNotification(channelId, getString(R.string.app_name), getString(R.string.status_disconnected))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(101, notification)
        }
    }

    private fun updateNotification(state: VestState) {
        val channelId = "openkemi_heat_channel"
        val modeLabel = if (state.mode == VestMode.ESP32_GATEWAY) "[ESP32-C6]" else "[Direct]"
        val batteryText = state.batteryDisplay?.let { " • Batt: $it" } ?: ""
        val runtimeText = state.remainingRuntimeMinutes?.let { " • $it min" } ?: ""

        val statusText = when {
            state.activeProfile != null -> {
                val stepLabel = state.currentStepLabelRes?.let { getString(it) } ?: ""
                val min = state.stepRemainingSeconds / 60
                val sec = state.stepRemainingSeconds % 60
                "$modeLabel $stepLabel (${String.format("%02d:%02d", min, sec)})$batteryText$runtimeText"
            }
            state.isAnyZoneHeating -> {
                val zones = mutableListOf<String>()
                if (state.neckLevel > 0) zones.add("Col:${state.neckLevel}")
                if (state.backLevel > 0) zones.add("Dos:${state.backLevel}")
                if (state.bellyLevel > 0) zones.add("Ventre:${state.bellyLevel}")
                "$modeLabel Chauffe: ${zones.joinToString(", ")}$batteryText$runtimeText"
            }
            else -> {
                val connStr = when (state.connectionStatus) {
                    com.supplices.kemimotowear.model.ConnectionStatus.CONNECTED -> getString(R.string.status_connected)
                    com.supplices.kemimotowear.model.ConnectionStatus.CONNECTING -> getString(R.string.status_scanning)
                    com.supplices.kemimotowear.model.ConnectionStatus.SCANNING -> getString(R.string.status_scanning)
                    com.supplices.kemimotowear.model.ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
                }
                "$modeLabel $connStr$batteryText$runtimeText"
            }
        }

        val notification = buildNotification(channelId, getString(R.string.app_name), statusText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(101, notification)
    }

    private fun buildNotification(channelId: String, title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        directController.disconnectAndClean()
        gatewayController.disconnectAndClean()
        super.onDestroy()
    }
}
