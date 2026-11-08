# Removable Storage Manager — Requirements

**Status:** Draft v1
**Last updated:** 2026-07-29
**Applies to:** native Android application (Kotlin + NDK)

> **The requirements live in [`docs/requirements/`](docs/requirements/README.md).**
> This file is an index. It holds no requirement text, so there is exactly one place to edit each
> requirement. Start at [docs/requirements/README.md](docs/requirements/README.md) for the full
> traceability table, the derivation rules behind each file's `priority` and `status`, and the audit
> log.
>
> The corpus is maintained privately and is not part of the public repository, so the links into
> `docs/` below resolve only in a maintainer's checkout. The summary in this file stands on its own.

A native Android app that detects removable storage media, identifies the filesystem on each
partition, makes the contents accessible for read and write, and exposes that access to the rest of
the system where the platform permits it.

Target filesystems: FAT12/16/32, exFAT, NTFS, ext2/3/4, F2FS.

The corpus is **89 top-level `FR-nnn` requirements** (114 including sub-items), **7 platform
constraints** `C-1`..`C-7`, and **20 open questions**, across 32 topic files.

---

## Read this first

Most of the architecture follows from seven properties of AOSP that cannot be worked around. They are
in **[01-constraints.md](docs/requirements/01-constraints.md)**, each with the platform source quoted.
Read it before designing anything — several apparently reasonable designs are ruled out permanently,
and the ones that get "designed around" by mistake are:

