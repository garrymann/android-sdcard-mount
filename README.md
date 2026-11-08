# Removable Storage Manager

[![Build and test](https://github.com/garrymann/android-sdcard-mount/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/garrymann/android-sdcard-mount/actions/workflows/build-and-test.yml)
[![Latest release](https://img.shields.io/github/v/release/garrymann/android-sdcard-mount?sort=semver)](https://github.com/garrymann/android-sdcard-mount/releases/latest)
![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue)](LICENSE)

An Android app that detects removable storage — USB-OTG drives, card readers and internal microSD cards —
identifies the filesystem on every partition, and makes it accessible wherever the platform allows.
When Android will not let an app reach a volume, it says exactly why, and whether root would help.

[Overview](#overview) • [Status](#project-status) • [Getting started](#getting-started) • [Architecture](#architecture) • [Contributing](#contributing) • [License](#license)

> [!IMPORTANT]
> This project is in early development. Volume detection, filesystem classification and SAF access
> grants work today; mounting, the userspace drivers and the `DocumentsProvider` are not built yet.
> See [Project status](#project-status).

## Overview

Android mounts FAT and exFAT on removable media and nothing else. NTFS, ext4 and F2FS cards show up as
"unsupported" or "damaged", and a generic error rarely tells you whether the problem is the card, the
phone, or the platform. Removable Storage Manager is a *storage access provider* first:

- **Detects** USB mass-storage devices and every volume `StorageManager` reports, including ones the
  platform refused to mount.
- **Identifies** filesystems from superblock and boot-sector magic — FAT12/16/32, exFAT, NTFS,
  ext2/3/4 and F2FS — and labels HFS+, APFS, Btrfs, XFS, ZFS, LVM and LUKS as unsupported instead of
  printing bytes.
- **Gives access** through the Storage Access Framework without root, a userspace USB driver for
  filesystems `vold` cannot mount, or a real kernel/FUSE mount when root is available.
- **Exposes** everything through a `DocumentsProvider`, so any file manager can use it. A minimal
  built-in browser covers standalone use.
- **Refuses** to write to anything it cannot write safely: unrecognised, encrypted, adoptable or
  inconsistent volumes stay read-only or untouched.

Out of scope: network shares, cloud storage, MTP/PTP, archive handling, and rooting the device. The app
uses root if it is already there; it never obtains it.

### What is possible, and where

Some limits are set by AOSP, not by this app — an unprivileged app can never open a block device or run
FUSE. The capability contract it is built to:

| Filesystem | Internal SD, no root | Internal SD, root | USB-OTG, no root | USB-OTG, root |
|---|---|---|---|---|
| FAT12/16/32 | ✅ via SAF | ✅ kernel mount | ✅ SAF or userspace driver | ✅ kernel mount |
| exFAT | ✅ via SAF on Android 13+; vendor-dependent on 11–12 | ✅ kernel mount | ✅ SAF or userspace driver | ✅ kernel mount |
| NTFS | ❌ impossible | ✅ `ntfs-3g` FUSE, or `ntfs3` | ⚠️ userspace driver (P1) | ✅ `ntfs-3g` FUSE |
| ext2/3/4 | ❌ impossible | ✅ kernel mount | ⚠️ userspace driver (P2) | ✅ kernel mount |
| F2FS | ❌ impossible | ✅ kernel mount | ❌ not planned | ✅ kernel mount |

The platform constraints behind this table are summarised in
[REQUIREMENTS.md](REQUIREMENTS.md#read-this-first).

## Project status

| Area | State |
|---|---|
| USB mass-storage and `StorageManager` volume detection | ✅ Implemented |
| Pure volume classifier with specific, per-case inaccessibility reasons | ✅ Implemented |
| SAF tree grants: request, persist and revoke | ✅ Implemented |
| Volume list and About screens (Compose, Material 3) | ✅ Implemented |
| Partition-table parsing and superblock identification | 🚧 Scaffolded in `:core-fs` |
| `DocumentsProvider` | ⏳ Planned (P0) |
| Root path: `RootService`, global-namespace mounts, eject | ⏳ Planned (P0) |
| Raw USB mode: BOT/SCSI transport and FAT userspace driver | ⏳ Planned (P0) |
| exFAT/NTFS userspace drivers, `fsck` | ⏳ Planned (P1) |

## Getting started

### Download

Signed APKs of the `foss` flavor are published on
[GitHub Releases](https://github.com/garrymann/android-sdcard-mount/releases):

- **[Latest release](https://github.com/garrymann/android-sdcard-mount/releases/latest)**: tagged,
  versioned builds.
- **[Nightly](https://github.com/garrymann/android-sdcard-mount/releases/tag/nightly)**: rebuilt from
  every push to `main`. Untested; use it only to try a fix.

Each release includes a `SHA256SUMS` file for the APK. Both channels are signed with the same key, so
they upgrade each other as long as the version code increases.

### Prerequisites

- JDK 17
- Android SDK with the **API 37** platform (`compileSdk` 37) and platform-tools (`adb`)
- A device or emulator running Android 11 (API 30) or later

The Gradle wrapper is pinned to Gradle 9.6.1 and checksum-verified. See
[gradle/wrapper/README.md](gradle/wrapper/README.md).

### Build and install

```bash
git clone https://github.com/garrymann/android-sdcard-mount.git
cd android-sdcard-mount
./gradlew :app:assemblePlayDebug
```

On Windows, [scripts/install-play-debug.ps1](scripts/install-play-debug.ps1) builds the Play debug APK
and installs it on the connected device:

```powershell
.\scripts\install-play-debug.ps1 -Launch            # build, install and start
.\scripts\install-play-debug.ps1 -SkipBuild -Serial <device-id>
```

### Run the tests

| Command | What it runs |
|---|---|
| `./gradlew :core-fs:test` | Fast JVM unit tests for the filesystem core |
| `./gradlew :app:connectedPlayDebugAndroidTest` | Compose UI tests on an API 35+ emulator or device |
| `./gradlew check` | All verification tasks, including the Play/root separation guard |

> [!TIP]
> Most of the interesting logic — including every "why can't I open this card?" message — lives in the
> pure `VolumeClassifier`, so it is covered by `:core-fs:test` without an emulator or real hardware.

### Build flavors

The app ships from one codebase in two flavors:

| Flavor | Channel | Root support |
|---|---|---|
| `play` | Google Play | None. `libsu` and root code are excluded from the artifact. |
| `foss` | Sideload / F-Droid | Optional, via [libsu](https://github.com/topjohnwu/libsu) (from JitPack). |

`./gradlew :app:verifyPlayHasNoRootCode` (part of `check`) fails the build if any root code or `libsu`
dependency can reach the Play artifact.

### Versioning

`versionName` and `versionCode` are derived from git tags: nothing in the repository is edited to cut
a release:

```bash
git tag v1.2.3
./gradlew :app:printAppVersion
git push origin v1.2.3
```

Pushing the tag runs [release.yml](.github/workflows/release.yml), which tests the build, signs the
`foss` release APK and publishes it with its checksum as the GitHub release `v1.2.3`. Every push to
`main` refreshes the `nightly` pre-release in the same way. Signing reads the repository secrets
`RSM_KEYSTORE_BASE64` and `RSM_KEYSTORE_PASSWORD`; locally, a release build is signed only when
`RSM_KEYSTORE_PATH` and `RSM_KEYSTORE_PASSWORD` are set in the environment.

Untagged builds fall back to `baseVersion` in [version.properties](version.properties) and report
`<base>-dev.<commits>`.

## Architecture

Two access paths converge on one interoperability surface:

```text
detection (USB attach intents + UsbManager.getDeviceList() + StorageManager volumes
           + /proc/partitions and /sys/block when root)
        │
        ├── no-root ── SAF delegation for platform-mounted volumes  (preferred: FAT32/exFAT)
        │           └─ raw USB mode: claim interface → BOT/SCSI → userspace FS parser
        │              (explicit, destructive, for filesystems vold cannot mount)
        │
        └── root ───── RootService (libsu nio FileSystemManager) → global-namespace mount
                       (kernel driver, or ntfs-3g as a separate process)
        │
        └──────────── DocumentsProvider  ← primary interoperability surface (both paths)
                      + minimal built-in browser (standalone use, and fallback)
```

### Project structure

```text
app/        Android app: Compose UI, platform adapters (StorageManager, UsbManager, SAF)
  src/main  Shared by both flavors — must never reference root code
  src/foss  Root-enabled flavor overlay
core-fs/    Pure Kotlin/JVM: block devices, partition tables, filesystem identification,
            volume classification. No android.* imports, so it is fully unit-testable.
scripts/    Developer helper scripts
```

Design principles that apply throughout:

- **Probe, don't guess.** Capabilities are detected at runtime on the actual device and filesystem,
  never inferred from Android version, phone model or OEM.
- **Integrity over access.** Writes are refused rather than risk corruption, and data is flushed and
  synced before success is reported.
- **No generic errors.** Every inaccessible volume gets a specific reason and an answer to "would root
  help?".

## Requirements

Code comments, commits and pull requests cite functional requirements (`FR-nnn`) and platform
constraints (`C-n`) from the project's requirements corpus, which is maintained privately.
[REQUIREMENTS.md](REQUIREMENTS.md) gives the overview and the constraints that shape the design.

> [!NOTE]
> Bundling GPL tools such as `ntfs-3g` or `exfatprogs`, and any userspace exFAT implementation, carry
> licensing and patent implications that are still under review. Open an issue before working in that
> area.

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first: every commit needs a
Developer Certificate of Origin sign-off. Report security issues privately as described in
[SECURITY.md](SECURITY.md), not in public issues.

## License

Copyright (C) 2026 garrymann and contributors.

Removable Storage Manager is free software: you can redistribute it and/or modify it under the terms
of the GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).

This program is distributed in the hope that it will be useful, but **without any warranty**. It reads
and, in future versions, writes raw storage media: back up anything you cannot afford to lose before
using it.
