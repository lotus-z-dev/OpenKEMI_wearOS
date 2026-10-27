# OpenKEMI for Wear OS

[![Release](https://img.shields.io/github/v/release/lotus-z-dev/OpenKEMI_wearOS?color=blue&label=Latest%20Release)](https://github.com/lotus-z-dev/OpenKEMI_wearOS/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Wear%20OS%205%2B%20%7C%20Galaxy%20Watch%207-orange.svg)](https://developer.android.com/wear)

An open-source, security-hardened **Wear OS** application designed to control **Kemimoto Bluetooth Low Energy (BLE) Heated Apparel** directly from your smartwatch (optimized for Samsung Galaxy Watch 7 / Wear OS 5+).

---

## 📥 Download Release APK

The latest production-ready, minified, and optimized APK can be downloaded directly from GitHub Releases:

👉 **[Download Latest OpenKEMI WearOS APK](https://github.com/lotus-z-dev/OpenKEMI_wearOS/releases/latest)**

---

## 🛡️ Security & Privacy Architecture

Unlike the proprietary vendor application, OpenKEMI is built with an uncompromising security-first philosophy:

1. **Zero Internet Access (`android.permission.INTERNET` omitted):**
   - The application has zero network capabilities.
   - It cannot leak telemetry, user location, hardware identifiers, or usage habits to remote servers.
2. **Calibrated Physical Proximity Guard (1.0 Meter Line-of-Sight):**
   - Implements RF threshold verification calibrated via the Friis Free Space Path Loss model ($A = -70\text{ dBm}$, $n = 2.0$).
   - Both BLE advertisements and active control commands require RSSI $\ge -70\text{ dBm}$.
   - Packets from beyond 1.0 meter are rejected to mitigate BLE relay, spoofing, or unintended garment manipulation.
3. **Hardened Foreground Service:**
   - Uses `connectedDevice` foreground service type strictly scoped to maintaining active heating element timers while the watch screen sleeps.
4. **Local IPC Isolation:**
   - All internal services are non-exported (`android:exported="false"`), preventing local privilege escalation from other third-party apps on the watch.

---

## ✨ Features

- **Wear OS Material 3 Compose UI:** Clean, glanceable controls tailored for round smartwatch displays.
- **Independent Zone Heating:** Granular control over the 3 heating elements:
  - **Col / Neck** (Collar / Upper Spine)
  - **Dos / Back** (Upper Back / Shoulder Blades)
  - **Ventre / Belly** (Lower Abdomen / Front Pockets)
- **Presets & Emergency Off:** Quick-access buttons for Éteint (0), Éco (1 - Green), Confort (2 - Blue), and Boost (3 - Red).
- **Stealth Mode (Mode Furtif):** Silences the physical LED button lights on the garment for nighttime riding or discretion.
- **Voice & Assistant Integration:**
  - Android App Shortcuts and Dynamic Shortcuts compatible with Google Assistant and Samsung Bixby / Modes & Routines.
  - Deep links: `kemimoto://action?command=heat&zone=all&level=X`.
- **Wear OS Tile (`VestTileService`):** Swipe directly from the watch face to view active status and toggle heat.
- **Watch Face Complication (`VestComplicationService`):** Display heating state right on your favorite watch face.

---

## 📡 Protocol Specification

- **Primary Service UUID:** `0000ff00-0000-1000-8000-00805f9b34fb` (16-bit alias `0xFF00`)
- **Telemetry Notification UUID:** `0000ff01-0000-1000-8000-00805f9b34fb` (`0xFF01`)
- **Control Write UUID:** `0000ff02-0000-1000-8000-00805f9b34fb` (`0xFF02`)

### 14-Byte Binary Control Frame
| Byte Offset | Value | Description |
|---|---|---|
| `[0]` | `0xAA` | Protocol Synchronization Header |
| `[1]` | `0x0E` | Frame Length (14 bytes decimal) |
| `[2]` | `0x01` | Command Code: Control Write |
| `[3]` | `0x01` | Device Type: Heated Vest / Jacket |
| `[4]` | `0x00` | Sub-command / Reserved |
| `[5]` | `0x00..0x03` | Neck Heating Level (0=Off, 1=Low, 2=Mid, 3=High) |
| `[6]` | `0x00..0x03` | Back Heating Level (0=Off, 1=Low, 2=Mid, 3=High) |
| `[7]` | `0x00..0x03` | Belly Heating Level (0=Off, 1=Low, 2=Mid, 3=High) |
| `[8]` | `0x00 / 0x01` | Stealth Mode LED Switch (`0x00` = LEDs On, `0x01` = LEDs Off) |
| `[9..12]` | `0xFF` | Padding Bytes |
| `[13]` | `0x0D` | Frame Termination Delimiter |

---

## 🚀 Building & Installing

### Prerequisites
- JDK 17
- Android SDK (API 34 / Build Tools 34.0.0)
- Gradle 8.10+

### Build Release APK
```bash
./gradlew assembleRelease
```
The optimized and signed output will be generated at:
`app/build/outputs/apk/release/app-release.apk`

### Install via ADB over Wi-Fi
```bash
adb connect <WATCH_IP>:<PORT>
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.supplices.kemimotowear android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.supplices.kemimotowear android.permission.BLUETOOTH_SCAN
```

---

## 📄 License

Copyright (c) 2026 lotus-z-dev. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
