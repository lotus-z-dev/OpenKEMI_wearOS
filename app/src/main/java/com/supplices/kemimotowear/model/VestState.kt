package com.supplices.kemimotowear.model

import com.supplices.kemimotowear.R

/**
 * Immutable domain state representing the real-time configuration, telemetry,
 * and security perimeter status of the Kemimoto heated apparel.
 *
 * Supports bilingual localization (French/English) via string resource IDs.
 *
 * @property connectionStatus Current BLE GATT connection lifecycle state
 * @property neckLevel Heating level for neck / collar zone (0=Off, 1=Low, 2=Med, 3=High)
 * @property backLevel Heating level for upper back / shoulder blade zone (0..3)
 * @property bellyLevel Heating level for lower abdominal zone (0..3)
 * @property isStealthMode Hardware LED stealth mode (true = physical garment button light disabled)
 * @property batteryPercent Peripheral battery charge percentage (0-100%) decoded from telemetry
 * @property timerRemainingSeconds Remaining auto-power-off countdown timer reported by hardware
 * @property activeProfile Currently running automated duty-cycle profile, or null if manual
 * @property currentStepLabelRes String resource ID describing the active profile phase
 * @property stepRemainingSeconds Countdown seconds until the next profile phase transition
 * @property currentRssi Measured Received Signal Strength Indication in dBm
 * @property isWithinProximity Physical security gate: true when watch is within calibrated ~45 cm
 * @property securityRejectionResId String resource ID for user diagnostic warnings
 * @property securityRejectionArg Optional format argument (e.g. RSSI dBm value or error code)
 */
data class VestState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val neckLevel: Int = 0,
    val backLevel: Int = 0,
    val bellyLevel: Int = 0,
    val isStealthMode: Boolean = false,
    val batteryPercent: Int? = null,
    val timerRemainingSeconds: Int = 0,
    val activeProfile: HeatingProfile? = null,
    val currentStepLabelRes: Int? = null,
    val stepRemainingSeconds: Int = 0,
    val currentRssi: Int? = null,
    val isWithinProximity: Boolean = false,
    val securityRejectionResId: Int? = null,
    val securityRejectionArg: Int? = null
) {
    /** True if at least one heating zone is actively drawing battery power */
    val isAnyZoneHeating: Boolean
        get() = neckLevel > 0 || backLevel > 0 || bellyLevel > 0

    /** Formats power state as 0..4 hardware bars (e.g. 4/4) or percentage if available */
    val batteryDisplay: String?
        get() = batteryPercent?.let {
            if (it in 0..4) "$it/4" else "$it%"
        }
}

/**
 * Connection states for the BLE peripheral lifecycle.
 */
enum class ConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

/**
 * Target heating zones addressable via Gemini voice commands or manual controls.
 */
enum class HeatingZone {
    ALL,
    NECK,
    BACK,
    BELLY
}
