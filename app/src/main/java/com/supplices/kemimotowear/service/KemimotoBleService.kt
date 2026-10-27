package com.supplices.kemimotowear.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.supplices.kemimotowear.R
import com.supplices.kemimotowear.ble.KemimotoBleManager
import com.supplices.kemimotowear.model.HeatingProfile
import com.supplices.kemimotowear.model.PredefinedProfiles
import com.supplices.kemimotowear.model.VestState
import com.supplices.kemimotowear.routine.HeatingProfileManager
import com.supplices.kemimotowear.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hardened Android Foreground Service maintaining BLE communication and profile execution.
 * Fully supports bilingual (English / French) runtime localization.
 */
class KemimotoBleService : Service() {

    private val binder = LocalBinder()
    lateinit var bleManager: KemimotoBleManager
        private set
    lateinit var profileManager: HeatingProfileManager
        private set

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _combinedState = MutableStateFlow(VestState())
    val combinedState: StateFlow<VestState> = _combinedState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): KemimotoBleService = this@KemimotoBleService
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = KemimotoBleManager(applicationContext)
        profileManager = HeatingProfileManager(applicationContext) { neck, back, belly ->
            bleManager.setLevels(neck, back, belly)
        }

        // Synchronize BLE hardware state and routine progression into unified state
        serviceScope.launch {
            bleManager.vestState.collect { bleState ->
                updateNotification(bleState)
                _combinedState.value = bleState.copy(
                    activeProfile = profileManager.activeProfile.value,
                    currentStepLabelRes = profileManager.currentStepLabelRes.value,
                    stepRemainingSeconds = profileManager.stepRemainingSeconds.value
                )
            }
        }

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

        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Dispatches commands originating from Gemini voice actions or Deep Links.
     * Example: kemimoto://action?command=heat&zone=neck&level=3
     */
    fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri != null && uri.scheme == "kemimoto" && uri.host == "action") {
            when (uri.getQueryParameter("command")) {
                "heat" -> {
                    val zone = uri.getQueryParameter("zone") ?: "all"
                    val level = uri.getQueryParameter("level")?.toIntOrNull() ?: 0
                    profileManager.stopProfile()
                    bleManager.setZoneLevel(zone, level)
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
                    bleManager.setStealthMode(enabled)
                }
                "stop" -> {
                    profileManager.stopProfile()
                    bleManager.setLevels(0, 0, 0)
                }
            }
        }
    }

    /**
     * Initializes the persistent foreground notification channel.
     */
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

    /**
     * Updates notification content dynamically as battery or heating state changes.
     */
    private fun updateNotification(state: VestState) {
        val channelId = "openkemi_heat_channel"
        val batteryText = state.batteryDisplay?.let { " • Batt: $it" } ?: ""
        val statusText = if (state.activeProfile != null) {
            val title = getString(state.activeProfile.titleRes)
            val step = state.currentStepLabelRes?.let { getString(it) } ?: ""
            "$title ($step)"
        } else if (state.isAnyZoneHeating) {
            "Col ${state.neckLevel} | Dos ${state.backLevel} | Ventre ${state.bellyLevel}"
        } else {
            getString(R.string.level_off)
        }

        val notification = buildNotification(channelId, "${getString(R.string.app_name)}$batteryText", statusText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(101, notification)
    }

    /**
     * Builds standard Android ongoing notification with pending activity launch intent.
     */
    private fun buildNotification(channelId: String, title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        profileManager.stopProfile()
        bleManager.disconnectAndClean()
        serviceScope.cancel()
        super.onDestroy()
    }
}
