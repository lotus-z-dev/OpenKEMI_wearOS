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
import com.supplices.kemimotowear.model.ConnectionStatus
import com.supplices.kemimotowear.model.PredefinedProfiles
import com.supplices.kemimotowear.model.VestMode
import com.supplices.kemimotowear.model.VestState
import com.supplices.kemimotowear.routine.HeatingProfileManager
import com.supplices.kemimotowear.ui.MainActivity
import com.supplices.kemimotowear.util.SoundAlertHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

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

        private val ALLOWED_ZONES = setOf(
            "all", "neck", "col", "top", "back", "dos", "mid", "belly", "ventre", "bottom"
        )
    }

    private val binder = LocalBinder()

    lateinit var directController: DirectJacketController
        private set
    lateinit var gatewayController: Esp32GatewayController
        private set
    lateinit var activeController: JacketController
        private set

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

        val isKeepAlive = prefs.getBoolean(KEY_KEEPALIVE_ENABLED, true)
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
                    state.connectionStatus == ConnectionStatus.CONNECTED &&
                    !state.isAnyZoneHeating &&
                    !state.isCriticalCutoff) {
                    secondCounter++
                    if (secondCounter >= 30) {
                        secondCounter = 0
                        // Exercise the vest controller while keeping every heating zone off.
                        activeController.keepAliveWithoutHeating()
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
                val heatingActive = state.connectionStatus == ConnectionStatus.CONNECTED &&
                    (state.isAnyZoneHeating || state.isChoppedLowActive)
                if (heatingActive) {
                    continuousHeatSeconds++
                    _combinedState.value = _combinedState.value.copy(continuousHeatingSeconds = continuousHeatSeconds)
                    if (state.isSafety90mEnabled && continuousHeatSeconds >= 5400L) { // 90 consecutive minutes
                        continuousHeatSeconds = 0L
                        SoundAlertHelper.playSafetyBeep(applicationContext)
                        profileManager.stopProfile()
                        if (state.isChoppedLowActive) {
                            activeController.setChoppedLowMode(false)
                        }
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
                if (controllerState.isHardwareOff && !_combinedState.value.isHardwareOff) {
                    // A vest-button shutdown cancels pending profile steps.
                    profileManager.stopProfile()
                }
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
     * Dispatches strictly validated commands originating from local App Shortcuts / Assistant actions.
     * Administrative operations (mode switching, battery/bond resets) are intentionally excluded
     * from external URI intents and are only accessible via direct watch UI interaction.
     */
    fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "kemimoto" || uri.host != "action") return

        when (uri.getQueryParameter("command")?.lowercase(Locale.ROOT)) {
            "heat" -> {
                val zone = uri.getQueryParameter("zone")?.lowercase(Locale.ROOT) ?: return
                val level = uri.getQueryParameter("level")?.toIntOrNull() ?: return
                if (zone !in ALLOWED_ZONES || level !in 0..3) return
                profileManager.stopProfile()
                if (level == 0 && zone == "all" && _combinedState.value.isChoppedLowActive) {
                    activeController.setChoppedLowMode(false)
                }
                activeController.setZoneLevel(zone, level)
            }
            "profile" -> {
                val profileId = uri.getQueryParameter("profileId")?.trim() ?: return
                val profile = PredefinedProfiles.findById(profileId) ?: return
                profileManager.startProfile(profile)
            }
            "stealth" -> {
                val enabledRaw = uri.getQueryParameter("enabled")?.lowercase(Locale.ROOT) ?: return
                val enabled = when (enabledRaw) {
                    "true" -> true
                    "false" -> false
                    else -> return
                }
                activeController.setStealthMode(enabled)
            }
            "stop" -> {
                profileManager.stopProfile()
                if (_combinedState.value.isChoppedLowActive) {
                    activeController.setChoppedLowMode(false)
                }
                activeController.setLevels(0, 0, 0)
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
        val modeLabel = if (state.mode == VestMode.ESP32_GATEWAY) "[ESP32]" else "[Direct]"
        val batteryText = state.batteryDisplay?.let { " • Batt: $it" } ?: ""
        val runtimeText = state.remainingRuntimeMinutes?.let { " • $it min" } ?: ""

        val statusText = when {
            state.activeProfile != null -> {
                val stepLabel = state.currentStepLabelRes?.let { getString(it) } ?: ""
                val min = state.stepRemainingSeconds / 60
                val sec = state.stepRemainingSeconds % 60
                "$modeLabel $stepLabel (${String.format(Locale.ROOT, "%02d:%02d", min, sec)})$batteryText$runtimeText"
            }
            state.isAnyZoneHeating -> {
                val zones = mutableListOf<String>()
                if (state.neckLevel > 0) zones.add("${getString(R.string.zone_neck_short)}:${state.neckLevel}")
                if (state.backLevel > 0) zones.add("${getString(R.string.zone_back_short)}:${state.backLevel}")
                if (state.bellyLevel > 0) zones.add("${getString(R.string.zone_belly_short)}:${state.bellyLevel}")
                "$modeLabel ${getString(R.string.notif_heating_prefix)} ${zones.joinToString(", ")}$batteryText$runtimeText"
            }
            else -> {
                val connStr = when (state.connectionStatus) {
                    ConnectionStatus.CONNECTED -> getString(R.string.status_connected)
                    ConnectionStatus.CONNECTING -> getString(R.string.status_scanning)
                    ConnectionStatus.SCANNING -> getString(R.string.status_scanning)
                    ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
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
        profileManager.destroy()
        serviceScope.cancel()
        directController.disconnectAndClean()
        gatewayController.disconnectAndClean()
        super.onDestroy()
    }
}
