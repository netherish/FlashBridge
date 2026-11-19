# Compatibility reports

FlashBridge speaks standard USB protocols, but USB Host behaviour, cable quality
and bootloader implementations vary by device. This page collects real reports.
**Empty rows mean "not tested yet", not "unsupported".**

## Host phones (running FlashBridge)

| Host device | Android | OTG | Result | Reported by |
|---|---|---|---|---|
| Unrooted test handset (serial `105de4200405`) | 14 | USB-C OTG | App installs and launches; USB Host API available; no crashes | netherish |

## Target devices (flashed by FlashBridge)

| Target | Mode | Action | Result | Notes | Reported by |
|---|---|---|---|---|---|
| Redmi 9 | Fastboot | Flash a 64 MiB `boot` image | Success | Verified with the v1.8.1 build; ADB connection also confirmed | netherish |
| Vivo Y73 | Fastboot and ADB | Flash image, ADB authorize and connect | Success | Verified with the v1.8.1 build, including the AOSP-compatible authorization fix | netherish |

## How to test safely

1. Use a device you own and can afford to lose. Back up everything first.
2. Use a **data** OTG cable/adapter — many cheap cables are charge-only.
3. Start with a harmless read command (`getvar all` in fastboot, `getprop` in ADB)
   before trying any write.
4. Confirm that the image matches the exact model and partition.
5. Report results with: host model, target model, Android version, mode
   (fastboot/ADB/recovery), cable type, and the exact command used.

Open an issue using the **Compatibility report** template to add your results.
