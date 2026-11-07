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
import com.supplices.kemimotowear.model.VestMode
import com.supplices.kemimotowear.model.VestState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Direct BLE Controller implementation communicating directly with the Kemimoto heated garment.
 * Operating Mode: DIRECT (Watch <-> Jacket).
 */
@SuppressLint("MissingPermission")
class DirectJacketController(private val context: Context) : JacketController {

    companion object {
        const val PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE = -70
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS_NAME = "openkemi_direct_prefs"
        private const val KEY_SAVED_MAC = "saved_vest_mac"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _vestState = MutableStateFlow(
        VestState(
            mode = VestMode.DIRECT,
            pairedMacAddress = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SAVED_MAC, null)
        )
    )
    override val vestState: StateFlow<VestState> = _vestState.asStateFlow()

    private var isPairingMode = false

    private val rssiPoller = object : Runnable {
        override fun run() {
            if (_vestState.value.connectionStatus == ConnectionStatus.CONNECTED) {
                bluetoothGatt?.readRemoteRssi()
                mainHandler.postDelayed(this, 1500L)
            }
        }
    }

    override fun startPairingScan() {
        isPairingMode = true
        _vestState.value = _vestState.value.copy(
            isPairingScanning = true,
            connectionStatus = ConnectionStatus.SCANNING,
            securityRejectionResId = null,
            securityRejectionArg = null
        )
        executeScan()
    }

    override fun startScanAndConnect() {
        isPairingMode = false
        _vestState.value = _vestState.value.copy(
            isPairingScanning = false,
            connectionStatus = ConnectionStatus.SCANNING,
            securityRejectionResId = null,
            securityRejectionArg = null
        )
        executeScan()
    }

    private fun executeScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                isPairingScanning = false,
                securityRejectionResId = R.string.alert_bluetooth_disabled,
                securityRejectionArg = null
            )
            return
        }

        val bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                isPairingScanning = false,
                securityRejectionResId = R.string.alert_ble_scanner_unavailable,
                securityRejectionArg = null
            )
            return
        }

        if (_vestState.value.connectionStatus == ConnectionStatus.CONNECTED) return

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
                    isPairingScanning = false,
                    securityRejectionResId = R.string.alert_vest_not_found,
                    securityRejectionArg = null
                )
            }
        }, 15000L)

        try {
            bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (_: Exception) {
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                isPairingScanning = false
            )
        }
    }

    override fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        _vestState.value = _vestState.value.copy(isPairingScanning = false)
    }

    override fun forgetPairedDevice() {
        prefs.edit().remove(KEY_SAVED_MAC).apply()
        disconnectAndClean()
        _vestState.value = _vestState.value.copy(
            pairedMacAddress = null,
            connectionStatus = ConnectionStatus.DISCONNECTED
        )
    }

    override fun isDevicePaired(): Boolean {
        return prefs.getString(KEY_SAVED_MAC, null) != null
    }

    override fun clearGatewayJacketBonding() {
        // Sans effet en mode direct
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val rssi = scanResult.rssi
                val device = scanResult.device
                val savedMac = prefs.getString(KEY_SAVED_MAC, null)

                _vestState.value = _vestState.value.copy(
                    currentRssi = rssi,
                    isWithinProximity = (rssi >= PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE)
                )

                if (isPairingMode) {
                    // Strict physical proximity check (< 1 meter) during pairing
                    val isNearby = rssi >= PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE
                    if (isNearby) {
                        stopScan()
                        prefs.edit().putString(KEY_SAVED_MAC, device.address).apply()
                        _vestState.value = _vestState.value.copy(
                            pairedMacAddress = device.address,
                            isPairingScanning = false,
                            securityRejectionResId = null
                        )
                        connectToDevice(device)
                    } else {
                        _vestState.value = _vestState.value.copy(
                            securityRejectionResId = R.string.alert_proximity_scan,
                            securityRejectionArg = rssi
                        )
                    }
                } else {
                    // Normal reconnection: connect if matching saved MAC or if no MAC is saved and close
                    if (savedMac != null) {
                        if (device.address.equals(savedMac, ignoreCase = true)) {
                            stopScan()
                            connectToDevice(device)
                        }
                    } else {
                        // First-time fallback
                        if (rssi >= PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE) {
                            stopScan()
                            prefs.edit().putString(KEY_SAVED_MAC, device.address).apply()
                            _vestState.value = _vestState.value.copy(
                                pairedMacAddress = device.address,
                                isPairingScanning = false,
                                securityRejectionResId = null
                            )
                            connectToDevice(device)
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                isPairingScanning = false,
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
            triggerHaptic(isWarning = false)
            mainHandler.postDelayed(thermalSafetyRunnable, 20 * 60 * 1000L)
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

    override fun setLevels(neck: Int, back: Int, belly: Int) {
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

    override fun setZoneLevel(zone: String, level: Int) {
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

    override fun toggleStealthMode() {
        val newStealth = !_vestState.value.isStealthMode
        _vestState.value = _vestState.value.copy(isStealthMode = newStealth)
        sendCurrentLevels()
    }

    override fun setStealthMode(enabled: Boolean) {
        _vestState.value = _vestState.value.copy(isStealthMode = enabled)
        sendCurrentLevels()
    }

    override fun setChoppedLowMode(enabled: Boolean) {
        // Direct mode does not support autonomous hardware chopped low; state reflected in state flow
        _vestState.value = _vestState.value.copy(isChoppedLowActive = enabled)
    }

    override fun resetBattery() {
        // Direct vendor hardware does not have a software fuel gauge to reset
    }

    override fun setUserCadence(state: Int) {
        _vestState.value = _vestState.value.copy(userCadenceState = state)
    }

    private fun sendCurrentLevels() {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        val state = _vestState.value

        // In Direct Mode, commands are sent freely without RSSI distance restriction
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
    }

    override fun disconnectAndClean() {
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
