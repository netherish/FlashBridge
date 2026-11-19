<div align="center">

# FlashBridge

### Use an Android phone as a firmware flashing station

[![Author](https://img.shields.io/badge/By-netherish-blueviolet)](https://github.com/netherish)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue)

[Download FlashBridge v1.8.1 (APK)](https://github.com/netherish/FlashBridge/releases/download/v1.8.1/FlashBridge-v1.8.1-full.apk)

</div>

---

## Download

**[Download the latest APK (v1.8.1)](https://github.com/netherish/FlashBridge/releases/download/v1.8.1/FlashBridge-v1.8.1-full.apk)** — Android 8.0+, no root required.

Or browse [all releases](https://github.com/netherish/FlashBridge/releases). Before installing, review the [compatibility reports](docs/TESTED_DEVICES.md) and the [security policy](SECURITY.md).

---

## What is FlashBridge?

FlashBridge turns one Android phone into a firmware flashing workstation. Connect a second device over an OTG cable and flash custom ROMs, recoveries, kernels and firmware images directly from the host phone, without a PC or root access.

It implements the fastboot and ADB protocols directly on top of Android's USB Host API (`UsbManager` and bulk USB transfers), so it runs on any unrooted Android 8.0+ device with OTG support.

```
Host phone (FlashBridge, unrooted) -- OTG cable / bulk USB --> Target phone (fastboot or ADB mode)
```

---

## Screenshots

Captures from the v1.8.1 build on an unrooted Android phone.

| Flash queue | Fastboot tools | ADB tools |
|:---:|:---:|:---:|
| <img src="assets/screenshots/01-flash.png" width="220" alt="Flash tab"> | <img src="assets/screenshots/02-fastboot.png" width="220" alt="Fastboot tab"> | <img src="assets/screenshots/03-adb.png" width="220" alt="ADB tab"> |

| Console |
|:---:|
| <img src="assets/screenshots/05-console.png" width="220" alt="Console tab"> |

---

## Features

### Flash

- Multi-image queue: add several files at once, set each partition independently, and flash the queue in one run.
- SHA-256 checksum computed for every queued image.
- Any partition name: boot, recovery, system, vendor, vbmeta, or a custom name.
- Temporary boot with `fastboot boot` without permanently flashing.
- Live transfer progress for flash and boot operations.

### Fastboot

- Command suite: `flash`, `boot`, `erase`, `format`, `wipe`, `getvar all`, `set_active`, `flashing unlock` / `flashing lock`, `unlock_critical`, `oem`, and raw commands.
- A/B slot switching and reboot to system, recovery, fastboot or continue.
- Post-flash factory reset: format data, metadata and cache, then reboot, to avoid encryption bootloops.
- Confirmation dialogs for every destructive action.

### ADB

- Device detection with a full RSA authentication handshake and device information via `getprop`.
- Remote shell with an output console.
- Sideload ROMs, GApps and OTA ZIPs directly from recovery.
- Push files over the ADB sync protocol with progress.
- Reboot to bootloader, recovery, fastboot, download or system.

### Core

- No root and no bundled binaries: pure Kotlin using the Android USB Host API.
- Universal fastboot detection by USB interface class, across vendors.
- Session log, device information panel and numeric transfer progress.
- Tabbed Material 3 interface (Flash / Fastboot / ADB / Console) with animated transitions.
- Standard and advanced operations are clearly separated; destructive operations are hidden behind an in-app Advanced switch.

---

## Requirements

| Item | Detail |
|---|---|
| Host phone | Android 8.0+ with OTG / USB host support. No root needed. |
| OTG cable | A data cable, not charge-only. USB-C to USB-A or USB-C to USB-C OTG adapter. |
| Target device | Either ADB mode (USB debugging enabled) or fastboot mode (Volume Down + Power). |
| Images | Correct ROM or firmware files stored on the host phone. |

**Notice:** flashing firmware can brick a device if done incorrectly. FlashBridge confirms destructive actions, but you are responsible for using the correct files for your specific device.

---

## How it works

Android's USB Host API allows an app to claim the bulk endpoints of a connected USB device. Both ADB and fastboot are protocols over those endpoints.

### Detect

Press **Detect device**. FlashBridge scans connected USB devices:

1. **Fastboot device** — detected by the standard USB fastboot interface (`0xFF/0x42/0x03`), which works across vendors; flash actions then unlock.
2. **ADB device** (USB class `0xFF`, subclass `0x42`, protocol `0x01`) — performs a full ADB handshake:
   - Sends `CNXN` and handles `AUTH` tokens by signing with a locally generated 2048-bit RSA key in the AOSP-compatible `RSAPublicKey` format, so the standard "Allow USB debugging?" prompt appears on the target.
   - Once authenticated, reads `getprop ro.product.model` and `getprop ro.product.device`.
   - Offers **Reboot to Fastboot** (`adb reboot bootloader`); press Detect again to flash.

### Flash

Fastboot is text commands over bulk USB. Flashing uses the standard three-step AOSP sequence:

1. Send `download:<size in hex>`; the device replies `DATA<size in hex>`.
2. Stream exactly that many image bytes over the bulk OUT endpoint using pipelined USB requests, with live progress.
3. Verify the upload reply, then send `flash:<partition>` and verify the final `OKAY`.

This is the same protocol that `fastboot` on a PC uses, implemented natively in Kotlin with no external binaries or root shell.

### After flashing

Use the **After flashing** card on the Flash tab: **Factory reset** (format userdata, metadata and cache), then **Reboot system**. Formatting userdata clears the old encryption state, which is the usual cause of bootloops.

### Sideload (recovery)

Boot the target into custom recovery, choose **Apply update**, then **Apply from ADB**. Connect, press Detect, then use **Sideload ZIP** on the Flash or ADB tab. The ZIP is streamed over the ADB `sideload:` service with live progress, equivalent to `adb sideload` on a PC.

### Limitations

Standard fastboot and ADB devices are supported across vendors. Samsung (Odin/download mode), Qualcomm 9008 (QFIL) and MediaTek BROM/preloader use proprietary protocols and are not supported. Bootloader unlock may additionally require OEM unlocking to be enabled, vendor authorisation or internet access, depending on the device.

---

## Build from source

You need JDK 17 and the Android SDK (platform 36, build tools 35/36).

Two product flavors are produced:

- `full` — all fastboot and ADB operations, with destructive ones behind an in-app Advanced switch. This is the primary build used by F-Droid.
- `play` — destructive and experimental operations compiled out (`BuildConfig.ADVANCED_FEATURES = false`). Kept for restricted distribution; not required for F-Droid.

```powershell
.\gradlew.bat test assembleFullRelease
```

The unsigned APK is written to `app\build\outputs\apk\full\release\app-full-release-unsigned.apk`. F-Droid signs builds itself, so no local keystore is needed for submission. For local installation, signing credentials come from `FB_KEYSTORE` / `FB_KEY_ALIAS` / `FB_STORE_PASS` / `FB_KEY_PASS` or an untracked `signing.properties`; the included `sign-and-install.ps1` reads the same values. Never commit a keystore.

---

## Ownership

This is an original project by [netherish](https://github.com/netherish), released under the [MIT License](LICENSE). It contains no third-party binaries or copied code, only original Kotlin using the Android SDK and Apache-2.0 AndroidX/Compose libraries. F-Droid compatible.