- An unprivileged app can **never** open a block device, so without root there is no path to an
  internal microSD card the OS has not mounted itself ([C-1](docs/requirements/01-constraints.md#c-1)).
- An unprivileged app can **never** run FUSE, so `ntfs-3g`/`fuse-exfat` exist only on the root path
  ([C-2](docs/requirements/01-constraints.md#c-2)).
- `vold` mounts only FAT and exFAT on removable volumes and has no NTFS implementation on any Android
  version, so `sm mount` delegation is unusable for NTFS/ext4/F2FS — permanently
  ([C-3](docs/requirements/01-constraints.md#c-3)).
- GKI kernels have no NTFS driver; probe `/proc/filesystems`, never assume
  ([C-4](docs/requirements/01-constraints.md#c-4)).
- Mounts from a plain `su` shell are invisible to other apps
  ([C-5](docs/requirements/01-constraints.md#c-5)).
- Even a correct manual mount is second-class — invisible to `MediaStore` and `StorageManager` — which
  is why the `DocumentsProvider` is the primary interop surface on *both* paths
  ([C-6](docs/requirements/01-constraints.md#c-6)).
- Force-claiming a USB device is mutually exclusive with `vold` and destructive on a live filesystem
  ([C-7](docs/requirements/01-constraints.md#c-7)).

---

## Index

| Topic | File |
|---|---|
| Purpose, scope, non-goals, decisions already made | [00-scope.md](docs/requirements/00-scope.md) |
| **Platform constraints C-1 – C-7** | [01-constraints.md](docs/requirements/01-constraints.md) |
| Capability matrix (the contract) | [02-capability-matrix.md](docs/requirements/02-capability-matrix.md) |
| Platform and compatibility targets · FR-001 – FR-005 | [10-platform-targets.md](docs/requirements/10-platform-targets.md) |
| Reference device matrix (Tier 1 / Tier 2) | [11-device-matrix.md](docs/requirements/11-device-matrix.md) |
| USB attach/detach detection · FR-100, FR-101, FR-109 | [20-usb-detection.md](docs/requirements/20-usb-detection.md) |
| Volume enumeration · FR-102 – FR-104 | [21-volume-enumeration.md](docs/requirements/21-volume-enumeration.md) |
| Partition and filesystem identification · FR-105 – FR-108 | [22-partition-and-fs-identification.md](docs/requirements/22-partition-and-fs-identification.md) |
| SAF delegation · FR-200 – FR-202 | [30-saf-delegation.md](docs/requirements/30-saf-delegation.md) |
| Raw USB transport, BOT/SCSI · FR-210 – FR-214 | [31-usb-transport.md](docs/requirements/31-usb-transport.md) |
| Userspace filesystem layer · FR-215 – FR-217 | [32-userspace-filesystem.md](docs/requirements/32-userspace-filesystem.md) |
| Root detection and privileged execution · FR-300 – FR-302 | [40-root-execution.md](docs/requirements/40-root-execution.md) |
| Mounting · FR-303 | [41-mounting.md](docs/requirements/41-mounting.md) |
| Eject and safe removal · FR-304 | [42-eject-and-removal.md](docs/requirements/42-eject-and-removal.md) |
| `fsck` and boot modules · FR-305, FR-306 | [43-fsck-and-modules.md](docs/requirements/43-fsck-and-modules.md) |
| `DocumentsProvider` · FR-500 – FR-507 | [50-documents-provider.md](docs/requirements/50-documents-provider.md) |
| UI and messaging · FR-600 – FR-603, FR-606, FR-607 | [60-ui.md](docs/requirements/60-ui.md) |
| Lifecycle and foreground services · FR-604, FR-605 | [61-lifecycle-and-fgs.md](docs/requirements/61-lifecycle-and-fgs.md) |
| Samsung / One UI · FR-700 – FR-702 | [70-samsung.md](docs/requirements/70-samsung.md) |
| Xiaomi / HyperOS · FR-710 – FR-712 | [71-xiaomi.md](docs/requirements/71-xiaomi.md) |
| All OEMs · FR-720 – FR-722 | [72-all-oems.md](docs/requirements/72-all-oems.md) |
| Licensing · FR-800 – FR-804 | [80-licensing.md](docs/requirements/80-licensing.md) |
| Userspace driver decision A/B/C/D · FR-810, FR-811 | [81-driver-decision.md](docs/requirements/81-driver-decision.md) |
| Play Store compliance · FR-820 – FR-823 | [82-play-compliance.md](docs/requirements/82-play-compliance.md) |
| Data integrity · FR-900 – FR-904 | [90-data-integrity.md](docs/requirements/90-data-integrity.md) |
| Performance · FR-910 – FR-913 | [91-performance.md](docs/requirements/91-performance.md) |
| Test corpus and acceptance · FR-920 – FR-923 | [92-test-corpus.md](docs/requirements/92-test-corpus.md) |
| Security and privacy · FR-930 – FR-934 | [93-security-privacy.md](docs/requirements/93-security-privacy.md) |
| Permissions inventory · FR-940, FR-941 | [94-permissions.md](docs/requirements/94-permissions.md) |
| Delivery phases P0 – P3 | [95-phases.md](docs/requirements/95-phases.md) |
| **Open questions and risks Q1 – Q20** | [96-open-questions.md](docs/requirements/96-open-questions.md) |
| Sources and epistemic policy | [97-sources.md](docs/requirements/97-sources.md) |

---

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

## Delivery phases

Twelve files are **P0**. See [95-phases.md](docs/requirements/95-phases.md) for the table, the exit
criteria, and the rule by which each file's priority is derived — and note that no file carries P1, P2,
or P3 at top level, because §14 only ever phases slices of a topic beyond P0.

## Status at a glance

Six files are `status: blocked` on a question whose Blocking column says Yes:
[50-documents-provider.md](docs/requirements/50-documents-provider.md) and
[71-xiaomi.md](docs/requirements/71-xiaomi.md) on
[Q1](docs/requirements/96-open-questions.md#q1),
[81-driver-decision.md](docs/requirements/81-driver-decision.md) on
[Q2](docs/requirements/96-open-questions.md#q2),
[80-licensing.md](docs/requirements/80-licensing.md) on
[Q3](docs/requirements/96-open-questions.md#q3),
[61-lifecycle-and-fgs.md](docs/requirements/61-lifecycle-and-fgs.md) on
[Q4](docs/requirements/96-open-questions.md#q4), and
[70-samsung.md](docs/requirements/70-samsung.md) on
[Q5](docs/requirements/96-open-questions.md#q5).

Q9 – Q19 were raised by the 2026-07-29 split audit and record contradictions, unbacked commitments,
and traceability gaps found in this document. None is resolved. If you are about to implement
something in an area one of them touches, read it first.

## Conventions

- Cite `FR-nnn` and `C-n` ids in commits, PRs, and code comments.
- Every requirement has a stable anchor equal to its id lowercased, so
  `docs/requirements/41-mounting.md#fr-303e` always resolves.
- A few normative bullets have **no spec id** ([Q16](docs/requirements/96-open-questions.md#q16)). The
  positional anchors the split gave them (`fr-601-m1`, `fr-700-b1`, …) are link targets only — never
  cite one as a requirement id.
- Inline `*unverified*` and `*Inference:*` hedges are load-bearing. Deleting one silently upgrades a
  guess to a fact.
- When something contradicts the spec, raise it rather than silently diverging.
