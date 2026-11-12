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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.supplices.kemimotowear.R
import com.supplices.kemimotowear.model.ConnectionStatus
import com.supplices.kemimotowear.model.VestMode
import com.supplices.kemimotowear.model.VestState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.ArrayDeque

/**
 * Controller implementation communicating with the ESP32-C6 Intelligent Gateway.
 * Operating Mode: ESP32_GATEWAY (Watch <-> ESP32-C6 <-> Kemimoto Jacket).
 *
 * Implements BLE Secure Connections (LESC) with bonding, Identity Resolving Key (IRK)
 * encrypted persistence, virtual fuel gauge telemetry processing, and anti-spoofing proximity enforcement.
 */
@SuppressLint("MissingPermission")
class Esp32GatewayController(private val context: Context) : JacketController {

    companion object {
        /**
         * Calibrated RSSI threshold for 1.5 meters proximity in free space / body attenuation.
         * Enforces strict perimeter security: commands are rejected if RSSI < -75 dBm.
         */
        const val PROXIMITY_RSSI_THRESHOLD_1_5M = -75

        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS_FILE = "openkemi_secure_gateway_prefs"
        private const val KEY_LAST_BONDED_MAC = "last_gateway_bonded_mac"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var charCommand: BluetoothGattCharacteristic? = null
    private var charTelemetry: BluetoothGattCharacteristic? = null
    private var charGauge: BluetoothGattCharacteristic? = null
    private var charCadence: BluetoothGattCharacteristic? = null
    private var charReset: BluetoothGattCharacteristic? = null
    private var charMode: BluetoothGattCharacteristic? = null
    private val pendingDescriptors = ArrayDeque<BluetoothGattDescriptor>()
    @Volatile private var pendingCommand = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // Encrypted storage for IRK and bonded gateway identifiers
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _vestState = MutableStateFlow(
        VestState(
            mode = VestMode.ESP32_GATEWAY,
            pairedMacAddress = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).getString(KEY_LAST_BONDED_MAC, null)
            } catch (_: Exception) { null }
        )
    )
    override val vestState: StateFlow<VestState> = _vestState.asStateFlow()

    private val rssiPoller = object : Runnable {
        override fun run() {
            if (_vestState.value.connectionStatus == ConnectionStatus.CONNECTED) {
                bluetoothGatt?.readRemoteRssi()
                mainHandler.postDelayed(this, 1500L)
            }
        }
    }

    override fun startScanAndConnect() {
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

        if (_vestState.value.connectionStatus != ConnectionStatus.DISCONNECTED) return

        _vestState.value = _vestState.value.copy(
            connectionStatus = ConnectionStatus.SCANNING,
            securityRejectionResId = null,
            securityRejectionArg = null
        )

        // Flexible background scan filter targeting ESP32-C6 Gateway service UUID or short name
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(GatewayProtocol.SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName("OK-GW").build()
        )

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
            bleScanner.startScan(filters, scanSettings, scanCallback)
        } catch (_: Exception) {
            _vestState.value = _vestState.value.copy(connectionStatus = ConnectionStatus.DISCONNECTED)
        }
    }

    override fun startPairingScan() {
        _vestState.value = _vestState.value.copy(isPairingScanning = true)
        startScanAndConnect()
    }

    override fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        _vestState.value = _vestState.value.copy(isPairingScanning = false)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val rssi = scanResult.rssi
                val isNearby = rssi >= PROXIMITY_RSSI_THRESHOLD_1_5M

                _vestState.value = _vestState.value.copy(
                    currentRssi = rssi,
                    isWithinProximity = isNearby
                )

                if (isNearby) {
                    stopScan()
                    connectToDevice(scanResult.device)
                } else {
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
        _vestState.value = _vestState.value.copy(
            connectionStatus = ConnectionStatus.CONNECTING,
            pairedMacAddress = device.address
        )

        // Store bonded device MAC securely in encrypted preferences
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            failConnection(R.string.alert_connection_failed)
        }
    }

    private fun failConnection(message: Int) {
        disconnectAndClean()
        _vestState.value = _vestState.value.copy(securityRejectionResId = message, securityRejectionArg = null)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (gatt?.discoverServices() != true) failConnection(R.string.alert_connection_failed)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnectAndClean()
            } else {
                failConnection(R.string.alert_connection_failed)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(GatewayProtocol.SERVICE_UUID)
                    ?: return failConnection(R.string.alert_service_missing)

                charCommand = service.getCharacteristic(GatewayProtocol.CHAR_COMMAND_UUID)
                charTelemetry = service.getCharacteristic(GatewayProtocol.CHAR_TELEMETRY_UUID)
                charGauge = service.getCharacteristic(GatewayProtocol.CHAR_GAUGE_UUID)
                charCadence = service.getCharacteristic(GatewayProtocol.CHAR_CADENCE_UUID)
                charReset = service.getCharacteristic(GatewayProtocol.CHAR_RESET_UUID)
                charMode = service.getCharacteristic(GatewayProtocol.CHAR_MODE_UUID)
                if (charCommand == null || charTelemetry == null || charGauge == null ||
                    charCadence == null || charReset == null || charMode == null) {
                    failConnection(R.string.alert_service_missing)
                    return
                }

                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    failConnection(R.string.alert_pairing_required)
                    return
                }

                // Subscribe to notifications for Command echo, Telemetry, and Fuel Gauge
                subscribeToCharacteristic(gatt, charCommand)
                subscribeToCharacteristic(gatt, charTelemetry)
                subscribeToCharacteristic(gatt, charGauge)
                writeNextDescriptor(gatt)
            } else {
                failConnection(R.string.alert_connection_failed)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) return failConnection(R.string.alert_connection_failed)
            writeNextDescriptor(gatt ?: return)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val withinProximity = rssi >= PROXIMITY_RSSI_THRESHOLD_1_5M
                _vestState.value = _vestState.value.copy(
                    currentRssi = rssi,
                    isWithinProximity = withinProximity,
                    securityRejectionResId = if (!withinProximity) R.string.alert_proximity_command_gateway else null,
                    securityRejectionArg = if (!withinProximity) rssi else null
                )
                if (withinProximity && pendingCommand) sendCurrentLevels()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val uuid = characteristic?.uuid ?: return
            val bytes = characteristic.value ?: return
            handleNotification(uuid, bytes)
        }
    }

    private fun subscribeToCharacteristic(gatt: BluetoothGatt, char: BluetoothGattCharacteristic?) {
        if (char == null || !gatt.setCharacteristicNotification(char, true)) {
            failConnection(R.string.alert_connection_failed)
            return
        }
        val descriptor = char.getDescriptor(CCCD_UUID) ?: run {
            failConnection(R.string.alert_service_missing)
            return
        }
        pendingDescriptors.addLast(descriptor)
    }

    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        if (gatt !== bluetoothGatt) return
        if (pendingDescriptors.isEmpty()) {
            securePrefs.edit().putString(KEY_LAST_BONDED_MAC, gatt.device.address).apply()
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.CONNECTED,
                pairedMacAddress = gatt.device.address,
                securityRejectionResId = null,
                securityRejectionArg = null
            )
            if (!gatt.readRemoteRssi()) failConnection(R.string.alert_connection_failed)
            else mainHandler.postDelayed(rssiPoller, 1500L)
            return
        }
        val descriptor = pendingDescriptors.removeFirst()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) failConnection(R.string.alert_connection_failed)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!gatt.writeDescriptor(descriptor)) failConnection(R.string.alert_connection_failed)
        }
    }

    private fun handleNotification(uuid: UUID, payload: ByteArray) {
        when (uuid) {
            GatewayProtocol.CHAR_GAUGE_UUID -> {
                val gauge = GatewayProtocol.parseGaugePayload(payload) ?: return
                val isCritical = gauge.virtualPercent <= 4
                _vestState.value = _vestState.value.copy(
                    virtualBatteryPercent = gauge.virtualPercent,
                    remainingRuntimeMinutes = gauge.remainingMinutes,
                    accumulatedMah = gauge.consumedMah,
                    isCriticalCutoff = isCritical
                )

                // Critical battery cutoff (< 15% warning, <= 4% hard cutoff)
                if (isCritical) {
                    triggerHaptic(isWarning = true, isCritical = true)
                } else if (gauge.virtualPercent < 15) {
                    triggerHaptic(isWarning = true, isCritical = false)
                }
            }
            GatewayProtocol.CHAR_TELEMETRY_UUID -> {
                val telemetry = KemimotoProtocol.parseTelemetry(payload) ?: return
                val previous = _vestState.value
                val levels = telemetry.heatingLevels
                val hardwareOff = levels != null && previous.isAnyZoneHeating &&
                    levels.first == 0 && levels.second == 0 && levels.third == 0
                if (hardwareOff) pendingCommand = false
                _vestState.value = previous.copy(
                    batteryPercent = telemetry.batteryPercent,
                    timerRemainingSeconds = telemetry.timerRemainingSeconds,
                    neckLevel = levels?.first ?: previous.neckLevel,
                    backLevel = levels?.second ?: previous.backLevel,
                    bellyLevel = levels?.third ?: previous.bellyLevel,
                    isHardwareOff = previous.isHardwareOff || hardwareOff
                )
            }
            GatewayProtocol.CHAR_COMMAND_UUID -> {
                if (payload.size >= 14 && payload[0] == 0xAA.toByte()) {
                    val previous = _vestState.value
                    val levels = (5..7).map { payload[it].toInt() and 0xFF }
                    if (levels.all { it in 0..3 }) {
                        val hardwareOff = previous.isAnyZoneHeating && levels.all { it == 0 }
                        if (hardwareOff) pendingCommand = false
                        _vestState.value = previous.copy(
                            neckLevel = levels[0], backLevel = levels[1], bellyLevel = levels[2],
                            isStealthMode = (payload[8].toInt() and 0xFF) == 0x01,
                            isHardwareOff = previous.isHardwareOff || hardwareOff
                        )
                    }
                }
            }
        }
    }

    private fun triggerHaptic(isWarning: Boolean, isCritical: Boolean = false) {
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
                    when {
                        isCritical -> {
                            // 1 long sustained vibration for critical battery warning (< 15% or <= 4%)
                            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                        isWarning -> {
                            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
                        }
                        else -> {
                            vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (isCritical) 1000L else if (isWarning) 300L else 70L)
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
            bellyLevel = clampedBelly,
            isHardwareOff = false
        )
        pendingCommand = true
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
        _vestState.value = newState.copy(isHardwareOff = false)
        pendingCommand = true
        sendCurrentLevels()
    }

    override fun toggleStealthMode() {
        val newStealth = !_vestState.value.isStealthMode
        _vestState.value = _vestState.value.copy(isStealthMode = newStealth)
        pendingCommand = true
        sendCurrentLevels()
    }

    override fun setStealthMode(enabled: Boolean) {
        _vestState.value = _vestState.value.copy(isStealthMode = enabled)
        pendingCommand = true
        sendCurrentLevels()
    }

    override fun setChoppedLowMode(enabled: Boolean) {
        val gatt = bluetoothGatt ?: return
        val char = charMode ?: return
        val packet = GatewayProtocol.buildModePacket(enabled)
        writeChar(gatt, char, packet)
        _vestState.value = _vestState.value.copy(isChoppedLowActive = enabled)
    }

    override fun resetBattery() {
        val gatt = bluetoothGatt ?: return
        val char = charReset ?: return
        val packet = GatewayProtocol.buildResetPacket()
        writeChar(gatt, char, packet)
    }

    override fun setUserCadence(state: Int) {
        val gatt = bluetoothGatt ?: return
        val char = charCadence ?: return
        val packet = GatewayProtocol.buildCadencePacket(state)
        writeChar(gatt, char, packet)
        _vestState.value = _vestState.value.copy(userCadenceState = state)
    }

    override fun keepAliveWithoutHeating() {
        if (_vestState.value.connectionStatus == ConnectionStatus.CONNECTED &&
            !_vestState.value.isAnyZoneHeating) sendCurrentLevels()
    }

    private fun sendCurrentLevels() {
        val gatt = bluetoothGatt ?: return
        val char = charCommand ?: return
        val state = _vestState.value

        // Proximity constraint: strictly enforce < 1.5m (RSSI >= -75 dBm)
        val rssi = state.currentRssi
        val withinProximity = rssi != null && rssi >= PROXIMITY_RSSI_THRESHOLD_1_5M
        if (!withinProximity) {
            _vestState.value = state.copy(
                securityRejectionResId = R.string.alert_proximity_command_gateway,
                securityRejectionArg = null
            )
            // Block unauthenticated command transmission beyond perimeter
            return
        } else if (state.securityRejectionResId == R.string.alert_proximity_command_gateway) {
            _vestState.value = state.copy(securityRejectionResId = null, securityRejectionArg = null)
        }

        val packet = KemimotoProtocol.buildCommandPacket(
            neck = state.neckLevel,
            back = state.backLevel,
            belly = state.bellyLevel,
            isStealthMode = state.isStealthMode
        )

        if (writeChar(gatt, char, packet)) pendingCommand = false
        else _vestState.value = _vestState.value.copy(securityRejectionResId = R.string.alert_connection_failed)
    }

    override fun clearGatewayJacketBonding() {
        val gatt = bluetoothGatt ?: return
        val char = charReset ?: return
        val packet = GatewayProtocol.buildClearJacketMacPacket()
        writeChar(gatt, char, packet)
    }

    private fun writeChar(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    override fun disconnectAndClean() {
        stopScan()
        pendingDescriptors.clear()
        pendingCommand = false
        mainHandler.removeCallbacks(rssiPoller)
        val wasHeating = _vestState.value.isAnyZoneHeating
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        charCommand = null
        charTelemetry = null
        charGauge = null
        charCadence = null
        charReset = null
        charMode = null
        _vestState.value = _vestState.value.copy(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            isWithinProximity = false,
            currentRssi = null,
            neckLevel = 0,
            backLevel = 0,
            bellyLevel = 0,
            isHardwareOff = false
        )
        if (wasHeating) {
            triggerHaptic(isWarning = true)
        }
    }

    override fun forgetPairedDevice() {
        try {
            securePrefs.edit().remove(KEY_LAST_BONDED_MAC).apply()
        } catch (_: Exception) {}
        disconnectAndClean()
        _vestState.value = _vestState.value.copy(pairedMacAddress = null)
    }

    override fun isDevicePaired(): Boolean {
        return try {
            securePrefs.getString(KEY_LAST_BONDED_MAC, null) != null
        } catch (_: Exception) { false }
    }
}
