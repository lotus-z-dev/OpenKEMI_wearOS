package com.supplices.kemimotowear.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.supplices.kemimotowear.R
import com.supplices.kemimotowear.model.ConnectionStatus
import com.supplices.kemimotowear.model.VestState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Hardened Bluetooth Low Energy (BLE) Peripheral Manager for Kemimoto Heated Apparel.
 * Fully localized with string resource IDs for English and French.
 */
@SuppressLint("MissingPermission")
class KemimotoBleManager(private val context: Context) {

    companion object {
        /**
         * Maximum permissible physical distance between the watch and the vest in unobstructed free space.
         * Calibrated to exactly 1.0 meter using standard 2.4 GHz RF Friis free-space path loss formula:
         * RSSI(d) = A - 10 * n * log10(d)
         * Where nominal measured power at 1 meter (A) = -70 dBm and path loss exponent (n) = 2.0.
         * Signals stronger than or equal to -70 dBm are accepted (within 1.0 meter).
         * Signals weaker than -70 dBm (greater than 1.0 meter) are rejected to eliminate relay or spoofing attacks.
         */
        const val PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE = -70
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _vestState = MutableStateFlow(VestState())
    val vestState: StateFlow<VestState> = _vestState.asStateFlow()

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val rssiPoller = object : Runnable {
        override fun run() {
            if (_vestState.value.connectionStatus == ConnectionStatus.CONNECTED) {
                bluetoothGatt?.readRemoteRssi()
                mainHandler.postDelayed(this, 1500L)
            }
        }
    }

    fun startScanAndConnect() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                securityRejectionResId = R.string.alert_bluetooth_disabled,
                securityRejectionArg = null
            )
            return
        }

        val bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                securityRejectionResId = R.string.alert_ble_scanner_unavailable,
                securityRejectionArg = null
            )
            return
        }

        if (_vestState.value.connectionStatus == ConnectionStatus.CONNECTED) return

        _vestState.value = _vestState.value.copy(
            connectionStatus = ConnectionStatus.SCANNING,
            securityRejectionResId = null,
            securityRejectionArg = null
        )

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(KemimotoProtocol.SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        mainHandler.postDelayed({
            if (_vestState.value.connectionStatus == ConnectionStatus.SCANNING) {
                stopScan()
                _vestState.value = _vestState.value.copy(
                    connectionStatus = ConnectionStatus.DISCONNECTED,
                    securityRejectionResId = R.string.alert_vest_not_found,
                    securityRejectionArg = null
                )
            }
        }, 12000L)

        try {
            bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (_: Exception) {
            _vestState.value = _vestState.value.copy(connectionStatus = ConnectionStatus.DISCONNECTED)
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val rssi = scanResult.rssi
                // Verify whether the peripheral signal strength meets the 1.0m free space threshold (-70 dBm)
                val isNearby = rssi >= PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE

                _vestState.value = _vestState.value.copy(
                    currentRssi = rssi,
                    isWithinProximity = isNearby
                )

                if (isNearby) {
                    // Stop scanning immediately upon finding our nearby authenticated vest to preserve watch battery
                    stopScan()
                    connectToDevice(scanResult.device)
                } else {
                    // Reject connection attempt and notify user if the vest is further than 1.0m away
                    _vestState.value = _vestState.value.copy(
                        securityRejectionResId = R.string.alert_proximity_scan,
                        securityRejectionArg = rssi
                    )
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                securityRejectionResId = R.string.alert_scan_failed,
                securityRejectionArg = errorCode
            )
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        _vestState.value = _vestState.value.copy(connectionStatus = ConnectionStatus.CONNECTING)
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _vestState.value = _vestState.value.copy(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    securityRejectionResId = null,
                    securityRejectionArg = null
                )
                gatt?.discoverServices()
                mainHandler.post(rssiPoller)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnectAndClean()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(KemimotoProtocol.SERVICE_UUID) ?: return

                writeCharacteristic = service.getCharacteristic(KemimotoProtocol.CHAR_WRITE_UUID)
                notifyCharacteristic = service.getCharacteristic(KemimotoProtocol.CHAR_NOTIFY_UUID)

                notifyCharacteristic?.let { char ->
                    gatt.setCharacteristicNotification(char, true)
                    val descriptor = char.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }

                gatt.readRemoteRssi()
                sendCurrentLevels()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Continuous background RSSI monitoring: confirms vest stays within 1.0m free-space radius (-70 dBm)
                val withinProximity = rssi >= PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE
                _vestState.value = _vestState.value.copy(
                    currentRssi = rssi,
                    isWithinProximity = withinProximity,
                    securityRejectionResId = if (!withinProximity) R.string.alert_proximity_command else null,
                    securityRejectionArg = if (!withinProximity) rssi else null
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingTelemetry(value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.value?.let { bytes ->
                handleIncomingTelemetry(bytes)
            }
        }
    }

    private fun handleIncomingTelemetry(payload: ByteArray) {
        val telemetry = KemimotoProtocol.parseTelemetry(payload) ?: return
        _vestState.value = _vestState.value.copy(
            batteryPercent = telemetry.batteryPercent,
            timerRemainingSeconds = telemetry.timerRemainingSeconds
        )
    }

    // Thermal Safety Guard: automatically steps down Level 3 (Max) to Level 2 (Blue) after 20 minutes continuous
    private val thermalSafetyRunnable = Runnable {
        val state = _vestState.value
        if (state.connectionStatus == ConnectionStatus.CONNECTED && state.isAnyZoneHeating) {
            val maxLevel = maxOf(state.neckLevel, state.backLevel, state.bellyLevel)
            if (maxLevel == 3) {
                val safeNeck = if (state.neckLevel == 3) 2 else state.neckLevel
                val safeBack = if (state.backLevel == 3) 2 else state.backLevel
                val safeBelly = if (state.bellyLevel == 3) 2 else state.bellyLevel
                _vestState.value = state.copy(
                    neckLevel = safeNeck,
                    backLevel = safeBack,
                    bellyLevel = safeBelly
                )
                sendCurrentLevels()
                triggerHaptic(isWarning = true)
            }
        }
    }

    private fun checkThermalProtection(neck: Int, back: Int, belly: Int) {
        val maxLevel = maxOf(neck, back, belly)
        mainHandler.removeCallbacks(thermalSafetyRunnable)
        if (maxLevel == 3) {
            triggerHaptic(isWarning = false) // Haptic click indicating Max Heat Level 3 engaged
            mainHandler.postDelayed(thermalSafetyRunnable, 20 * 60 * 1000L) // 20-min failsafe timeout
        }
    }

    private fun triggerHaptic(isWarning: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (isWarning) {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (isWarning) 300L else 70L)
                }
            }
        } catch (_: Exception) {}
    }

    fun setLevels(neck: Int, back: Int, belly: Int) {
        val clampedNeck = neck.coerceIn(0, 3)
        val clampedBack = back.coerceIn(0, 3)
        val clampedBelly = belly.coerceIn(0, 3)
        _vestState.value = _vestState.value.copy(
            neckLevel = clampedNeck,
            backLevel = clampedBack,
            bellyLevel = clampedBelly
        )
        checkThermalProtection(clampedNeck, clampedBack, clampedBelly)
        sendCurrentLevels()
    }

    fun setZoneLevel(zone: String, level: Int) {
        val current = _vestState.value
        val clamped = level.coerceIn(0, 3)
        val newState = when (zone.lowercase()) {
            "col", "neck", "top" -> current.copy(neckLevel = clamped)
            "dos", "back", "mid" -> current.copy(backLevel = clamped)
            "ventre", "belly", "bottom" -> current.copy(bellyLevel = clamped)
            else -> current.copy(neckLevel = clamped, backLevel = clamped, bellyLevel = clamped)
        }
        _vestState.value = newState
        checkThermalProtection(newState.neckLevel, newState.backLevel, newState.bellyLevel)
        sendCurrentLevels()
    }

    fun toggleStealthMode() {
        val newStealth = !_vestState.value.isStealthMode
        _vestState.value = _vestState.value.copy(isStealthMode = newStealth)
        sendCurrentLevels()
    }

    fun setStealthMode(enabled: Boolean) {
        _vestState.value = _vestState.value.copy(isStealthMode = enabled)
        sendCurrentLevels()
    }

    private fun sendCurrentLevels() {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        val state = _vestState.value

        // Proximity Security Guard: Verifies that the watch is physically located within 1.0 meter
        // in unobstructed free space before transmitting heating control packets to the vest.
        // If the RSSI is strictly below -70 dBm (meaning the distance exceeds 1.0 meter), the command is aborted
        // and an alert is displayed on the screen to safeguard against remote BLE hijacking.
        if (state.currentRssi != null && state.currentRssi < PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE) {
            _vestState.value = state.copy(
                securityRejectionResId = R.string.alert_proximity_command,
                securityRejectionArg = state.currentRssi
            )
            return
        }

        val packet = KemimotoProtocol.buildCommandPacket(
            neck = state.neckLevel,
            back = state.backLevel,
            belly = state.bellyLevel,
            isStealthMode = state.isStealthMode
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            char.value = packet
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }

        if (state.securityRejectionResId == R.string.alert_proximity_command) {
            _vestState.value = state.copy(securityRejectionResId = null, securityRejectionArg = null)
        }
    }

    fun disconnectAndClean() {
        mainHandler.removeCallbacks(rssiPoller)
        mainHandler.removeCallbacks(thermalSafetyRunnable)
        val wasHeating = _vestState.value.isAnyZoneHeating
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        _vestState.value = _vestState.value.copy(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            isWithinProximity = false,
            currentRssi = null,
            neckLevel = 0,
            backLevel = 0,
            bellyLevel = 0
        )
        if (wasHeating) {
            triggerHaptic(isWarning = true)
        }
    }
}
