package dev.rsm.fs.model

/**
 * Whether root access would change the outcome.
 *
 * FR-601's first sentence requires the UI to state the specific reason **and** whether root would
 * help, and AC-7 says a reason without the root verdict does not satisfy the requirement. Making it
 * a non-nullable field of every reason is what stops that half being forgotten.
 */
enum class RootRelevance {
    /** Root would give access. C-1 and C-3 cases on an unrooted device. */
    ROOT_WOULD_HELP,

    /** Root would not change anything — a hardware fault, a foreign-encrypted volume, a UAS-only
     *  enclosure whose kernel driver is what root would use anyway. */
    ROOT_WOULD_NOT_HELP,

    /** The failure already happened on the root path, so root is not the missing piece. */
    ROOT_ALREADY_IN_USE,
}

/**
 * Why a volume, or an operation on it, is not available.
 *
 * ### Why this is a sealed hierarchy with no message field
 *
 * FR-602: *"The app MUST NOT show a generic 'Mount failed' message anywhere."* It has no exception
 * clause and no scope limiter — the rationale spells out that "anywhere" includes the root path, the
 * raw USB path, `fsck`, and the `DocumentsProvider`'s error surface. AC-8 adds that the five FR-601
 * cases do not exhaust it: any *other* failure needs a specific reason too. The spec names no lint
 * rule or review gate for this and its own notes call it the hardest criterion in the corpus to
 * verify.
 *
 * So it is made a type problem instead. There is no `String message` here and no catch-all subtype.
 * Rendering happens in one exhaustive `when` over these subtypes with no `else` branch
 * (`dev.rsm.ui.messages.ReasonStrings`), so adding a failure mode fails to compile until someone
 * writes its explanation, and `ReasonExhaustivenessTest` asserts every subtype has a mapped string
 * resource. That is stronger than the spec asks for, and it is the only mechanism that survives six
 * months of maintenance.
 *
 * The five reasons FR-601 names explicitly are marked. Two of them — [PlatformCannotMountNoRoot] and
 * [EncryptedForAnotherDevice] — have wording prescribed by the spec as an exact string; the others
 * prescribe content only, and the spec says not to invent canonical strings for them.
 */
sealed interface InaccessibilityReason {

    val rootRelevance: RootRelevance

    // ---------------------------------------------------------------------------------------------
    // FR-601's five required messages
    // ---------------------------------------------------------------------------------------------

