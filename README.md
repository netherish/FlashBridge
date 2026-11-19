<div align="center">

# FlashBridge

### Your phone is the flashing station. Flash any device, anywhere — no PC needed.

[![Author](https://img.shields.io/badge/By-netherish-blueviolet)](https://github.com/netherish)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Status](https://img.shields.io/badge/Status-in%20progress-orange)
![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-red)

</div>

---

## What is FlashBridge?

**FlashBridge** turns one Android phone into a complete flashing workstation. Connect a second device over an **OTG cable** and flash custom ROMs, recoveries, kernels, and firmware images directly from the host phone — no computer required.

Think of it as **fastboot + ADB sideload + ODIN/EDL, running on your phone**, with a clean Material 3 UI that walks you through the process.

```
┌─────────────────┐   OTG cable   ┌─────────────────┐
│  HOST PHONE     │──────────────▶│  TARGET PHONE   │
│  FlashBridge    │   fastboot /   │  fastboot mode /│
│  (this app)     │   adb / edl    │  download mode  │
└─────────────────┘               └─────────────────┘
```

---

## Features (planned)

- ⚡ **Fastboot flashing** — `flash`, `erase`, `boot`, `reboot` commands from the phone
- 📦 **ROM flashing** — flash full ROM zips, `system.img`, `boot.img`, `vendor.img`, etc.
- 🔄 **ADB sideload** — sideload OTA/update zips to a device in recovery
- 🔓 **Unlock / relock bootloader** — one-tap `fastboot oem unlock` flows
- 🧩 **Multi-mode support** — Fastboot, ADB Recovery, EDL (Qualcomm), ODIN (Samsung, via protocol)
- 📁 **Local file browser** — pick images/ROMs from host storage with checksum verification
- 🛡️ **Safety checks** — verify image signatures/hashes, confirm target device identity, prevent wrong-device flashes
- 📜 **Flash log** — full command output with copy/export for debugging
- 💾 **Profile saving** — save device-specific flash recipes for repeat use

---

## Requirements

| Item | Detail |
|---|---|
| **Host phone** | OTG support + **root** (for raw USB access to fastboot/ADB) |
| **OTG cable** | USB-C → USB-A or USB-C → USB-C OTG adapter/cable |
| **Target device** | Must support fastboot / download / EDL / recovery mode |
| **Images** | Correct ROM/firmware files stored on the host phone |
| **Android** | Host: 8.0+ (API 26) |

> ⚠️ Flashing firmware can **brick** a device if done wrong. FlashBridge includes safety checks, but you are responsible for using the correct files for your specific device.

---

## How it works

FlashBridge bundles statically-compiled **fastboot** and **adb** binaries for Android (`aarch64` / `arm`), communicates with the target device over USB OTG, and exposes the operations through a guided UI:

1. Connect target device in the appropriate mode (fastboot, recovery, download, EDL).
2. FlashBridge detects the connected device over USB.
3. Pick an image/ROM file from host storage (hash verified).
4. Choose an operation (flash, boot, sideload, erase, reboot).
5. FlashBridge runs the command and streams live output to the log.

---

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- Root via **libsu**
- Bundled **fastboot** / **adb** binaries (Android builds)
- USB device detection via Android `UsbManager`
- Coroutines + Flows for streaming command output

---

## Status

This repo is **in active development**. The README defines the product vision and architecture; implementation is being built out. Star / watch for updates.

---

## Ownership

This is an original project by **[netherish](https://github.com/netherish)**. No open-source license is applied — all rights are reserved by the author. No GPL, no third-party license obligations, no attribution requirements.
