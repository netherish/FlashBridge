<div align="center">

# FlashBridge

### Your phone is the flashing station. Flash any device, anywhere — no PC, no root.

[![Author](https://img.shields.io/badge/By-netherish-blueviolet)](https://github.com/netherish)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Status](https://img.shields.io/badge/Status-working-brightgreen)

[⬇️ **Download FlashBridge APK (v1.3 Advanced)**](https://github.com/netherish/FlashBridge/releases/download/v1.3/flashbridge.apk)

</div>

---

## Download

**[Download the latest APK (v1.3 Advanced)](https://github.com/netherish/FlashBridge/releases/download/v1.3/flashbridge.apk)** — Android 8.0+ · no root required

Or browse [all releases](https://github.com/netherish/FlashBridge/releases).

---

## What is FlashBridge?

**FlashBridge** turns one Android phone into a complete flashing workstation. Connect a second device over an **OTG cable** and flash custom ROMs, recoveries, kernels, and firmware images directly from the host phone — no computer and no root required.

It speaks the **fastboot** and **ADB** protocols directly over Android's USB Host API (`UsbManager` + bulk USB transfers), so it works on any normal, unrooted Android 8.0+ phone with OTG support.

```
┌─────────────────┐   OTG cable    ┌──────────────────────┐
│  HOST PHONE     │───────────────▶│  TARGET PHONE        │
│  FlashBridge    │  fastboot/ADB  │  ADB or fastboot mode│
│  (no root)      │  bulk USB      │                      │
└─────────────────┘                └──────────────────────┘
```

---

## Features

### Advanced Flash tab
- 📦 **Multi-image queue** — add multiple files at once, set each partition independently, flash the whole queue in one run
- 🔐 **SHA-256 checksum** computed for every queued image
- 🧩 **Any partition** — boot, recovery, system, vendor, vbmeta — type the name, pick the image
- 🚀 **Temporary boot** — `fastboot boot` without permanently flashing

### Fastboot tools
- ⚡ Full command suite: `flash`, `boot`, `erase`, `format`, `wipe`, `getvar all`, `set_active`, `flashing unlock/lock`, `unlock_critical`, `oem`, raw commands
- 🔀 A/B slot switching, reboot to system / recovery / fastboot / continue
- 🛡️ Safety confirmations on every destructive action

### ADB tools
- 🔍 ADB detection with full RSA auth handshake and device info (`getprop`)
- 💻 **ADB shell** with output console
- 📲 **ADB sideload** — install ROMs, GApps and OTA ZIPs directly from recovery
- 📤 **ADB push** via the sync protocol with progress
- 🔁 Reboot to bootloader / recovery / fastboot / download / system

### Everywhere-compatible core
- 🔓 **No root, no binaries** — pure Kotlin implementation using the Android USB Host API
- 🌐 Universal fastboot detection by USB interface class — works across vendors
- 📊 Live transfer progress, device info panel, and full session log
- 🎨 Tabbed Material 3 advanced interface (Flash / Fastboot / ADB / Console)

---

## Requirements

| Item | Detail |
|---|---|
| **Host phone** | Android 8.0+ with **OTG / USB host** support — **no root needed** |
| **OTG cable** | A **data** cable (not charge-only) — USB-C → USB-A or USB-C → USB-C OTG adapter |
| **Target device** | Either in **ADB mode** (USB debugging on) or **fastboot mode** (Volume Down + Power) |
| **Images** | Correct ROM/firmware files stored on the host phone |

> ⚠️ Flashing firmware can **brick** a device if done wrong. FlashBridge confirms destructive actions, but you are responsible for using the correct files for your specific device.

---

## How it works

Android's USB Host API lets an app claim a connected USB device's bulk endpoints. Both ADB and fastboot are just protocols over those endpoints.

### Detect

Press **Detect device**. FlashBridge scans USB devices:

1. **Fastboot device** — detected by the standard USB fastboot interface (`0xFF/0x42/0x03`), so it works across vendors; flash actions unlock.
2. **ADB device** (USB class `0xFF` / subclass `0x42` / protocol `0x01`) → performs a full ADB handshake:
   - Sends `CNXN`, handles `AUTH` tokens by signing with a locally generated 2048-bit RSA key (AOSP-compatible `RSAPublicKey` blob), so the standard "Allow USB debugging?" prompt appears on the target.
   - Once authenticated, reads `getprop ro.product.model` / `getprop ro.product.device`.
   - Offers **Reboot to Fastboot** (`adb reboot bootloader`) — then press Detect again to flash.

### Flash

Fastboot is text commands over bulk USB:

1. Send `flash:<partition>` → device replies `DATA<size>`.
2. Stream the selected image bytes over the bulk OUT endpoint with live progress.
3. Verify the final `OKAY` reply.

This is the same protocol `fastboot` on a PC uses — implemented natively in Kotlin, so no external binaries or root shell are involved.

### Sideload (recovery)

Boot the target into custom recovery, choose **Apply update → Apply from ADB**, connect, press Detect, then **Sideload ZIP** on the Flash or ADB tab. The ZIP is streamed over the ADB `sideload:` service with live progress — exactly like `adb sideload` on a PC.

### Limitations

Standard fastboot and ADB devices are supported across vendors. Samsung (Odin/download mode), MediaTek (SP Flash Tool) and Qualcomm 9008 (QFIL) use proprietary protocols that cannot be driven through fastboot/ADB. Bootloader unlock may additionally require OEM unlocking to be enabled, a Mi-account authorization (Xiaomi), or internet access depending on the vendor.

---

## Build from source

You need JDK 17 and Android SDK (platform 36, build-tools 35/36).

```powershell
.\gradlew.bat assembleRelease
```

The unsigned APK is at `app\build\outputs\apk\release\`. Sign it with `apksigner` before installing, or use the included `sign-and-install.ps1` (edit the keystore password first).

---

## Ownership

This is an original project by **[netherish](https://github.com/netherish)**. No open-source license is applied — all rights are reserved by the author. No GPL, no third-party license obligations, no attribution requirements. The implementation contains no third-party binaries or copied code — just original Kotlin using the Android SDK.
