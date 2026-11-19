# Security policy

## Supported versions

Only the latest released version receives fixes. Downloads are published on the
[releases page](https://github.com/netherish/FlashBridge/releases).

| Version | Supported |
|---|---|
| 1.6.x | ✅ |
| < 1.6 | ❌ |

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

- bundles **no** vendor binaries, download agents, or exploit payloads
- does **not** implement security-bypass exploits
- requires the user to supply any DA or authorization files themselves
- confirms destructive actions before running them

Flashing incorrect images can permanently damage hardware. Always back up
your data and verify that firmware matches the exact device model.

## Integrity

Release APKs are signed with the maintainer's release key and a SHA-256 digest
is published in the release notes. Verify the digest before installing.