    /**
     * FR-601 message 1 — prescribed wording, compared as an exact string by its test.
     *
     * The C-1 case: `neverallow appdomain dev_type:blk_file { read write };` is a compile-time
     * SELinux assertion, so on a stock device there is no path to an internal card the OS has not
     * mounted. `vold` mounts only FAT and exFAT on removable volumes (C-3) and has no NTFS
     * implementation on any Android version, so an NTFS or ext4 card in an internal slot is
     * inaccessible to this app, to every other app, and to the platform.
     *
     * Scoped exactly as the spec scopes it. F2FS is equally impossible but message 1's wording covers
     * "NTFS/ext4 on internal microSD, no root"; other kinds route through
     * [PlatformRefusedFilesystemNoRoot] so the message list is not extended (see A-5 in 60-ui.md).
     */
    data class PlatformCannotMountNoRoot(
        val kind: FilesystemKind,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /**
     * FR-601 message 2 — report the magic bytes found. AC-3: "Not 'unknown filesystem' — the bytes."
     */
    data class FilesystemUnrecognised(
        val magicBytesHex: String,
        val probedAtByteOffset: Long,
    ) : InaccessibilityReason {
        // The bytes are unreadable to *any* driver here, but root opens ntfs-3g, ext4 and F2FS
        // mounts that this app's userspace layer does not implement, so root can still change the
        // outcome for a volume this build cannot parse.
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /**
     * FR-601 message 3 — USB permission denied, and how to re-grant.
     *
     * FR-210 requires denial be handled "without crashing or re-prompting in a loop". Telling the
     * user how to re-grant is what replaces the loop, so this reason exists to be shown once with
     * instructions, not to trigger another request.
     */
    data class UsbPermissionDenied(
        val deviceDescription: String,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /**
     * FR-601 message 4 — the OS has the volume mounted and raw access was requested; prompt to eject.
     *
     * C-7: `claimInterface(force = true)` is the mutual-exclusion mechanism, and force-claiming a
     * mounted volume pulls the block device out from under a live filesystem with dirty pages. FR-212
     * therefore requires the eject first, and FR-720 says finding the volume already mounted is the
     * normal case rather than the exception.
     */
    data class MountedByPlatformRawRequested(
        val volumeDescription: String,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /**
     * FR-601 message 5 — prescribed wording, compared as an exact string by its test.
     *
     * FR-108: an Android adoptable volume is a dm-crypt container keyed to the host device, so any
     * write is data loss. FR-904 makes the write prohibition absolute, including a write the user
     * explicitly requests, and FR-601 AC-9 requires the volume be reported with this wording *rather
     * than by a failed write*.
     */
    data class EncryptedForAnotherDevice(
        val kind: FilesystemKind,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    // ---------------------------------------------------------------------------------------------
    // FR-602 AC-8: every other failure also needs a specific reason
    // ---------------------------------------------------------------------------------------------

    /**
     * `vold` refused this filesystem and the app has no unprivileged path to it, but the kind is
     * outside message 1's prescribed scope (F2FS, ext2/ext3, exFAT on an Android 11–12 device with no
     * vendor `fsck.exfat`).
     */
    data class PlatformRefusedFilesystemNoRoot(
        val kind: FilesystemKind,
        val isInternalSlot: Boolean,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /**
     * The platform reports the volume unmountable and the app could not read the medium to say more.
     *
     * The C-1 wall for a volume whose filesystem is therefore unknown: `STATE_UNMOUNTABLE` is the
     * primary signal of a filesystem `vold` refused (FR-102), but it is *not* evidence of corruption,
     * and the platform's own "Format" offer must not be echoed.
     */
    data object PlatformUnmountableUnknownFilesystem : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /**
     * FR-211d: a UAS-only enclosure with no BOT alternate interface.
     *
     * AC-8 makes silent failure here a defect regardless of any other criterion passing. Root does not
     * add UAS support to this app — with root you get whatever the kernel's `uas` driver already did —
     * so the verdict is ROOT_WOULD_NOT_HELP even though the requirement's own message text
     * ("not supported without root") reads as though it would. Recorded in
     * docs/decisions/0007-uas-message.md.
     */
    data class UasOnlyEnclosure(
        val deviceDescription: String,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /**
     * A mass-storage device the app detected, for which the platform reported no volume at all.
     *
     * Distinct from [PlatformUnmountableUnknownFilesystem], which describes a volume `vold` knows
     * about and refused. Here `vold` produced nothing: no `StorageVolume`, no disk, no block device.
     * The device is enumerated on the USB bus and answers as class 0x08, and the storage stack below
     * it is simply absent.
     *
     * ### Why this exists, and why its message names two causes rather than one
     *
     * Observed on real hardware (see [Q20](../../../../../../docs/requirements/96-open-questions.md)):
     * a third-party userspace driver had force-claimed the reader's interface — `USBDEVFS_DISCONNECT`,
     * the C-7 mechanism — and exited without rebinding `usb-storage`, leaving the interface with no
     * driver until the device is re-enumerated. An empty reader with no card inserted presents
     * identically from where this app stands, and so does a device the platform is still settling.
     *
     * The app cannot tell those apart and must not pretend otherwise. C-1 blocks the block device,
     * and the sysfs driver bindings that would answer the question (`/sys/bus/usb/drivers/usb-storage/`)
     * are SELinux-denied to an app on the no-root path. So the message states the observation — the
     * platform reported no volume for this device — and names the causes it could be, rather than
     * picking one. FR-602's requirement is a *specific* reason, not a confident one; a confidently
     * wrong cause would be the same defect FR-106 rules out for filesystem identification.
     *
     * The remedy that works for every cause is re-enumeration, which is why the message says to
     * reconnect.
     */
    data class MassStorageDeviceNotMounted(
        val deviceDescription: String,
    ) : InaccessibilityReason {
        // FR-104: with root the app reads /proc/partitions and /sys/block directly, so a partition
        // vold never reported is still discoverable — and the driver binding that is unreadable here
        // is readable there. Root does not make an empty reader hold a card, but it changes the
        // outcome in the cases where there is anything to find, which is what this verdict claims.
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /** Root is required for this volume and no root manager is present (FR-300, FR-302). */
    data object RootManagerNotPresent : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /** The user declined the root grant. Not retried in a loop, by the same logic as FR-210. */
    data object RootGrantDeclined : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /**
     * FR-302a: KernelSU moved mounting into pluggable metamodules, so a root manager can be present
     * and working while systemless mounts are unavailable. The requirement is to detect the absence
     * and explain it "rather than failing opaquely".
     */
    data class MountMetamoduleMissing(
        val rootProviderName: String,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_ALREADY_IN_USE
    }

    /**
     * FR-303e: NTFS with a dirty log or hibernation file mounts read-only by default because
     * `ntfs-3g` otherwise refuses or risks corruption. The requirement obliges the app to report
     * *why* it fell back and to offer an explicit override, so both facts are carried here.
     */
    data class NtfsUncleanReadOnly(
        val hasDirtyLog: Boolean,
        val hasHibernationFile: Boolean,
        val overrideAvailable: Boolean = true,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_ALREADY_IN_USE
    }

    /** No NTFS driver is reachable: `/proc/filesystems` has no `ntfs3` and no `ntfs-3g` is installed (C-4). */
    data object NoNtfsDriverAvailable : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_ALREADY_IN_USE
    }

    /**
     * The mount command reported success but the mount point did not stat or list.
     *
     * FR-303f: "A zero exit code from `mount` is not sufficient evidence of success." This reason
     * exists so that the verification failure is reportable as itself rather than as a generic error.
     */
    data class MountVerificationFailed(
        val mountPoint: String,
        val statSucceeded: Boolean,
        val listingSucceeded: Boolean,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_ALREADY_IN_USE
    }

    /**
     * FR-106's disagreement case, escalated to a refusal for writes.
     *
     * Both values are retained and both are shown. A disagreement downgrades the volume to read-only
     * rather than being resolved in favour of either side (FR-900's refuse-rather-than-risk).
     */
    data class DeclaredAndDetectedTypesDisagree(
        val declaredDescription: String,
        val detectedKind: FilesystemKind,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /** FR-901's per-volume read round-trip failed, so no write may follow (FR-900). */
    data class ReadRoundTripFailed(
        val atByteOffset: Long,
        val bytesCompared: Int,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /** The partition table could not be enumerated. */
    data class PartitionTableUnreadable(
        val anomalies: List<PartitionTableAnomaly>,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /** A hardware or transport failure, distinguished by [BlockFailureKind]. */
    data class DeviceFailure(
        val failure: BlockFailureKind,
        val deviceDescription: String,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /**
     * FR-304b: the device went away without an eject. All cached state and all outstanding
     * `DocumentsProvider` URIs are invalidated and the user is warned data may be lost.
     */
    data class RemovedUncleanly(
        val hadPendingWrites: Boolean,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /**
     * FR-710: HyperOS's replacement document picker did not enumerate this app's provider, so SAF is
     * not a usable surface here and the built-in browser is offered instead.
     *
     * Detected at runtime, never inferred from the OEM name (FR-005, FR-710 AC-2).
     */
    data object ProviderNotEnumeratedByPicker : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }

    /**
     * FR-712: writes fail on a volume the platform reports as writable, with a SAF grant held. The
     * remedy surfaced is the `MANAGE_EXTERNAL_STORAGE` route.
     */
    data class SafWriteBlockedDespiteGrant(
        val treeUri: String,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /**
     * The volume must be ejected by the user through the platform, because the app cannot unmount a
     * `vold`-managed volume itself.
     *
     * FR-304a defines eject as sync, close handles, unmount, confirm — but unmounting a public volume
     * needs `MOUNT_UNMOUNT_FILESYSTEMS` or the `@SystemApi` `StorageManager.unmount`, and FR-103 AC-5
     * restricts the no-root path to public APIs. So for platform-managed volumes the app instructs and
     * observes. See docs/decisions/0004-eject-on-unrooted-devices.md.
     */
    data class PlatformEjectRequiresUser(
        val volumeDescription: String,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /** The filesystem is identified and supported, but this build has no driver for it yet (P1+). */
    data class DriverNotImplementedYet(
        val kind: FilesystemKind,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_HELP
    }

    /** FR-107's label-as-unsupported set: identified, and explicitly not supported. */
    data class FilesystemNotSupported(
        val kind: FilesystemKind,
    ) : InaccessibilityReason {
        override val rootRelevance = RootRelevance.ROOT_WOULD_NOT_HELP
    }
}

/**
 * Transport-level failure kinds, mirrored into the model so `:app` can render a reason without
 * depending on the block layer's exception type.
 */
enum class BlockFailureKind {
    MediumError,
    TransportFailure,
    DeviceDetached,
    NotReady,
    OutOfRange,
    WriteRefused,
}
