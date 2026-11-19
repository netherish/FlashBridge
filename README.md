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

It speaks the **fastboot protocol directly over Android's USB Host API** (`UsbManager` + bulk USB transfers), so it works on any normal, unrooted Android 8.0+ phone with OTG support.

```
┌─────────────────┐   OTG cable   ┌─────────────────┐
│  HOST PHONE     │──────────────▶│  TARGET PHONE   │
│  FlashBridge    │   fastboot /   │  fastboot mode  │
│  (no root)      │   bulk USB     │                 │
└─────────────────┘               └─────────────────┘
```

---

## Features

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
| **Target device** | Booted into fastboot mode (usually Volume Down + Power) |
| **Images** | Correct ROM/firmware files stored on the host phone |

> ⚠️ Flashing firmware can **brick** a device if done wrong. FlashBridge confirms destructive actions, but you are responsible for using the correct files for your specific device.

---

## How it works

Android's USB Host API lets an app claim a connected USB device's bulk endpoints. The fastboot protocol is simply text commands over those endpoints:

1. **Detect** — FlashBridge scans USB devices for known fastboot IDs (e.g. Google `0x18D1:0xD00D`) and asks for USB permission.
2. **Connect** — claims the device's bulk IN/OUT endpoints.
3. **Command** — sends `flash:boot`, `getvar:product`, etc. as ASCII over the bulk endpoint.
4. **Response** — reads the 4-byte status (`OKAY` / `FAIL` / `INFO` / `DATA`) exactly like a PC fastboot client.
5. **Flash** — on `DATA<size>`, streams the selected image bytes over USB, then verifies the final `OKAY`.

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
