package com.supplices.kemimotowear.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
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

/**
 * Direct BLE Controller implementation communicating directly with the Kemimoto heated garment.
 * Operating Mode: DIRECT (Watch <-> Jacket).
 *
 * Security controls:
 * - Persists paired garment MAC in Android Keystore-backed EncryptedSharedPreferences.
 * - Requires < 1.0 m proximity (RSSI >= -70 dBm) for pairing and energizing heating elements.
 * - Always permits emergency shutoff (0, 0, 0) on an active connection.
 */
@SuppressLint("MissingPermission")
class DirectJacketController(private val context: Context) : JacketController {

    companion object {
        const val PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE = -70
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val LEGACY_PREFS_NAME = "openkemi_direct_prefs"
        private const val SECURE_PREFS_NAME = "openkemi_direct_secure_prefs"
        private const val KEY_SAVED_MAC = "saved_vest_mac"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val prefs: SharedPreferences by lazy {
        val secure = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        }

        // One-time transparent migration from legacy plaintext SharedPreferences
        if (secure !== context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)) {
            try {
                val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                val legacyMac = legacy.getString(KEY_SAVED_MAC, null)
                if (legacyMac != null && secure.getString(KEY_SAVED_MAC, null) == null) {
                    secure.edit().putString(KEY_SAVED_MAC, legacyMac).apply()
                }
                if (legacyMac != null) {
                    legacy.edit().remove(KEY_SAVED_MAC).apply()
                }
            } catch (_: Exception) {}
        }
        secure
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    private val _vestState = MutableStateFlow(
        VestState(
            mode = VestMode.DIRECT,
            pairedMacAddress = try { prefs.getString(KEY_SAVED_MAC, null) } catch (_: Exception) { null }
        )
    )
    override val vestState: StateFlow<VestState> = _vestState.asStateFlow()

    @Volatile private var isPairingMode = false
    private var candidateAddress: String? = null
    @Volatile private var pendingCommand = false

    private val rssiPoller = object : Runnable {
        override fun run() {
            if (_vestState.value.connectionStatus == ConnectionStatus.CONNECTED) {
                bluetoothGatt?.readRemoteRssi()
                mainHandler.postDelayed(this, 1500L)
            }
        }
    }

    override fun startPairingScan() {
        if (_vestState.value.connectionStatus == ConnectionStatus.SCANNING || _vestState.value.connectionStatus == ConnectionStatus.CONNECTING) return
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
        if (_vestState.value.connectionStatus == ConnectionStatus.SCANNING || _vestState.value.connectionStatus == ConnectionStatus.CONNECTING) return
        isPairingMode = false
        _vestState.value = _vestState.value.copy(
            isPairingScanning = false,
            connectionStatus = ConnectionStatus.SCANNING,
            securityRejectionResId = null,
            securityRejectionArg = null
        )
        executeScan()
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
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

        cancelScanTimeout()
        val timeout = Runnable {
            if (_vestState.value.connectionStatus == ConnectionStatus.SCANNING) {
                stopScan()
                _vestState.value = _vestState.value.copy(
                    connectionStatus = ConnectionStatus.DISCONNECTED,
                    isPairingScanning = false,
                    securityRejectionResId = R.string.alert_vest_not_found,
                    securityRejectionArg = null
                )
            }
        }
        scanTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 15000L)

