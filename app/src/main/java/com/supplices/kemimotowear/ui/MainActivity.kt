package com.supplices.kemimotowear.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.supplices.kemimotowear.R
import com.supplices.kemimotowear.ble.GatewayProtocol
import com.supplices.kemimotowear.model.ConnectionStatus
import com.supplices.kemimotowear.model.HeatingProfile
import com.supplices.kemimotowear.model.PredefinedProfiles
import com.supplices.kemimotowear.model.VestMode
import com.supplices.kemimotowear.model.VestState
import com.supplices.kemimotowear.service.KemimotoBleService
import com.supplices.kemimotowear.ui.components.RadarScanner
import com.supplices.kemimotowear.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Main Activity for OpenKEMI on Wear OS (Samsung Galaxy Watch 7).
 *
 * Implements a 3-panel swipeable HorizontalPager:
 * - Panel 0: Settings & Pairing (<1m) & About section (GitHub & Unofficial notice)
 * - Panel 1: Home Screen (Battery, Connection status, Quick 3-color presets, Directional hints)
 * - Panel 2: Detailed Zones (Col, Dos, Ventre), Stealth mode, and Automated Profiles
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    val bleServiceState = mutableStateOf<KemimotoBleService?>(null)
    private var isBound = false

    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var lastStepCount = 0
    private var lastStepTimestamp = 0L
    private var pendingBluetoothAction: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (grants.values.all { it } && bluetoothReady()) action?.invoke()
        else Toast.makeText(this, R.string.alert_bluetooth_disabled, Toast.LENGTH_SHORT).show()
    }

    private fun bluetoothReady(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return false
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter?.isEnabled != true) {
            ensureBluetoothEnabled()
            return false
        }
        return true
    }

    private fun withBluetooth(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                pendingBluetoothAction = action
                permissionLauncher.launch(missing.toTypedArray())
                return
            }
        }
        if (bluetoothReady()) action()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as KemimotoBleService.LocalBinder
            val svc = binder.getService()
            bleServiceState.value = svc
            isBound = true

            intent?.let { svc.handleIntent(it) }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bleServiceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerOnDeviceShortcuts()
        setupSensors()

        val serviceIntent = Intent(this, KemimotoBleService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            KemimotoWearTheme {
                val currentService = bleServiceState.value
                val vestState by (currentService?.combinedState?.collectAsState()
                    ?: remember { mutableStateOf(VestState()) })

                // Monitor battery cutoff and trigger sustained haptic warning
                LaunchedEffect(vestState.isCriticalCutoff) {
                    if (vestState.isCriticalCutoff) {
                        triggerCodedHaptic(HapticAction.CRITICAL_BATTERY)
                    }
                }

                KemimotoWearScreen(
                    state = vestState,
                    onModeToggle = { targetMode ->
                        bleServiceState.value?.setOperatingMode(targetMode)
                    },
                    onKeepAliveToggle = {
                        val current = bleServiceState.value?.combinedState?.value?.isKeepAliveEnabled ?: false
                        bleServiceState.value?.setKeepAliveEnabled(!current)
                    },
                    onSafety90mToggle = {
                        val current = bleServiceState.value?.combinedState?.value?.isSafety90mEnabled ?: true
                        bleServiceState.value?.setSafety90mEnabled(!current)
                    },
                    onDismissSafetyAlert = {
                        bleServiceState.value?.dismissSafetyAlert()
                    },
                    onStartPairingScan = {
                        withBluetooth { bleServiceState.value?.activeController?.startPairingScan() }
                    },
                    onStopScan = {
                        bleServiceState.value?.activeController?.stopScan()
                    },
                    onForgetPairedDevice = {
                        bleServiceState.value?.activeController?.forgetPairedDevice()
                        Toast.makeText(this@MainActivity, getString(R.string.btn_forget_mac), Toast.LENGTH_SHORT).show()
                    },
                    onClearGatewayJacketBonding = {
                        bleServiceState.value?.clearGatewayJacketBonding()
                        Toast.makeText(this@MainActivity, getString(R.string.esp32_toast_jacket_cleared), Toast.LENGTH_SHORT).show()
                    },
                    onConnectScan = {
                        withBluetooth { bleServiceState.value?.activeController?.startScanAndConnect() }
                    },
                    onDisconnect = {
                        bleServiceState.value?.activeController?.disconnectAndClean()
                    },
                    onCycleZone = { zone, currentLevel ->
                        val next = (currentLevel + 1) % 4
                        bleServiceState.value?.activeController?.setZoneLevel(zone, next)
                        when (next) {
                            0 -> triggerCodedHaptic(HapticAction.OFF)
                            2 -> triggerCodedHaptic(HapticAction.MEDIUM)
                            3 -> triggerCodedHaptic(HapticAction.HIGH)
                            else -> {}
                        }
                    },
                    onSetPreset = { targetLevel ->
                        val svc = bleServiceState.value
                        val currentMax = maxOf(vestState.neckLevel, vestState.backLevel, vestState.bellyLevel)
                        val newLevel = if (currentMax == targetLevel && vestState.isAnyZoneHeating) 0 else targetLevel
                        svc?.profileManager?.stopProfile()
                        svc?.activeController?.setLevels(newLevel, newLevel, newLevel)
                        when (newLevel) {
                            0 -> triggerCodedHaptic(HapticAction.OFF)
                            2 -> triggerCodedHaptic(HapticAction.MEDIUM)
                            3 -> triggerCodedHaptic(HapticAction.HIGH)
                            else -> {}
                        }
                    },
                    onTurnOffAll = {
                        val svc = bleServiceState.value
                        svc?.profileManager?.stopProfile()
                        svc?.activeController?.setLevels(0, 0, 0)
                        triggerCodedHaptic(HapticAction.OFF)
                    },
                    onToggleStealth = {
                        bleServiceState.value?.activeController?.toggleStealthMode()
                    },
                    onToggleChoppedLow = {
                        val newStatus = !vestState.isChoppedLowActive
                        bleServiceState.value?.activeController?.setChoppedLowMode(newStatus)
                    },
                    onResetBattery = {
                        bleServiceState.value?.activeController?.resetBattery()
                        Toast.makeText(this@MainActivity, getString(R.string.battery_reset_success), Toast.LENGTH_SHORT).show()
                    },
                    onStartProfile = { profile ->
                        bleServiceState.value?.profileManager?.startProfile(profile)
                    },
                    onStopProfile = {
                        bleServiceState.value?.profileManager?.stopProfile()
                    }
                )
            }
        }
    }

    private fun ensureBluetoothEnabled() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            try {
                val btSettingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(btSettingsIntent)
            } catch (_: Exception) {}
        }
    }

    private fun setupSensors() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            stepSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            val now = System.currentTimeMillis()
            lastStepCount++
            if (now - lastStepTimestamp > 5000) {
                lastStepTimestamp = now
                bleServiceState.value?.activeController?.setUserCadence(GatewayProtocol.CADENCE_WALKING.toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    enum class HapticAction {
        OFF,
        MEDIUM,
        HIGH,
        CRITICAL_BATTERY
    }

    private fun triggerCodedHaptic(action: HapticAction) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    when (action) {
                        HapticAction.OFF -> vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                        HapticAction.MEDIUM -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1))
                        HapticAction.HIGH -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80, 80, 80), -1))
                        HapticAction.CRITICAL_BATTERY -> vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    when (action) {
                        HapticAction.OFF -> vibrator.vibrate(80L)
                        HapticAction.MEDIUM -> vibrator.vibrate(longArrayOf(0, 80, 80, 80), -1)
                        HapticAction.HIGH -> vibrator.vibrate(longArrayOf(0, 80, 80, 80, 80, 80), -1)
                        HapticAction.CRITICAL_BATTERY -> vibrator.vibrate(1000L)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun registerOnDeviceShortcuts() {
        try {
            val synonyms = resources.getStringArray(R.array.vest_synonyms).toList()

            val stopIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("kemimoto://action?command=heat&zone=all&level=0")
            }
            val stopShortcut = ShortcutInfoCompat.Builder(this, "stop_vest")
                .setShortLabel(getString(R.string.shortcut_stop_label))
                .setLongLabel(getString(R.string.shortcut_stop_long_label))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(stopIntent)
                .addCapabilityBinding("actions.intent.STOP_DEVICE_OPERATION", "device.name", synonyms)
                .build()

            val greenIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("kemimoto://action?command=heat&zone=all&level=1")
            }
            val greenShortcut = ShortcutInfoCompat.Builder(this, "green_preset")
                .setShortLabel(getString(R.string.shortcut_green_label))
                .setLongLabel(getString(R.string.shortcut_green_long_label))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(greenIntent)
                .build()

            val blueIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("kemimoto://action?command=heat&zone=all&level=2")
            }
            val blueShortcut = ShortcutInfoCompat.Builder(this, "blue_preset")
                .setShortLabel(getString(R.string.shortcut_blue_label))
                .setLongLabel(getString(R.string.shortcut_blue_long_label))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(blueIntent)
                .build()

            val maxIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("kemimoto://action?command=heat&zone=all&level=3")
            }
            val maxShortcut = ShortcutInfoCompat.Builder(this, "max_heating")
                .setShortLabel(getString(R.string.shortcut_max_label))
                .setLongLabel(getString(R.string.shortcut_max_long_label))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(maxIntent)
                .build()

            val stealthIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("kemimoto://action?command=stealth&enabled=true")
            }
            val stealthShortcut = ShortcutInfoCompat.Builder(this, "toggle_stealth")
                .setShortLabel(getString(R.string.shortcut_stealth_label))
                .setLongLabel(getString(R.string.shortcut_stealth_long_label))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(stealthIntent)
                .build()

            val openIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("kemimoto://action?command=open")
            }
            val openShortcut = ShortcutInfoCompat.Builder(this, "open_vest")
                .setShortLabel(getString(R.string.app_name))
                .setLongLabel(getString(R.string.shortcut_open_long_label))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(openIntent)
                .addCapabilityBinding("actions.intent.OPEN_APP_FEATURE", "feature", synonyms)
                .build()

            ShortcutManagerCompat.setDynamicShortcuts(
                this,
                listOf(stopShortcut, greenShortcut, blueShortcut, maxShortcut, stealthShortcut, openShortcut)
            )
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bleServiceState.value?.handleIntent(intent)
    }

    override fun onDestroy() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
        if (isBound) {
            unbindService(connection)
            isBound = false
            bleServiceState.value = null
        }
        super.onDestroy()
    }
}

