# Security policy

## Supported versions

Only the latest released version receives fixes. Downloads are published on the
[releases page](https://github.com/netherish/FlashBridge/releases).

| Version | Supported |
|---|---|
| 1.8.x | Yes |
| < 1.8 | No |

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.

Use [GitHub's private vulnerability reporting](https://github.com/netherish/FlashBridge/security/advisories/new)
if available, or contact the maintainer directly through GitHub. Include:

- affected version and device model / Android version
- steps to reproduce
- impact (what an attacker could do)
- any suggested fix

You should receive an acknowledgement within a few days. Please give reasonable
time for a fix before public disclosure.

## Safe-use statement

FlashBridge is a repair and firmware utility for devices you own or are
authorised to modify. It deliberately:

- bundles **no** vendor binaries, firmware, or exploit payloads
- does **not** implement security-bypass exploits
- requires the user to supply every image flashed
- confirms destructive actions before running them

Flashing incorrect images can permanently damage hardware. Always back up
your data and verify that firmware matches the exact device model.

## Integrity

Release APKs are signed. GitHub publishes a SHA-256 digest for each release
asset; verify it before installing. F-Droid builds and signs its own APK from
source using the metadata in `fdroid.yml`.
