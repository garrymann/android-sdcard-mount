# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

**The project is scaffolded.** `:app` is an Android/Compose app and `:core-fs` is a pure Kotlin/JVM
filesystem core. The initial test suite covers byte/block helpers and the app launch screen. The app is
still at the beginning of the product implementation: the requirements corpus below, not the starter
screen, defines the intended feature set.

**`docs/requirements/` is the source of truth.** Start at
[`docs/requirements/README.md`](docs/requirements/README.md) — it carries the FR→file traceability
table, a section map, and the audit log. `REQUIREMENTS.md` at the root is now only an index and holds
no requirement text; do not add requirements to it.

To find a requirement, look its id up in the README's traceability table. Every requirement has a
stable anchor equal to its id lowercased, so `docs/requirements/41-mounting.md#fr-303e` resolves
directly. Read the whole owning file before implementing, plus
[`01-constraints.md`](docs/requirements/01-constraints.md) if you have not already.

Cite `FR-nnn` and `C-n` ids in commits, PRs, and code comments. Note that a few normative bullets have
no spec id (see `Q16`); the positional anchors the corpus gives them (`fr-601-m1`, `fr-700-b1`, …) are
link targets only and must never be cited as requirement ids.

When something contradicts the spec, raise it rather than silently diverging.
[`96-open-questions.md`](docs/requirements/96-open-questions.md) holds 20 open questions with named
owners — Q1–Q8 from the original risk register, Q9–Q19 recording contradictions found when the spec was
split, and Q20 a gap found on real hardware. Six files are `status: blocked` on a question whose
Blocking column says Yes. Check for a
relevant open question before designing in these areas: the HyperOS SAF-picker risk, the A/B/C/D
userspace driver choice, the GPL/patent boundary, foreground-service caps, and One UI provider
behaviour.

Per-file frontmatter carries `priority`, `status`, `depends-on`, and `constraints-cited`, all **derived
by rule** from the spec rather than hand-written — the rules are in the README. Do not hand-edit a
`priority` to something the spec does not state; if a phase looks wrong, the fix is to add an FR id to
the phase table, not to loosen the field.

## Build and test

The Gradle wrapper is pinned to Gradle 9.6.1; use JDK 17.

- `./gradlew :core-fs:test` runs the fast JVM unit suite.
- `./gradlew :app:assemblePlayDebug` builds the Play debug artifact.
- `./gradlew :app:connectedPlayDebugAndroidTest` runs the Compose UI suite on an Android emulator or
  device.
- `./gradlew check` runs the configured verification tasks, including the Play/root separation guard.

## The constraints that shape the whole design

Most of the architecture follows from AOSP properties that cannot be worked around
([`01-constraints.md`](docs/requirements/01-constraints.md), verified against platform source). The ones
that most often get "designed around" by mistake:

- **An unprivileged app can never open a block device** (`neverallow appdomain dev_type:blk_file`).
  Without root there is *no* path to an internal microSD card the OS has not mounted itself. NTFS on
  an internal card in a stock device is inaccessible — the app must explain this, not retry.
- **An unprivileged app can never run FUSE.** `ntfs-3g`/`fuse-exfat` exist only on the root path. The
  no-root path parses filesystems in userspace over USB bulk transfers, and `openDocument` must use
  `StorageManager.openProxyFileDescriptor()` — the only seekable-fd mechanism without `/dev/fuse`.
- **`vold` mounts only FAT and exFAT on removable volumes**, and has no NTFS implementation on any
  Android version. `sm mount` delegation is therefore unusable for NTFS/ext4/F2FS — permanently.
  `STATE_UNMOUNTABLE` is the primary signal of a filesystem `vold` refused.
- **GKI kernels have no NTFS driver.** Even with root, NTFS means `ntfs-3g` FUSE unless the vendor
  kernel advertises `ntfs3`. Probe `/proc/filesystems`; never assume.
- **Mounts from a plain `su` shell are invisible to other apps** (per-app mount namespaces). Root
  mounts must go in the global namespace (`Shell.FLAG_MOUNT_MASTER` / `su -mm`) at
  `/mnt/user/<userId>/<label>`.
- **Even a correct manual mount is second-class** — invisible to `MediaStore` and `StorageManager`.
  Hence the `DocumentsProvider` is the primary interop surface on *both* paths; the root mount is a
  bonus for shell and legacy path-based clients.
- **Force-claiming a USB device is mutually exclusive with `vold`** and destructive on a live
  filesystem. Raw mode must be explicit and must require ejection first.

Corollary rules that apply everywhere: never branch on OEM name, model, or Android version to infer
filesystem support (probe at runtime, FR-005); never assume the app wins a race against `vold` (it
mounts before `USB_DEVICE_ATTACHED` arrives, FR-720); never show a generic "Mount failed" (FR-602 —
every inaccessible volume needs a specific reason and whether root would help).

## Architecture

Two access paths converge on one interop surface:

```
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
                      + minimal built-in browser (standalone use, and HyperOS fallback)
```

Design consequences worth internalising before writing code:

- Filesystem identification reads superblock/boot-sector magic directly; MBR type bytes and GPT type
  GUIDs are advisory and must be reported when they disagree (FR-106). Partition tables are parsed
  per-device and *every* partition is listed (FR-105).
- Adoptable/private (dm-crypt) volumes are keyed to the host device: detect and refuse to touch
  (FR-108, FR-904).
- Privileged work goes through a narrow `RootService` Binder interface with path canonicalisation —
  not by shelling out and parsing stdout, whose format varies by busybox build and OEM (FR-301c,
  FR-933).
- Data integrity outranks features: refuse read-write rather than risk corruption; round-trip-verify
  the read path before the first write; flush and `SYNCHRONIZE CACHE` before reporting success
  (FR-900–904).

## Build variants and licensing landmines

Single codebase, Gradle `productFlavor` split `play` / `foss`; divergence lives in flavor-specific
source sets, not runtime branches (FR-823). The Play build must not reference root features in
metadata and must gate root code behind a build flag (FR-822).

- `ntfs-3g`, `relan/exfat`, `exfatprogs` are **GPL-2.0**. Linking them into the app's native lib
  puts the whole app under GPL. If shipped, they must be separate executables invoked over
  exec/pipes, and legal review is required first (FR-800).
- A *userspace* exFAT reimplementation carries Microsoft patent exposure that the kernel driver does
  not (FR-803). Using the kernel exFAT driver via a root mount avoids the issue.
- `libaums` (Apache-2.0) is FAT32-only; `libfsntfs` (LGPL-3.0) is read-only. The A/B/C/D userspace
  driver decision (FR-810) is blocking for P1 and must be recorded before FR-215 work starts.
- `libsu` comes from **JitPack, not Maven Central**. On 6.0.0: use `Shell.cmd(...)` (`Shell.sh`/
  `Shell.su` were removed), and call `Shell.setDefaultBuilder(...)` with `FLAG_MOUNT_MASTER` in a
  static initializer before any shell is created (FR-301).

## Targets

`minSdk` 30; `targetSdk` 36+ (Play requires API 36+ from 2026-08-31). Must work on Android 11–17.
`arm64-v8a` required; `armeabi-v7a` and `x86_64` should build. The Tier-1/Tier-2 device matrix is in
[`11-device-matrix.md`](docs/requirements/11-device-matrix.md) and the acceptance suite in
[`92-test-corpus.md`](docs/requirements/92-test-corpus.md) — including a reference filesystem-image
corpus validated by each filesystem's own `fsck` after every write test (FR-920, FR-921).
