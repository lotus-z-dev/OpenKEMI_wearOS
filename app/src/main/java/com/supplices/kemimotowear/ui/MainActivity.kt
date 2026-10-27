package com.supplices.kemimotowear.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.supplices.kemimotowear.R
import com.supplices.kemimotowear.model.ConnectionStatus
import com.supplices.kemimotowear.model.HeatingProfile
import com.supplices.kemimotowear.model.PredefinedProfiles
import com.supplices.kemimotowear.model.VestState
import com.supplices.kemimotowear.service.KemimotoBleService
import com.supplices.kemimotowear.ui.theme.*

/**
 * Main Activity for OpenKEMI on Wear OS (Samsung Galaxy Watch 7).
 *
 * Ergonomically optimized for circular displays using Wear OS Material Chips
 * to guarantee zero text truncation, vertical wrapping, or overlap.
 */
class MainActivity : ComponentActivity() {

    val bleServiceState = mutableStateOf<KemimotoBleService?>(null)
    private var isBound = false

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

        val serviceIntent = Intent(this, KemimotoBleService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            KemimotoWearTheme {
                val currentService = bleServiceState.value
                val vestState by (currentService?.combinedState?.collectAsState()
                    ?: remember { mutableStateOf(VestState()) })

                KemimotoWearScreen(
                    state = vestState,
                    onConnectScan = {
                        val svc = bleServiceState.value
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
                        svc?.bleManager?.startScanAndConnect()
                    },
                    onDisconnect = {
                        bleServiceState.value?.bleManager?.disconnectAndClean()
                    },
                    onCycleZone = { zone, currentLevel ->
                        val next = (currentLevel + 1) % 4
                        bleServiceState.value?.bleManager?.setZoneLevel(zone, next)
                    },
                    onSetPreset = { targetLevel ->
                        val svc = bleServiceState.value
                        val currentMax = maxOf(vestState.neckLevel, vestState.backLevel, vestState.bellyLevel)
                        val newLevel = if (currentMax == targetLevel && vestState.isAnyZoneHeating) 0 else targetLevel
                        svc?.profileManager?.stopProfile()
                        svc?.bleManager?.setLevels(newLevel, newLevel, newLevel)
                    },
                    onTurnOffAll = {
                        val svc = bleServiceState.value
                        svc?.profileManager?.stopProfile()
                        svc?.bleManager?.setLevels(0, 0, 0)
                    },
                    onToggleStealth = {
                        bleServiceState.value?.bleManager?.toggleStealthMode()
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
            val greenShortcut = ShortcutInfoCompat.Builder(this, "heat_green")
                .setShortLabel(getString(R.string.shortcut_green_label))
                .setLongLabel(getString(R.string.shortcut_green_long_label))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(greenIntent)
                .build()

            val blueIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("kemimoto://action?command=heat&zone=all&level=2")
            }
            val blueShortcut = ShortcutInfoCompat.Builder(this, "heat_blue")
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
        if (isBound) {
            unbindService(connection)
            isBound = false
            bleServiceState.value = null
        }
        super.onDestroy()
    }
}

/**
 * Clean circular Wear OS layout using standard Wear Chips.
 */
@Composable
fun KemimotoWearScreen(
    state: VestState,
    onConnectScan: () -> Unit,
    onDisconnect: () -> Unit,
    onCycleZone: (zone: String, currentLevel: Int) -> Unit,
    onSetPreset: (level: Int) -> Unit,
    onTurnOffAll: () -> Unit,
    onToggleStealth: () -> Unit,
    onStartProfile: (HeatingProfile) -> Unit,
    onStopProfile: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Connection Status Chip
        item {
            ConnectionStatusChip(state, onConnectScan, onDisconnect)
        }

        // 2. Security Alert (if triggered)
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

        // 3. 3-Color Intensity Controller (Blue / Green / Red)
        item {
            ThreeColorIntensityCard(state, onSetPreset, onTurnOffAll)
        }

        // 4. Active Profile Card (if running)
        if (state.activeProfile != null) {
            item {
                ActiveProfileChip(state, onStopProfile)
            }
        }

        // 5. Zone 1: Col / Neck
        item {
            ZoneStatusCard(
                name = stringResource(R.string.zone_neck),
                level = state.neckLevel,
                onClick = { onCycleZone("col", state.neckLevel) }
            )
        }

        // 6. Zone 2: Dos / Back
        item {
            ZoneStatusCard(
                name = stringResource(R.string.zone_back),
                level = state.backLevel,
                onClick = { onCycleZone("dos", state.backLevel) }
            )
        }

        // 7. Zone 3: Ventre / Belly
        item {
            ZoneStatusCard(
                name = stringResource(R.string.zone_belly),
                level = state.bellyLevel,
                onClick = { onCycleZone("ventre", state.bellyLevel) }
            )
        }

        // 8. Stealth Mode Switch
        item {
            StealthStatusCard(
                isStealth = state.isStealthMode,
                onToggle = onToggleStealth
            )
        }

        // 9. Automated Profiles Header
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
    }
}

/**
 * Connection Chip: Single Wear Chip that properly formats connection & battery.
 */
@Composable
fun ConnectionStatusChip(
    state: VestState,
    onConnectScan: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = state.connectionStatus == ConnectionStatus.CONNECTED
    val isScanning = state.connectionStatus == ConnectionStatus.SCANNING

    val statusDotColor = when {
        isConnected -> Color(0xFF00E676)
        isScanning -> Color(0xFFFFD600)
        else -> Color(0xFFFF1744)
    }

    Chip(
        onClick = { if (isConnected) onDisconnect() else onConnectScan() },
        icon = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusDotColor, CircleShape)
            )
        },
        label = {
            val status = when {
                isConnected -> stringResource(R.string.status_connected)
                isScanning -> stringResource(R.string.status_scanning)
                else -> stringResource(R.string.status_disconnected)
            }
            val batt = if (isConnected && state.batteryDisplay != null) " • 🔋 ${state.batteryDisplay}" else ""
            Text(
                text = "$status$batt",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
        },
        secondaryLabel = {
            val action = if (isConnected) {
                stringResource(R.string.btn_disconnect)
            } else {
                stringResource(R.string.btn_connect)
            }
            Text(
                text = action,
                fontSize = 10.sp,
                color = Color.LightGray,
                maxLines = 1
            )
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = when {
                isConnected -> Color(0xFF0A2616)
                isScanning -> Color(0xFF26210A)
                else -> Color(0xFF221111)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
    )
}

/**
 * 3-Color Intensity Controller:
 * Features 3 clear buttons: Blue (1), Green (2), Red (3), and a Turn Off button.
 * Selected button illuminates with solid color and checkmark.
 */
@Composable
fun ThreeColorIntensityCard(
    state: VestState,
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
            .padding(horizontal = 14.dp, vertical = 3.dp),
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
            // Header text: Veste Allumée / Éteinte
            Text(
                text = if (isHeating) stringResource(R.string.vest_heating_on) else stringResource(R.string.vest_heating_off),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHeating) Color(0xFFFF5252) else Color.LightGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Row of 3 Distinct Color Buttons: Vert, Bleu, Rouge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ColorPresetButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.preset_green),
                    level = 1,
                    isSelected = currentLevel == 1,
                    activeColor = HeatLowGreen,
                    activeBg = Color(0xFF00E676),
                    onClick = { onSetPreset(1) }
                )

                ColorPresetButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.preset_blue),
                    level = 2,
                    isSelected = currentLevel == 2,
                    activeColor = HeatMidBlue,
                    activeBg = Color(0xFF00B0FF),
                    onClick = { onSetPreset(2) }
                )

                ColorPresetButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.preset_red),
                    level = 3,
                    isSelected = currentLevel == 3,
                    activeColor = HeatHighRed,
                    activeBg = Color(0xFFFF1744),
                    onClick = { onSetPreset(3) }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Master Turn Off Button
            Button(
                onClick = onTurnOffAll,
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

/**
 * Individual Color Button with high-contrast active state.
 */
@Composable
fun ColorPresetButton(
    modifier: Modifier = Modifier,
    label: String,
    level: Int,
    isSelected: Boolean,
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
                color = if (isSelected) Color.White else activeColor.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isSelected) "✔ $label" else label,
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (isSelected) {
                if (level == 1 || level == 2) Color.Black else Color.White
            } else {
                activeColor
            },
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Dedicated Zone Controller:
 * Built as a standard Wear OS Chip so labels stack vertically (Name on top, Level below).
 * Zero horizontal clipping or vertical wrapping.
 */
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
            .padding(horizontal = 14.dp, vertical = 2.dp)
    )
}

/**
 * Stealth Mode Controller using native Wear OS ToggleChip and Switch.
 */
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
        toggleControl = {
            Switch(checked = isStealth)
        },
        colors = ToggleChipDefaults.toggleChipColors(
            checkedStartBackgroundColor = Color(0xFF1E293B),
            uncheckedStartBackgroundColor = Color(0xFF18181A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
    )
}

/**
 * Active Profile Chip with real-time countdown.
 */
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
            .padding(horizontal = 14.dp, vertical = 3.dp),
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

/**
 * Profile Selection Chip with ON/OFF indicator.
 */
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
            .padding(horizontal = 14.dp, vertical = 2.dp),
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

/**
 * Security & Proximity Alert Chip.
 */
@Composable
fun ProximityAlertChip(message: String) {
    Chip(
        onClick = {},
        colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF3E2723)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
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
