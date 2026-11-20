# OpenKEMI for Wear OS

[![Release](https://img.shields.io/github/v/release/lotus-z-dev/OpenKEMI_wearOS?color=blue&label=Latest%20Release)](https://github.com/lotus-z-dev/OpenKEMI_wearOS/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Wear%20OS%205%2B%20%7C%20Galaxy%20Watch%207-orange.svg)](https://developer.android.com/wear)

An open-source, security-hardened, **100% offline** **Wear OS** application for controlling [Kemimoto](https://link.amazon/A061Fmvk2) Bluetooth Low Energy (BLE) heated apparel directly from your smartwatch (optimized for Samsung Galaxy Watch 7 / Wear OS 5+), supporting both **Direct BLE Mode** and the encrypted **[ESP32 Hardware Gateway](https://github.com/lotus-z-dev/openkemi_wearos_esp32proxy)**.

<p align="center">
  <a href="https://link.amazon/B005JxEoX">
    <img src="https://m.media-amazon.com/images/I/71wLpWn0jYL._AC_SL1500_.jpg" alt="Kemimoto BLE Heated Vest" width="320" />
  </a>
  <br />
  <em><a href="https://link.amazon/B005JxEoX">Get the compatible Kemimoto Heated Vest on Amazon</a></em>
</p>

---

## Download Release APK

Pre-built, minified, and release-signed APKs (with SHA-256 checksums) are published via GitHub Releases:

- **[Download Latest OpenKEMI WearOS APK](https://github.com/lotus-z-dev/OpenKEMI_wearOS/releases/latest)**

---

## Security & Privacy Architecture

Unlike proprietary companion apps that require cloud accounts, analytics, and broad phone permissions, OpenKEMI is built strictly for **sovereign local operation**:

```text
+-----------------------------------------------------------------------+
| OpenKEMI Wear OS App (Zero INTERNET permission, allowBackup="false")  |
| - EncryptedSharedPreferences (Android Keystore AES-256-GCM / SIV)     |
| - Non-exported Foreground Service (connectedDevice only)              |
| - Non-browsable local App Shortcuts with strict allowlist validation  |
+---------------------------+-------------------------------------------+
                            |
        +-------------------+-------------------+
        | Mode 1: Direct BLE                    | Mode 2: ESP32 Gateway
        v                                       v
+---------------------------+       +-----------------------------------+
| Kemimoto Vest (0xFF00)    |       | ESP32-C3/C6 Gateway (LESC P-256)  |
| - MAC Pinned (Encrypted)  |       | - Bonded Identity Pinned          |
| - < 1.0 m RSSI >= -70 dBm |       | - < 1.5 m RSSI >= -75 dBm         |
| - 20m Max-Heat Step-Down  |       | - Zero-RF KeepAlive + Chopped Low |
| - 90m Safety Watchdog     |       | - Dual Hardware Thermal Watchdogs |
+---------------------------+       +-----------------------------------+
```

### Key Security Controls

| Control | Implementation | Why It Matters |
| :--- | :--- | :--- |
| **Zero Network Surface** | `android.permission.INTERNET` omitted completely | Physically impossible for the app to phone home or exfiltrate usage/telemetry data. |
| **No Location Permission** | `BLUETOOTH_SCAN` uses `neverForLocation` | Prevents BLE scans from being used for geofencing or location tracking. |
| **Encrypted Pairing Storage** | Android Keystore `EncryptedSharedPreferences` (`AES256_SIV` / `AES256_GCM`) + `android:allowBackup="false"` | Protects pinned vest and gateway MAC/identity metadata against backup extraction. |
| **Anti-Spoofing Device Pinning** | Bonded identity check (`BOND_BONDED` + pinned MAC) | Rejects rogue `"OK-GW"` BLE spoofers and third-party vests nearby. |
| **Calibrated Proximity Gate** | `< 1.0 m` (`RSSI >= -70 dBm`) in Direct Mode / `< 1.5 m` (`RSSI >= -75 dBm`) in Gateway Mode | Blocks remote BLE relay activation while always allowing emergency shutoff (`0, 0, 0`). |
| **Locked-Down IPC & Deep Links** | Non-exported `KemimotoBleService`; `BROWSABLE` removed; strict allowlist on `zone`/`level`; `mode`/`reset` UI-only | Prevents malicious web pages or third-party watch apps from turning on heat, resetting battery safety, or downgrading Gateway mode. |
| **Release Signing Hygiene** | Environment-injected release keystore (`v2`/`v3` APK Signature Scheme); never signed with debug keys | Prevents unauthorized debug-key APK overwrite attacks. |
| **Thermal Safety Watchdogs** | 20-minute Max-Heat step-down + 90-minute audible alarm auto-shutoff (also disables Chopped Low) | Protects against accidental prolonged high-power heating. |

---

## Features

- **Wear OS Material 3 Compose UI:** Three swipeable panels tailored for circular screens (Settings & Pairing, Home Presets, Zones & Automated Profiles).
- **Dual Operating Modes:**
  - **Direct BLE Mode:** Pair directly with your Kemimoto vest (`< 1 m` proximity).
  - **ESP32 Gateway Mode:** Pair over encrypted BLE LESC with [openkemi_wearos_esp32proxy](https://github.com/lotus-z-dev/openkemi_wearos_esp32proxy) for Zero-RF power-bank keep-alive, virtual coulomb counter, and **Chopped Low (5m ON / 5m OFF)** mode.
- **Independent Zone Heating:** Separate control for **Neck**, **Back**, and **Belly** (Levels 0–3) plus **Stealth Mode** (LEDs off).
- **Automated Heating Profiles:** 5 built-in cyclic and warmup routines (`5m/5m`, `5m/10m Eco`, `10m/10m Extreme`, `Rapid Warmup`, `Moto/Wind`).
- **Wear OS Tile & Watch Face Complication:** Instant launch and status access from your watch face or tile carousel.
- **Bilingual (English & French):** Full UI, alerts, notifications, and voice shortcuts in English and French.

---

## Compatible Apparel

Designed for official [Kemimoto BLE heated apparel](https://link.amazon/A061Fmvk2):
- **[Kemimoto Heated Vest on Amazon](https://link.amazon/B005JxEoX)**

---

## Step-by-Step Build, Signing & Installation Guide

### Prerequisites
- **JDK 17** (Temurin / OpenJDK 17)
- **Android SDK** (Compile/Target API 34, Min API 30)
- **Gradle Wrapper** (`./gradlew`, included)

### 1. Generate a Private Release Keystore (One-Time Setup)
Never sign production APKs with the public Android debug key:

```bash
keytool -genkeypair -v -keystore openkemi-release.jks -alias openkemi \
  -keyalg RSA -keysize 4096 -validity 10000
```

*(Store your `.jks` file securely offline and never commit it to Git.)*

### 2. Build a Signed Release APK Locally
Export the 4 signing environment variables and run Gradle:

```bash
export OPENKEMI_KEYSTORE_PATH="/path/to/openkemi-release.jks"
export OPENKEMI_KEYSTORE_PASSWORD="your-keystore-password"
export OPENKEMI_KEY_ALIAS="openkemi"
export OPENKEMI_KEY_PASSWORD="your-key-password"
./gradlew assembleRelease
```

The minified, resource-shrunk, signed APK is generated at:
`app/build/outputs/apk/release/app-release.apk`

> **GitHub Actions Releases:** To publish signed tags automatically, add `OPENKEMI_KEYSTORE_BASE64` (`base64 -w0 openkemi-release.jks`), `OPENKEMI_KEYSTORE_PASSWORD`, `OPENKEMI_KEY_ALIAS`, and `OPENKEMI_KEY_PASSWORD` to your repository's **GitHub Actions Secrets**.

### 3. Install on Your Wear OS Watch via ADB over Wi-Fi
```bash
adb connect <WATCH_IP>:<PORT>
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.supplices.kemimotowear android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.supplices.kemimotowear android.permission.BLUETOOTH_SCAN
```

---

## License & Legal Notices

Copyright (c) 2026 lotus-z-dev. All rights reserved.

Licensed under the **Apache License, Version 2.0** — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
OpenKEMI is an independent open-source project and is not affiliated with or endorsed by Kemimoto®.