        try {
            bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (_: Exception) {
            cancelScanTimeout()
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                isPairingScanning = false
            )
        }
    }

    override fun stopScan() {
        cancelScanTimeout()
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        _vestState.value = _vestState.value.copy(isPairingScanning = false)
    }

    override fun forgetPairedDevice() {
        try {
            prefs.edit().remove(KEY_SAVED_MAC).apply()
        } catch (_: Exception) {}
        disconnectAndClean()
        _vestState.value = _vestState.value.copy(
            pairedMacAddress = null,
            connectionStatus = ConnectionStatus.DISCONNECTED
        )
    }

    override fun isDevicePaired(): Boolean {
        return try {
            prefs.getString(KEY_SAVED_MAC, null) != null
        } catch (_: Exception) { false }
    }

    override fun clearGatewayJacketBonding() {
        // No-op in direct watch-to-jacket mode
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val rssi = scanResult.rssi
                val device = scanResult.device
                val savedMac = try { prefs.getString(KEY_SAVED_MAC, null) } catch (_: Exception) { null }

                _vestState.value = _vestState.value.copy(
                    currentRssi = rssi,
                    isWithinProximity = (rssi >= PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE)
                )

                if (isPairingMode) {
                    // Strict physical proximity check (< 1 meter) during pairing
                    val isNearby = rssi >= PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE
                    if (isNearby) {
                        stopScan()
                        candidateAddress = device.address
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
                        // First-time fallback within 1 m proximity
                        if (rssi >= PROXIMITY_RSSI_THRESHOLD_1M_FREE_SPACE) {
                            stopScan()
                            candidateAddress = device.address
                            connectToDevice(device)
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            cancelScanTimeout()
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
                val service = gatt.getService(KemimotoProtocol.SERVICE_UUID)
                    ?: return failConnection(R.string.alert_service_missing)

                writeCharacteristic = service.getCharacteristic(KemimotoProtocol.CHAR_WRITE_UUID)
                notifyCharacteristic = service.getCharacteristic(KemimotoProtocol.CHAR_NOTIFY_UUID)
                if (writeCharacteristic == null || notifyCharacteristic == null) {
                    failConnection(R.string.alert_service_missing)
                    return
                }

                notifyCharacteristic?.let { char ->
                    if (!gatt.setCharacteristicNotification(char, true)) {
                        failConnection(R.string.alert_connection_failed)
                        return
                    }
                    val descriptor = char.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
                                failConnection(R.string.alert_connection_failed)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            if (!gatt.writeDescriptor(descriptor)) failConnection(R.string.alert_connection_failed)
                        }
                    } else {
                        failConnection(R.string.alert_service_missing)
                    }
                }
            } else {
                failConnection(R.string.alert_connection_failed)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) return failConnection(R.string.alert_connection_failed)
            candidateAddress?.let { address ->
                try {
                    prefs.edit().putString(KEY_SAVED_MAC, address).apply()
                } catch (_: Exception) {}
                candidateAddress = null
                _vestState.value = _vestState.value.copy(pairedMacAddress = address)
            }
            _vestState.value = _vestState.value.copy(
                connectionStatus = ConnectionStatus.CONNECTED,
                securityRejectionResId = null,
                securityRejectionArg = null
            )
            if (gatt?.readRemoteRssi() != true) failConnection(R.string.alert_connection_failed)
            else mainHandler.postDelayed(rssiPoller, 1500L)
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
                if (withinProximity && pendingCommand) sendCurrentLevels()
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
            bellyLevel = clampedBelly,
            isHardwareOff = false
        )
        checkThermalProtection(clampedNeck, clampedBack, clampedBelly)
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
        checkThermalProtection(newState.neckLevel, newState.backLevel, newState.bellyLevel)
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
        // Direct mode does not support autonomous hardware chopped low; state reflected in state flow
        _vestState.value = _vestState.value.copy(isChoppedLowActive = enabled)
    }

    override fun resetBattery() {
        // Direct vendor hardware does not have a software fuel gauge to reset
    }

    override fun setUserCadence(state: Int) {
        _vestState.value = _vestState.value.copy(userCadenceState = state.coerceIn(0, 3))
    }

    override fun keepAliveWithoutHeating() {
        if (_vestState.value.connectionStatus == ConnectionStatus.CONNECTED &&
            !_vestState.value.isAnyZoneHeating) sendCurrentLevels()
    }

    private fun sendCurrentLevels() {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        val state = _vestState.value
        if (state.connectionStatus != ConnectionStatus.CONNECTED) return

        // Require a measured RSSI within 1 m before energizing any heating zone.
        // Emergency shutoff (0, 0, 0) is always permitted on an active connection.
        val requestsHeat = state.isAnyZoneHeating
        if (requestsHeat && (state.currentRssi == null || !state.isWithinProximity)) {
            _vestState.value = state.copy(securityRejectionResId = R.string.alert_proximity_command)
            return
        }
        val packet = KemimotoProtocol.buildCommandPacket(
            neck = state.neckLevel,
            back = state.backLevel,
            belly = state.bellyLevel,
            isStealthMode = state.isStealthMode
        )

        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = packet
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
        if (accepted) pendingCommand = false
        else _vestState.value = _vestState.value.copy(securityRejectionResId = R.string.alert_connection_failed)
    }

    override fun disconnectAndClean() {
        stopScan()
        candidateAddress = null
        pendingCommand = false
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
            bellyLevel = 0,
            isHardwareOff = false
        )
        if (wasHeating) {
            triggerHaptic(isWarning = true)
        }
    }
}