/**
 * 3-Panel HorizontalPager Wear OS layout optimized for circular displays.
 */
@Composable
fun KemimotoWearScreen(
    state: VestState,
    onModeToggle: (VestMode) -> Unit,
    onKeepAliveToggle: (Boolean) -> Unit,
    onSafety90mToggle: (Boolean) -> Unit,
    onDismissSafetyAlert: () -> Unit,
    onStartPairingScan: () -> Unit,
    onStopScan: () -> Unit,
    onForgetPairedDevice: () -> Unit,
    onClearGatewayJacketBonding: () -> Unit,
    onConnectScan: () -> Unit,
    onDisconnect: () -> Unit,
    onCycleZone: (zone: String, currentLevel: Int) -> Unit,
    onSetPreset: (level: Int) -> Unit,
    onTurnOffAll: () -> Unit,
    onToggleStealth: () -> Unit,
    onToggleChoppedLow: () -> Unit,
    onResetBattery: () -> Unit,
    onStartProfile: (HeatingProfile) -> Unit,
    onStopProfile: () -> Unit
) {
    // Page 0: Settings & Pairing, Page 1: Home (Default), Page 2: Functions & Profiles
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    val settingsListState = rememberScalingLazyListState()
    val homeListState = rememberScalingLazyListState()
    val functionsListState = rememberScalingLazyListState()

    // Systématiquement réinitialiser la vue au sommet lors d'un changement de page
    LaunchedEffect(pagerState.currentPage) {
        when (pagerState.currentPage) {
            0 -> settingsListState.scrollToItem(0, 0)
            1 -> homeListState.scrollToItem(0, 0)
            2 -> functionsListState.scrollToItem(0, 0)
        }
    }

    val pageIndicatorState = remember(pagerState) {
        object : PageIndicatorState {
            override val pageCount: Int
                get() = 3
            override val pageOffset: Float
                get() = pagerState.currentPageOffsetFraction
            override val selectedPage: Int
                get() = pagerState.currentPage
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> SettingsPanel(
                    state = state,
                    listState = settingsListState,
                    onModeToggle = onModeToggle,
                    onKeepAliveToggle = onKeepAliveToggle,
                    onSafety90mToggle = onSafety90mToggle,
                    onStartPairingScan = onStartPairingScan,
                    onStopScan = onStopScan,
                    onForgetPairedDevice = onForgetPairedDevice,
                    onClearGatewayJacketBonding = onClearGatewayJacketBonding,
                    onToggleChoppedLow = onToggleChoppedLow,
                    onResetBattery = onResetBattery,
                    onConnectScan = onConnectScan,
                    onDisconnect = onDisconnect,
                    onNavigateToHome = {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    }
                )
                1 -> HomePanel(
                    state = state,
                    listState = homeListState,
                    onSetPreset = onSetPreset,
                    onTurnOffAll = onTurnOffAll,
                    onDismissSafetyAlert = onDismissSafetyAlert,
                    onConnectScan = onConnectScan,
                    onNavigateToSettings = {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    },
                    onNavigateToFunctions = {
                        coroutineScope.launch { pagerState.animateScrollToPage(2) }
                    }
                )
                2 -> FunctionsPanel(
                    state = state,
                    listState = functionsListState,
                    onCycleZone = onCycleZone,
                    onToggleStealth = onToggleStealth,
                    onToggleChoppedLow = onToggleChoppedLow,
                    onResetBattery = onResetBattery,
                    onStartProfile = onStartProfile,
                    onStopProfile = onStopProfile,
                    onNavigateToHome = {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    }
                )
            }
        }

        // Horizontal page indicator at the bottom
        HorizontalPageIndicator(
            pageIndicatorState = pageIndicatorState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 3.dp)
        )
    }
}

