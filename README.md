<div align="center">

# FlashBridge

### Your phone is the flashing station. Flash any device, anywhere — no PC, no root.

[![Author](https://img.shields.io/badge/By-netherish-blueviolet)](https://github.com/netherish)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Status](https://img.shields.io/badge/Status-working-brightgreen)

[⬇️ **Download FlashBridge APK (v1.6)**](https://github.com/netherish/FlashBridge/releases/download/v1.6/flashbridge.apk)

</div>

---

## Download

**[Download the latest APK (v1.6)](https://github.com/netherish/FlashBridge/releases/download/v1.6/flashbridge.apk)** — Android 8.0+ · no root required

Or browse [all releases](https://github.com/netherish/FlashBridge/releases).

Before installing, check the [compatibility reports](docs/TESTED_DEVICES.md) and the [security policy](SECURITY.md).

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

## Screenshots

Real UI captures from the v1.6 build on an unrooted Android phone.

| Flash queue | Fastboot tools | ADB tools |
|:---:|:---:|:---:|
| <img src="assets/screenshots/01-flash.png" width="220" alt="Flash tab"> | <img src="assets/screenshots/02-fastboot.png" width="220" alt="Fastboot tab"> | <img src="assets/screenshots/03-adb.png" width="220" alt="ADB tab"> |

| MediaTek detection | BYO DA / auth / raw memory | Console |
|:---:|:---:|:---:|
| <img src="assets/screenshots/04-mtk.png" width="220" alt="MTK tab"> | <img src="assets/screenshots/06-mtk-details.png" width="220" alt="MTK advanced tools"> | <img src="assets/screenshots/05-console.png" width="220" alt="Console tab"> |

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
- 🧹 **Post-flash factory reset** — format data + metadata + cache, then reboot, right after flashing (prevents encryption bootloops)
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
- 🎨 Tabbed Material 3 advanced interface (Flash / Fastboot / ADB / MTK / Console)
- 📟 **MediaTek BROM/preloader support** — instant auto-detect, chipset/security readout, **bring-your-own DA** parsing (V3/V5/V6), user-supplied auth/cert upload, `SEND_DA` + `JUMP_DA`/`JUMP_DA64`, raw 32-bit memory read/write
- 🧩 **No bundled binaries** — you supply the DA and auth files; FlashBridge recognises and uses them

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

After flashing a custom ROM, use the **After flashing** card on the Flash tab: **Factory reset** (format userdata + metadata + cache), then **Reboot system**. Formatting userdata clears the old encryption state, which is the usual cause of bootloops.

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

### MediaTek (MTK) devices

The **MTK tab** detects MediaTek devices in BROM or preloader mode (USB VID `0x0E8D`), performs the first-stage handshake, and reads:

- HW code / HW version / HW subcode / SW version
- Bootloader and BROM versions
- Chipset name (MT6735, MT6765 Helio P35, Dimensity, …)
- Target security config — SBC, SLA, DAA, certificate and auth flags

To enter BROM: power off, hold **Volume Up + Power** (or Volume Down + Power), connect USB, then release. For preloader: power off and just connect USB.

#### Bring your own DA (v1.6+)

The MTK tab now goes beyond identification:

1. **Choose a DA file** from your own storage. FlashBridge parses the documented 0x6C-byte header and DA entry table: protocol (Legacy V3 / XFlash V5 / XML V6), file ID, version, supported hardware codes, and each stage's offset, load address, size and signature length. It checks the DA against the connected chip's HW code and shows SHA-256.
2. **Add your own authorization files** if the chip requires BROM SLA or DAA. They are sent with the standard `SEND_AUTH` (0xE2) / `SEND_CERT` (0xE0) commands.
3. **Upload and jump** — the selected DA stage is sent with `SEND_DA` (0xD7) to its header-specified address, checksummed, then executed with `JUMP_DA` (0xD5) or `JUMP_DA64` (0xDE).
4. **Raw memory tools** — `READ32` (0xD1) and `WRITE32` (0xD4) for advanced diagnosis once a DA is running.

This is the same first-stage path PC tools use, implemented in pure Kotlin. FlashBridge **bundles no MediaTek binaries** and does **not implement exploit-based security bypasses** (kamakiri, carbonara, heapbait, etc.). Writing/formatting flash partitions is performed by commands inside the DA itself; support for those DA-level commands is not claimed here. Only use DA and auth files you are entitled to, for devices you own, and keep a backup.

### Limitations

Standard fastboot and ADB devices are supported across vendors. Samsung (Odin/download mode) and Qualcomm 9008 (QFIL) use proprietary protocols that cannot be driven through fastboot/ADB. MediaTek partition writes need a compatible running DA. Bootloader unlock may additionally require OEM unlocking to be enabled, a Mi-account authorization (Xiaomi), or internet access depending on the vendor.

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
