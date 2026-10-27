package com.supplices.kemimotowear.routine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.supplices.kemimotowear.model.HeatingProfile
import com.supplices.kemimotowear.model.ProfileStep
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Autonomous runtime engine for automated intermittent heating profiles.
 * Supports bilingual localized phase notifications.
 *
 * @param context Application context used to acquire system Vibrator service
 * @param onApplyHeatLevels Callback invoked whenever a new heating level tuple (neck, back, belly) must be applied
 */
class HeatingProfileManager(
    private val context: Context,
    private val onApplyHeatLevels: (neck: Int, back: Int, belly: Int) -> Unit
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var executionJob: Job? = null

    // Reactive StateFlows observed by the Wear OS Compose UI
    private val _activeProfile = MutableStateFlow<HeatingProfile?>(null)
    val activeProfile: StateFlow<HeatingProfile?> = _activeProfile.asStateFlow()

    private val _currentStepLabelRes = MutableStateFlow<Int?>(null)
    val currentStepLabelRes: StateFlow<Int?> = _currentStepLabelRes.asStateFlow()

    private val _stepRemainingSeconds = MutableStateFlow(0)
    val stepRemainingSeconds: StateFlow<Int> = _stepRemainingSeconds.asStateFlow()

    /**
     * Starts execution of a designated heating profile.
     *
     * @param profile HeatingProfile instance containing steps and cyclic settings
     */
    fun startProfile(profile: HeatingProfile) {
        stopProfile()

        _activeProfile.value = profile
        executionJob = coroutineScope.launch {
            do {
                for (step in profile.steps) {
                    executeStep(step)
                }
            } while (profile.isCyclic && isActive)

            // Auto-stop once non-cyclic profile finishes all steps
            stopProfile()
        }
    }

    /**
     * Immediately stops the running profile and resets all phase countdowns.
     */
    fun stopProfile() {
        executionJob?.cancel()
        executionJob = null
        _activeProfile.value = null
        _currentStepLabelRes.value = null
        _stepRemainingSeconds.value = 0
    }

    /**
     * Executes an individual phase step: triggers haptics, dispatches BLE commands,
     * and decrements countdown every second.
     */
    private suspend fun executeStep(step: ProfileStep) {
        _currentStepLabelRes.value = step.stepLabelRes

        // Distinct haptic pulse based on heating intensity
        val isHigh = step.neckLevel >= 3 || step.backLevel >= 3 || step.bellyLevel >= 3
        triggerHaptic(isHigh)

        // Dispatch new heating values to the BLE manager
        onApplyHeatLevels(step.neckLevel, step.backLevel, step.bellyLevel)

        var totalSeconds = step.durationMinutes * 60
        while (totalSeconds > 0 && coroutineScope.isActive) {
            _stepRemainingSeconds.value = totalSeconds
            delay(1000L)
            totalSeconds--
        }
    }

    /**
     * Produces tactile haptic pulses on Wear OS (Samsung Galaxy Watch).
     */
    private fun triggerHaptic(strong: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = if (strong) {
                    longArrayOf(0, 250, 100, 250) // Double pulse for high heat
                } else {
                    longArrayOf(0, 150)           // Single pulse for eco/cool down
                }
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (strong) 300L else 150L)
            }
        } catch (_: Exception) {
            // Haptic failure should never crash the profile routine
        }
    }
}