/**
 * Panel 0: Settings, Strict Pairing (<1m), Toggleable Options, and About Section.
 */
@Composable
fun SettingsPanel(
    state: VestState,
    listState: ScalingLazyListState,
    onModeToggle: (VestMode) -> Unit,
    onKeepAliveToggle: (Boolean) -> Unit,
    onSafety90mToggle: (Boolean) -> Unit,
    onStartPairingScan: () -> Unit,
    onStopScan: () -> Unit,
    onForgetPairedDevice: () -> Unit,
    onClearGatewayJacketBonding: () -> Unit,
    onToggleChoppedLow: () -> Unit,
    onResetBattery: () -> Unit,
    onConnectScan: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToHome: () -> Unit
) {
    var showEsp32Settings by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = stringResource(R.string.title_settings),
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
        }

        // 1. Hardware Pairing Card & MAC Display
        item {
            val isGateway = state.mode == VestMode.ESP32_GATEWAY
            Card(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(16.dp),
                backgroundPainter = CardDefaults.cardBackgroundPainter(
                    startBackgroundColor = Color(0xFF181A20),
                    endBackgroundColor = Color(0xFF101216)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isGateway) {
                        Text(
                            text = stringResource(R.string.esp32_suboption_title),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF80D8FF),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (state.connectionStatus == ConnectionStatus.CONNECTED) {
                                "● " + stringResource(R.string.esp32_status_connected)
                            } else {
                                "○ " + stringResource(R.string.esp32_status_disconnected)
                            },
                            fontSize = 9.sp,
                            color = if (state.connectionStatus == ConnectionStatus.CONNECTED) Color(0xFF00E676) else Color(0xFFFFB74D)
                        )
                        state.pairedMacAddress?.let { gwMac ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.mac_paired_format, gwMac),
                                fontSize = 8.5.sp,
                                color = Color.LightGray
                            )
                        }
                    } else {
                        val mac = state.pairedMacAddress
                        if (mac != null) {
                            Text(
                                text = stringResource(R.string.mac_paired_format, mac),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "● ${stringResource(R.string.mac_saved_badge)}",
                                fontSize = 9.sp,
                                color = Color.LightGray
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = onForgetPairedDevice,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(26.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB71C1C)),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.btn_forget_mac),
                                    fontSize = 9.sp,
                                    color = Color.White
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "⚠️",
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.mac_unpaired),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFFFB74D),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Radar Animation during Pairing Discovery
        if (state.isPairingScanning) {
            item {
                RadarScanner(onCancelScan = onStopScan)
            }
        } else if (state.mode == VestMode.DIRECT) {
            item {
                Chip(
                    onClick = onStartPairingScan,
                    label = {
                        Text(
                            text = stringResource(R.string.btn_pair_new),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = "Scan strict de proximité",
                            fontSize = 9.sp,
                            color = Color(0xFF80D8FF)
                        )
                    },
                    icon = { Text(text = "📡", fontSize = 12.sp) },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF00384D)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }

        // 3. Connect / Disconnect Chip & Status
        item {
            val isConnected = state.connectionStatus == ConnectionStatus.CONNECTED
            val isPaired = state.pairedMacAddress != null || state.mode == VestMode.ESP32_GATEWAY
            Chip(
                onClick = {
                    if (isConnected) {
                        onDisconnect()
                    } else if (isPaired) {
                        onConnectScan()
                    }
                },
                label = {
                    Text(
                        text = if (isConnected) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                },
                secondaryLabel = {
                    if (isConnected) {
                        Text(
                            text = "Touchez pour déconnecter",
                            fontSize = 8.5.sp,
                            color = Color(0xFFB0BEC5)
                        )
                    } else if (isPaired) {
                        Text(
                            text = "Touchez pour reconnecter",
                            fontSize = 8.5.sp,
                            color = Color(0xFF80D8FF)
                        )
                    } else {
                        Text(
                            text = "Veste non associée",
                            fontSize = 8.5.sp,
                            color = Color(0xFFFFB74D)
                        )
                    }
                },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (isConnected) Color(0xFF00E676) else Color(0xFFFF5252), CircleShape)
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1E1E24)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // 4. Sub-Option Dédiée: Passerelle ESP32
        item {
            val isGateway = state.mode == VestMode.ESP32_GATEWAY
            Chip(
                onClick = { showEsp32Settings = true },
                label = {
                    Text(
                        text = stringResource(R.string.esp32_suboption_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                secondaryLabel = {
                    Text(
                        text = if (isGateway) {
                            if (state.connectionStatus == ConnectionStatus.CONNECTED) {
                                "● " + stringResource(R.string.esp32_status_connected) + " • Configurer →"
                            } else {
                                "○ " + stringResource(R.string.esp32_status_disconnected) + " • Configurer →"
                            }
                        } else {
                            stringResource(R.string.esp32_toggle_desc_off) + " →"
                        },
                        fontSize = 8.5.sp,
                        color = if (isGateway) Color(0xFF80D8FF) else Color.LightGray
                    )
                },
                icon = { Text(text = "📡", fontSize = 12.sp) },
                colors = ChipDefaults.chipColors(
                    backgroundColor = if (isGateway) Color(0xFF0D253A) else Color(0xFF181A20)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // 5. KeepAlive Battery Toggle
        item {
            ToggleChip(
                checked = state.isKeepAliveEnabled,
                onCheckedChange = { onKeepAliveToggle(it) },
                label = {
                    Text(
                        text = stringResource(R.string.keepalive_title),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                secondaryLabel = {
                    Text(
                        text = stringResource(if (state.isKeepAliveEnabled) R.string.keepalive_desc_on else R.string.keepalive_desc_off),
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (state.isKeepAliveEnabled) Color(0xFF00E676) else Color.LightGray
                    )
                },
                toggleControl = { Switch(checked = state.isKeepAliveEnabled) },
                colors = ToggleChipDefaults.toggleChipColors(
                    checkedStartBackgroundColor = Color(0xFF0F3018),
                    uncheckedStartBackgroundColor = Color(0xFF18181A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // 6. Safety 90-Min Toggle
        item {
            ToggleChip(
                checked = state.isSafety90mEnabled,
                onCheckedChange = { onSafety90mToggle(it) },
                label = {
                    Text(
                        text = stringResource(R.string.safety_90m_title),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                secondaryLabel = {
                    Text(
                        text = stringResource(if (state.isSafety90mEnabled) R.string.safety_90m_desc_on else R.string.safety_90m_desc_off),
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (state.isSafety90mEnabled) Color(0xFFFF8A80) else Color.LightGray
                    )
                },
                toggleControl = { Switch(checked = state.isSafety90mEnabled) },
                colors = ToggleChipDefaults.toggleChipColors(
                    checkedStartBackgroundColor = Color(0xFF331414),
                    uncheckedStartBackgroundColor = Color(0xFF18181A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // 7. About Section Card
        item {
            val context = LocalContext.current
            Card(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(16.dp),
                backgroundPainter = CardDefaults.cardBackgroundPainter(
                    startBackgroundColor = Color(0xFF161820),
                    endBackgroundColor = Color(0xFF101116)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ℹ️ ${stringResource(R.string.about_app_name)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = stringResource(R.string.about_version),
                        fontSize = 8.5.sp,
                        color = Color(0xFFB0BEC5),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF2E2208), RoundedCornerShape(6.dp))
                            .border(0.8.dp, Color(0xFFFFA000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.about_unofficial_notice),
                            fontSize = 7.5.sp,
                            lineHeight = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFFD54F),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_legal_trademark),
                        fontSize = 7.5.sp,
                        lineHeight = 8.5.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFFCFD8DC),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = stringResource(R.string.about_legal_font),
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        color = Color(0xFF90A4AE),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.about_legal_libs),
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        color = Color(0xFF90A4AE),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(5.dp))

                    // Bouton Affilié Amazon Veste Kemimoto
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2E1C0A))
                            .border(1.dp, Color(0xFFFFB74D), RoundedCornerShape(10.dp))
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://link.amazon/B005JxEoX")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Navigateur introuvable", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(text = "🛒", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.btn_buy_vest_amazon),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD54F)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(5.dp))
                    // Lien GitHub cliquable avec redirection vers Samsung Browser
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F2233))
                            .border(1.dp, Color(0xFF2196F3), RoundedCornerShape(10.dp))
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lotus-z-dev/OpenKEMI_wearOS")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Navigateur introuvable", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(text = "🌐", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = "github.com/lotus-z-dev",
                                    fontSize = 7.5.sp,
                                    lineHeight = 8.sp,
                                    color = Color(0xFF90CAF9),
                                    textDecoration = TextDecoration.Underline
                                )
                                Text(
                                    text = "OpenKEMI_wearOS",
                                    fontSize = 8.5.sp,
                                    lineHeight = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE3F2FD),
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                        }
                    }
                }
            }
        }

        // 8. Navigation: Jump to Home
        item {
            Button(
                onClick = onNavigateToHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF263238)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.nav_to_home),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (showEsp32Settings) {
        val esp32ListState = rememberScalingLazyListState()
        val context = LocalContext.current
        val isGateway = state.mode == VestMode.ESP32_GATEWAY
        val isConnected = state.connectionStatus == ConnectionStatus.CONNECTED && isGateway

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .clickable { /* absorb background clicks */ }
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = esp32ListState,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Text(
                        text = stringResource(R.string.esp32_suboption_title),
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF80D8FF),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }

                // 1. Avertissement explicite obligatoire
                item {
                    Card(
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        backgroundPainter = CardDefaults.cardBackgroundPainter(
                            startBackgroundColor = Color(0xFF332008),
                            endBackgroundColor = Color(0xFF261805)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.esp32_warning_banner),
                            fontSize = 8.sp,
                            lineHeight = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFFD54F),
                            modifier = Modifier.padding(6.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // 2. Bascule du mode ESP32
                item {
                    ToggleChip(
                        checked = isGateway,
                        onCheckedChange = {
                            val nextMode = if (isGateway) VestMode.DIRECT else VestMode.ESP32_GATEWAY
                            onModeToggle(nextMode)
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.esp32_toggle_label),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = stringResource(if (isGateway) R.string.esp32_toggle_desc_on else R.string.esp32_toggle_desc_off),
                                fontSize = 8.5.sp,
                                color = if (isGateway) Color(0xFF80D8FF) else Color.LightGray
                            )
                        },
                        toggleControl = { Switch(checked = isGateway) },
                        colors = ToggleChipDefaults.toggleChipColors(
                            checkedStartBackgroundColor = Color(0xFF0D253A),
                            uncheckedStartBackgroundColor = Color(0xFF18181A)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                }

                // 3. Statut & Connexion/Déconnexion
                if (isGateway) {
                    item {
                        Chip(
                            onClick = {
                                if (isConnected) onDisconnect() else onConnectScan()
                            },
                            label = {
                                Text(
                                    text = if (isConnected) stringResource(R.string.esp32_btn_disconnect) else stringResource(R.string.esp32_btn_connect),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            },
                            secondaryLabel = {
                                Text(
                                    text = when {
                                        state.connectionStatus == ConnectionStatus.SCANNING -> stringResource(R.string.esp32_status_scanning)
                                        isConnected -> stringResource(R.string.esp32_status_connected)
                                        else -> stringResource(R.string.esp32_status_disconnected)
                                    },
                                    fontSize = 8.5.sp,
                                    color = if (isConnected) Color(0xFF00E676) else Color(0xFFFF5252)
                                )
                            },
                            icon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(if (isConnected) Color(0xFF00E676) else Color(0xFFFF5252), CircleShape)
                                )
                            },
                            colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF161E2E)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }

                    // 4. Oublier la veste sur l'ESP32 (envoyé en direct à l'ESP32)
                    item {
                        Button(
                            onClick = onClearGatewayJacketBonding,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .padding(horizontal = 14.dp, vertical = 1.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF6A1B1B)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.esp32_btn_forget_jacket),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // 5. Oublier la passerelle sur la montre
                    item {
                        Button(
                            onClick = onForgetPairedDevice,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .padding(horizontal = 14.dp, vertical = 1.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3E2723)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.esp32_btn_forget_gateway),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFCCBC)
                            )
                        }
                    }

                    // 6. Recalibrer la batterie virtuelle à 100%
                    item {
                        Button(
                            onClick = onResetBattery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .padding(horizontal = 16.dp, vertical = 1.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1B3A4B)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.btn_reset_battery),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF80D8FF)
                            )
                        }
                    }

                    // 7. Mode haché Chopped Low (5m/5m)
                    item {
                        ToggleChip(
                            checked = state.isChoppedLowActive,
                            onCheckedChange = { onToggleChoppedLow() },
                            label = {
                                Text(
                                    text = stringResource(R.string.chopped_low_title),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            secondaryLabel = {
                                Text(
                                    text = stringResource(R.string.chopped_low_desc),
                                    fontSize = 8.5.sp,
                                    color = if (state.isChoppedLowActive) Color(0xFF00E676) else Color.LightGray
                                )
                            },
                            toggleControl = { Switch(checked = state.isChoppedLowActive) },
                            colors = ToggleChipDefaults.toggleChipColors(
                                checkedStartBackgroundColor = Color(0xFF0F3018),
                                uncheckedStartBackgroundColor = Color(0xFF18181A)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                }

                // 8. Description détaillée
                item {
                    Card(
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        backgroundPainter = CardDefaults.cardBackgroundPainter(
                            startBackgroundColor = Color(0xFF14202C),
                            endBackgroundColor = Color(0xFF0E1620)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.esp32_info_desc),
                            fontSize = 8.sp,
                            lineHeight = 10.5.sp,
                            color = Color(0xFFECEFF1),
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }

                item {
                    // Bouton Affilié Amazon Veste Kemimoto dans modal ESP32
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2E1C0A))
                            .border(1.dp, Color(0xFFFFB74D), RoundedCornerShape(10.dp))
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://link.amazon/B005JxEoX")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Navigateur introuvable", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(text = "🛒", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.btn_buy_vest_amazon),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD54F)
                            )
                        }
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F2233))
                            .border(1.dp, Color(0xFF29B6F6), RoundedCornerShape(10.dp))
                            .clickable {
                                try {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/lotus-z-dev/openkemi_wearos_esp32proxy")
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Navigateur introuvable", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(text = "🌐", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = stringResource(R.string.esp32_info_github_label),
                                    fontSize = 7.5.sp,
                                    lineHeight = 9.sp,
                                    color = Color(0xFF90CAF9)
                                )
                                Text(
                                    text = "github.com/lotus-z-dev/openkemi_wearos_esp32proxy",
                                    fontSize = 6.8.sp,
                                    lineHeight = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4FC3F7),
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { showEsp32Settings = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(26.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text(
                            text = "✕ " + stringResource(R.string.esp32_info_close),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
    }
}

/**
 * Panel 1: Home Screen (Battery, Status, Quick 3-Color Presets, Directional Guidance).
 */
@Composable
fun HomePanel(
    state: VestState,
    listState: ScalingLazyListState,
    onSetPreset: (Int) -> Unit,
    onTurnOffAll: () -> Unit,
    onDismissSafetyAlert: () -> Unit,
    onConnectScan: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToFunctions: () -> Unit
) {
    val isPaired = state.pairedMacAddress != null
    val isConnected = state.connectionStatus == ConnectionStatus.CONNECTED

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Battery Display
        item {
            CircularBatteryCard(state = state)
        }

        // Safety 90-min Cutoff Triggered Warning
        if (state.isSafetyShutdownTriggered) {
            item {
                Chip(
                    onClick = onDismissSafetyAlert,
                    label = {
                        Text(
                            text = stringResource(R.string.safety_90m_triggered),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = "Touchez pour acquitter",
                            fontSize = 9.sp,
                            color = Color(0xFFFFCDD2),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFFB71C1C)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }

        // Diagnostic / Proximity Alert (if triggered)
        if (state.securityRejectionResId != null) {
            item {
                val msg = if (state.securityRejectionArg != null) {
                    stringResource(state.securityRejectionResId, state.securityRejectionArg)
                } else {
                    stringResource(state.securityRejectionResId)
                }
                ProximityAlertChip(message = msg)
            }
        }

        // Critical Battery Warning (<= 4%)
        if (state.isCriticalCutoff) {
            item {
                ProximityAlertChip(message = stringResource(R.string.battery_critical_alert))
            }
        }

        // Status & Pairing / Jacket Avatar Display
        val isGateway = state.mode == VestMode.ESP32_GATEWAY
        val isReady = isConnected

        if (isGateway) {
            // ESP32 Gateway Mode Active: Show Gateway connectivity status pill, never "Unpaired Jacket"
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isConnected) {
                        Row(
                            modifier = Modifier
                                .background(Color(0xFF0D2416), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFF00E676), RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "📡", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "● " + stringResource(R.string.esp32_status_connected),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF261E10), RoundedCornerShape(8.dp))
                                .border(0.8.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "○ " + stringResource(R.string.esp32_status_disconnected),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD54F),
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = onConnectScan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1B2838)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = "🔌 " + stringResource(R.string.esp32_btn_connect),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF80D8FF)
                            )
                        }
                    }
                }
            }
        } else if (!isPaired) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF2E1C0A), RoundedCornerShape(8.dp))
                            .border(0.8.dp, Color(0xFFFFB74D), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "⚠️ ${stringResource(R.string.status_unpaired_pill)}",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB74D),
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF004D40)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "📡", fontSize = 10.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.btn_goto_pairing),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Discret raccourci affilié Amazon si aucune veste appairée
                    val context = LocalContext.current
                    Text(
                        text = stringResource(R.string.buy_vest_hint),
                        fontSize = 8.sp,
                        color = Color(0xFF90CAF9),
                        modifier = Modifier
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://link.amazon/B005JxEoX")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                            .padding(2.dp)
                    )
                }
            }
        } else if (!isConnected) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF261E10), RoundedCornerShape(8.dp))
                            .border(0.8.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "○ ${stringResource(R.string.status_disconnected)}",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD54F),
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = onConnectScan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1B2838)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "🔌 ${stringResource(R.string.btn_connect)}",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF80D8FF)
                        )
                    }
                }
            }
        } else {
            // Veste Appairée & Connectée : Affiche la petite veste avec contour vert néon
            item {
                Row(
                    modifier = Modifier
                        .background(Color(0xFF0D2416), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF00E676), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher),
                        contentDescription = "Veste connectée",
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "● ${stringResource(R.string.paired_ready_status)}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676)
                    )
                }
            }
        }

        // Quick 3-Color Intensity Presets (Greyed out if not connected/ready)
        item {
            Box(modifier = Modifier.alpha(if (isReady) 1.0f else 0.4f)) {
                ThreeColorIntensityCard(
                    state = state,
                    isEnabled = isReady,
                    onSetPreset = onSetPreset,
                    onTurnOffAll = onTurnOffAll
                )
            }
        }

        // Boutons de navigation ergonomiques : Réglages (Gauche) et Profils & Zones (Droite)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF222831)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "⚙️ ${stringResource(R.string.btn_goto_settings)}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB0BEC5),
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onNavigateToFunctions,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1B2838)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "🎛️ ${stringResource(R.string.btn_goto_functions)}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF80D8FF),
                        maxLines = 1
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * Panel 2: Detailed Zones (Neck, Back, Belly), Stealth Mode, and Automated Profiles.
 */
