<div align="center">

# FlashBridge

### Your phone is the flashing station. Flash any device, anywhere — no PC, no root.

[![Author](https://img.shields.io/badge/By-netherish-blueviolet)](https://github.com/netherish)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Status](https://img.shields.io/badge/Status-working-brightgreen)

</div>

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

- 🔍 **ADB device detection** — detects devices in normal Android mode with USB debugging enabled, reads their model/device props, and can reboot them straight into fastboot
- ⚡ **Fastboot flashing** — `flash`, `erase`, `getvar`, `reboot`, `flashing unlock` over raw USB
- 📦 **Any partition** — boot, recovery, system, vendor, vbmeta — type the name, pick the image
- 📁 **Local file picker** — choose any image/ROM file from host storage; streamed directly over USB
- 📊 **Live progress** — real-time transfer percentage and full command log
- 🛡️ **Safety confirmations** — destructive actions (erase, unlock) require explicit confirmation
- 🔓 **No root, no binaries** — pure Kotlin implementation using the Android USB Host API

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

1. **Fastboot device** (e.g. Google `0x18D1:0xD00D`) → connects directly; flash actions unlock.
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
