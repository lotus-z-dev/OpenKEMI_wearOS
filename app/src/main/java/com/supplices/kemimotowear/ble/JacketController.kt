package com.supplices.kemimotowear.ble

import com.supplices.kemimotowear.model.VestState
import kotlinx.coroutines.flow.StateFlow

/**
 * Polymorphic abstraction defining operations for controlling the Kemimoto heated apparel.
 * Enables runtime switching between Direct BLE Mode and ESP32 Hardware Gateway Mode.
 */
interface JacketController {

    /**
     * Unified state flow reflecting real-time telemetry, zone levels, and security posture.
     */
    val vestState: StateFlow<VestState>

    /**
     * Initiates BLE discovery and connects to the target hardware device.
     */
    fun startScanAndConnect()

    /**
     * Halts any active BLE scanning routines.
     */
    fun stopScan()

    /**
     * Commands heating levels across all three independent thermal zones simultaneously.
     *
     * @param neck Neck / collar zone level (0=Off, 1=Low, 2=Mid, 3=High)
     * @param back Upper back / shoulder blade zone level (0..3)
     * @param belly Abdominal / lower pocket zone level (0..3)
     */
    fun setLevels(neck: Int, back: Int, belly: Int)

    /**
     * Commands heating level for an individual thermal zone by name.
     *
     * @param zone Zone identifier ("neck", "back", "belly", etc.)
     * @param level Heating power level (0..3)
     */
    fun setZoneLevel(zone: String, level: Int)

    /**
     * Toggles physical garment button LED stealth mode.
     */
    fun toggleStealthMode()

    /**
     * Explicitly sets garment button LED stealth mode.
     */
    fun setStealthMode(enabled: Boolean)

    /**
     * Enables or disables autonomous Chopped Low Mode (5 min ON / 5 min OFF).
     */
    fun setChoppedLowMode(enabled: Boolean)

    /**
     * Dispatches a battery reset command to recalibrate fuel gauge to 100%.
     */
    fun resetBattery()

    /**
     * Dispatches user movement/cadence state to optimize thermal management.
     *
     * @param state 0=Stationary, 1=Walking, 2=Running, 3=In-Vehicle
     */
    fun setUserCadence(state: Int)

    /**
     * Disconnects active GATT session, cancels timers, and releases Bluetooth resources.
     */
    fun disconnectAndClean()

    /**
     * Initiates pairing scan strictly requiring physical proximity (< 1 meter) and saves MAC.
     */
    fun startPairingScan()

    /**
     * Forgets the currently saved peripheral MAC address and resets pairing state.
     */
    fun forgetPairedDevice()

    /**
     * Sends a command to clear the bonded jacket MAC on the ESP32 gateway.
     */
    fun clearGatewayJacketBonding()

    /**
     * Returns true if a device MAC address is currently paired and saved in storage.
     */
    fun isDevicePaired(): Boolean
}