@Composable
fun FunctionsPanel(
    state: VestState,
    listState: ScalingLazyListState,
    onCycleZone: (zone: String, currentLevel: Int) -> Unit,
    onToggleStealth: () -> Unit,
    onToggleChoppedLow: () -> Unit,
    onResetBattery: () -> Unit,
    onStartProfile: (HeatingProfile) -> Unit,
    onStopProfile: () -> Unit,
    onNavigateToHome: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Back to Home Button
        item {
            Button(
                onClick = onNavigateToHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF263238)),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(
                    text = stringResource(R.string.nav_back_home),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Active Profile Card (if running)
        if (state.activeProfile != null) {
            item {
                ActiveProfileChip(state, onStopProfile)
            }
        }

        // Zone 1: Col / Neck
        item {
            ZoneStatusCard(
                name = stringResource(R.string.zone_neck),
                level = state.neckLevel,
                onClick = { onCycleZone("col", state.neckLevel) }
            )
        }

        // Zone 2: Dos / Back
        item {
            ZoneStatusCard(
                name = stringResource(R.string.zone_back),
                level = state.backLevel,
                onClick = { onCycleZone("dos", state.backLevel) }
            )
        }

        // Zone 3: Ventre / Belly
        item {
            ZoneStatusCard(
                name = stringResource(R.string.zone_belly),
                level = state.bellyLevel,
                onClick = { onCycleZone("ventre", state.bellyLevel) }
            )
        }

        // Stealth Mode Switch
        item {
            StealthStatusCard(
                isStealth = state.isStealthMode,
                onToggle = onToggleStealth
            )
        }

        // Gateway Specific Quick Action: Chopped Low Mode (5/5) & Battery Reset
        if (state.mode == VestMode.ESP32_GATEWAY) {
            item {
                ChoppedLowCard(
                    isActive = state.isChoppedLowActive,
                    onToggle = onToggleChoppedLow
                )
            }
            item {
                ResetBatteryChip(onReset = onResetBattery)
            }
        }

        // Automated Profiles Header
        item {
            Text(
                text = stringResource(R.string.title_automated_profiles),
                style = MaterialTheme.typography.title3,
                color = Color.LightGray,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        items(PredefinedProfiles.ALL_PROFILES, key = { it.id }) { profile ->
            ProfileStatusChip(
                profile = profile,
                isActive = state.activeProfile?.id == profile.id,
                onClick = { onStartProfile(profile) }
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * Circular Battery Card.
 */
@Composable
fun CircularBatteryCard(state: VestState) {
    val percent = state.displayBatteryPercent ?: 0
    val progress = (percent / 100.0f).coerceIn(0.0f, 1.0f)
    val runtimeMins = state.remainingRuntimeMinutes

    val progressColor = when {
        percent <= 15 -> Color(0xFFFF1744)
        percent <= 35 -> Color(0xFFFFD600)
        else -> Color(0xFF00E676)
    }

    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = Color(0xFF141416),
            endBackgroundColor = Color(0xFF0C0C0E)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp)
            ) {
                CircularProgressIndicator(
                    progress = progress,
                    indicatorColor = progressColor,
                    trackColor = Color(0xFF2C2C30),
                    strokeWidth = 4.dp,
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    text = state.batteryDisplay ?: "--",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = if (state.mode == VestMode.ESP32_GATEWAY) "Batterie Virtuelle" else "Batterie Veste",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (runtimeMins != null && runtimeMins > 0 && state.mode == VestMode.ESP32_GATEWAY) {
                    Text(
                        text = stringResource(R.string.battery_runtime_format, runtimeMins),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF80D8FF)
                    )
                }
                if (state.accumulatedMah != null && state.mode == VestMode.ESP32_GATEWAY) {
                    Text(
                        text = "Conso: ${state.accumulatedMah} mAh",
                        fontSize = 9.sp,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}

/**
 * 3-Color Intensity Controller Card.
 */
@Composable
fun ThreeColorIntensityCard(
    state: VestState,
    isEnabled: Boolean,
    onSetPreset: (Int) -> Unit,
    onTurnOffAll: () -> Unit
) {
    val isHeating = state.isAnyZoneHeating
    val currentLevel = if (state.neckLevel == state.backLevel && state.backLevel == state.bellyLevel) {
        state.neckLevel
    } else if (isHeating) {
        maxOf(state.neckLevel, state.backLevel, state.bellyLevel)
    } else {
        0
    }

    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = if (isHeating) Color(0xFF1E1414) else Color(0xFF161618),
            endBackgroundColor = Color(0xFF101012)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isHeating) stringResource(R.string.vest_heating_on) else stringResource(R.string.vest_heating_off),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHeating) Color(0xFFFF5252) else Color.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ColorPresetButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.preset_green),
                    level = 1,
                    isSelected = currentLevel == 1,
                    isEnabled = isEnabled,
                    activeColor = HeatLowGreen,
                    activeBg = Color(0xFF00E676),
                    onClick = { if (isEnabled) onSetPreset(1) }
                )

                ColorPresetButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.preset_blue),
                    level = 2,
                    isSelected = currentLevel == 2,
                    isEnabled = isEnabled,
                    activeColor = HeatMidBlue,
                    activeBg = Color(0xFF00B0FF),
                    onClick = { if (isEnabled) onSetPreset(2) }
                )

                ColorPresetButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.preset_red),
                    level = 3,
                    isSelected = currentLevel == 3,
                    isEnabled = isEnabled,
                    activeColor = HeatHighRed,
                    activeBg = Color(0xFFFF1744),
                    onClick = { if (isEnabled) onSetPreset(3) }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = { if (isEnabled) onTurnOffAll() },
                enabled = isEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (!isHeating) Color(0xFF37474F) else Color(0xFF212124)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.btn_turn_off),
                    fontSize = 10.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun ColorPresetButton(
    modifier: Modifier = Modifier,
    label: String,
    level: Int,
    isSelected: Boolean,
    isEnabled: Boolean,
    activeColor: Color,
    activeBg: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .background(
                color = if (isSelected) activeBg else Color(0xFF1C1C20),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color.White else activeColor.copy(alpha = if (isEnabled) 0.5f else 0.2f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = isEnabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isSelected) "✔ $label" else label,
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (isSelected) {
                if (level == 1 || level == 2) Color.Black else Color.White
            } else {
                if (isEnabled) activeColor else Color.Gray
            },
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ZoneStatusCard(name: String, level: Int, onClick: () -> Unit) {
    val isOn = level > 0
    val (labelRes, accentColor) = when (level) {
        1 -> R.string.level_low to HeatLowGreen
        2 -> R.string.level_mid to HeatMidBlue
        3 -> R.string.level_high to HeatHighRed
        else -> R.string.level_off to Color.Gray
    }

    Chip(
        onClick = onClick,
        icon = {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(accentColor, CircleShape)
                    .border(1.dp, if (isOn) Color.White else Color.DarkGray, CircleShape)
            )
        },
        label = {
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
        },
        secondaryLabel = {
            Text(
                text = if (isOn) "${stringResource(R.string.state_on)} • ${stringResource(labelRes)}" else stringResource(R.string.state_off),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isOn) accentColor else Color.Gray,
                maxLines = 1
            )
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = if (isOn) accentColor.copy(alpha = 0.16f) else Color(0xFF1A1A1C)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
fun StealthStatusCard(isStealth: Boolean, onToggle: () -> Unit) {
    ToggleChip(
        checked = isStealth,
        onCheckedChange = { onToggle() },
        label = {
            Text(
                text = stringResource(R.string.stealth_mode),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        },
        secondaryLabel = {
            Text(
                text = stringResource(if (isStealth) R.string.led_off else R.string.led_on),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isStealth) Color(0xFF80D8FF) else Color.Gray,
                maxLines = 1
            )
        },
        toggleControl = { Switch(checked = isStealth) },
        colors = ToggleChipDefaults.toggleChipColors(
            checkedStartBackgroundColor = Color(0xFF1E293B),
            uncheckedStartBackgroundColor = Color(0xFF18181A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
fun ActiveProfileChip(state: VestState, onStop: () -> Unit) {
    val mins = state.stepRemainingSeconds / 60
    val secs = state.stepRemainingSeconds % 60
    val timeFormatted = "%02d:%02d".format(mins, secs)

    val profileTitle = state.activeProfile?.titleRes?.let { stringResource(it) } ?: ""
    val stepLabel = state.currentStepLabelRes?.let { stringResource(it) } ?: ""
    val stopLabel = stringResource(R.string.stop_action)

    Chip(
        onClick = onStop,
        colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1B5E20)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        label = {
            Text(
                text = "⚡ $profileTitle (${stringResource(R.string.state_on)})",
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color.White,
                maxLines = 1
            )
        },
        secondaryLabel = {
            Text(
                text = "$stepLabel • $timeFormatted ($stopLabel)",
                fontSize = 9.sp,
                color = Color(0xFFB9F6CA),
                maxLines = 1
            )
        }
    )
}

@Composable
fun ProfileStatusChip(
    profile: HeatingProfile,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isActive) Color(0xFF0D47A1) else Color(0xFF1E1E24)

    Chip(
        onClick = onClick,
        colors = ChipDefaults.chipColors(backgroundColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        label = {
            Text(
                text = stringResource(profile.titleRes),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1
            )
        },
        secondaryLabel = {
            Text(
                text = if (isActive) "${stringResource(R.string.state_on)} • En cours" else stringResource(profile.subtitleRes),
                fontSize = 9.sp,
                color = if (isActive) Color(0xFF80D8FF) else Color.LightGray,
                maxLines = 1
            )
        }
    )
}

@Composable
fun ChoppedLowCard(
    isActive: Boolean,
    onToggle: () -> Unit
) {
    ToggleChip(
        checked = isActive,
        onCheckedChange = { onToggle() },
        label = {
            Text(
                text = stringResource(R.string.chopped_low_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        },
        secondaryLabel = {
            Text(
                text = stringResource(R.string.chopped_low_desc),
                fontSize = 9.sp,
                color = if (isActive) Color(0xFF00E676) else Color.LightGray
            )
        },
        toggleControl = { Switch(checked = isActive) },
        colors = ToggleChipDefaults.toggleChipColors(
            checkedStartBackgroundColor = Color(0xFF0F3018),
            uncheckedStartBackgroundColor = Color(0xFF18181A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
fun ResetBatteryChip(onReset: () -> Unit) {
    Chip(
        onClick = onReset,
        label = {
            Text(
                text = stringResource(R.string.btn_reset_battery),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        },
        secondaryLabel = {
            Text(
                text = "Recalibre la jauge à 100%",
                fontSize = 9.sp,
                color = Color.LightGray
            )
        },
        icon = { Text(text = "🔄", fontSize = 12.sp) },
        colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1F1F24)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
fun ProximityAlertChip(message: String) {
    Chip(
        onClick = {},
        colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF3E2723)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        label = {
            Text(
                text = "⚠️ $message",
                color = ProximityWarningColor,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
