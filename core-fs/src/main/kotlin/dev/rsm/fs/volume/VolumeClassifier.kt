package dev.rsm.fs.volume

import dev.rsm.fs.model.DetectedFilesystem
import dev.rsm.fs.model.FilesystemKind
import dev.rsm.fs.model.InaccessibilityReason

/**
 * Decides how — or whether — a volume can be reached, from facts alone.
 *
 * ### Why this is pure
 *
 * Every interesting branch here is a case the spec gates a release on, and most of them need a card
 * the developer does not have to hand: an NTFS card in an internal slot (FR-601 message 1), an exFAT
 * volume on an Android 11 device whose vendor shipped no `fsck.exfat` (C-3), a volume adopted by
 * another phone (FR-108). Keeping the decision a function of [VolumeFacts] makes all of them
 * reachable from `core-fs/src/test` with no emulator, which is the only way the FR-601 wording is
 * still correct in six months.
 *
 * The platform adapters in `:app` therefore do no reasoning. They observe, fill in [VolumeFacts],
 * and render whatever comes back.
 *
 * ### The rule that is easy to get wrong
 *
 * FR-005 and FR-720: never infer capability from the OEM, the model, or the Android version, and
 * never assume the app saw the volume before `vold` did. Nothing below reads a version or a brand,
 * and [PlatformVolumeState.MOUNTED] is treated as the *normal* first observation rather than a race
 * the app lost.
 */
object VolumeClassifier {

    /** Kinds whose no-root failure FR-601 message 1 prescribes exact wording for. */
    private val MESSAGE_ONE_KINDS = setOf(FilesystemKind.NTFS, FilesystemKind.EXT4)

    fun classify(facts: VolumeFacts): VolumeAccess {
        // FR-108 / FR-904 first, and before any state check: a volume encrypted to another device
        // must be refused whatever the platform says about it, because the refusal is the point.
        val encryptedKind = foreignEncryptedKind(facts.detected)
        if (encryptedKind != null) {
            return VolumeAccess.Blocked(InaccessibilityReason.EncryptedForAnotherDevice(encryptedKind))
        }

        return when (facts.state) {
            // FR-200: for a volume the platform already mounted, SAF is the default and the app MUST
            // NOT force-claim the device. That holds for exFAT and FAT32 especially — raw mode buys
            // nothing there and costs the possibility of corruption (C-7).
            PlatformVolumeState.MOUNTED ->
                if (facts.hasPersistedTreeGrant) {
                    VolumeAccess.Ready(AccessMethod.SAF, readOnly = false)
                } else {
                    VolumeAccess.NeedsUserGrant(AccessMethod.SAF)
                }

            PlatformVolumeState.MOUNTED_READ_ONLY ->
                if (facts.hasPersistedTreeGrant) {
                    VolumeAccess.Ready(AccessMethod.SAF, readOnly = true)
                } else {
                    VolumeAccess.NeedsUserGrant(AccessMethod.SAF)
                }

            PlatformVolumeState.CHECKING,
            PlatformVolumeState.EJECTING,
            -> VolumeAccess.Busy(facts.state)

            // C-3's signature outcome. vold has no NTFS implementation on any Android version, and
            // its ext4/f2fs back-ends serve adoptable volumes only — so a refused public volume
            // lands here and the reason depends on what, if anything, the app could read.
            PlatformVolumeState.UNMOUNTABLE,
            PlatformVolumeState.NO_FILESYSTEM,
            -> blockedUnmountable(facts)

            // FR-101 / FR-304b: a detach is always treated as unclean.
            PlatformVolumeState.BAD_REMOVAL ->
                VolumeAccess.Blocked(InaccessibilityReason.RemovedUncleanly(hadPendingWrites = false))

            // An unmounted-but-present volume is the eject-first case, not a failure: the app cannot
            // unmount a vold-managed volume itself on the no-root path (FR-103 AC-5), so it asks.
            PlatformVolumeState.UNMOUNTED ->
                if (facts.rootAvailable) {
                    VolumeAccess.NeedsUserGrant(AccessMethod.ROOT_MOUNT)
                } else {
                    VolumeAccess.Blocked(
                        InaccessibilityReason.PlatformEjectRequiresUser(facts.label),
                    )
                }

            PlatformVolumeState.REMOVED ->
                VolumeAccess.Blocked(InaccessibilityReason.RemovedUncleanly(hadPendingWrites = false))

            PlatformVolumeState.UNKNOWN ->
                VolumeAccess.Blocked(InaccessibilityReason.PlatformUnmountableUnknownFilesystem)

            // No StorageVolume exists behind this entry at all — a mass-storage device the app saw
            // on the USB bus and vold never reported. Distinct from UNMOUNTABLE, where vold knows
            // about the volume and refused its filesystem; here the storage stack below the device
            // is absent, so there is nothing whose filesystem could be named. Q20.
            PlatformVolumeState.NOT_REPORTED ->
                VolumeAccess.Blocked(InaccessibilityReason.MassStorageDeviceNotMounted(facts.label))
        }
    }

    /**
     * The refusal branch, split out because it carries FR-601's most-scrutinised message.
     *
     * Three outcomes, in order of how much the app actually knows:
     *  1. the medium was read and named a kind — message 1 if it is in message 1's prescribed scope,
     *     otherwise the same substance without borrowing wording the spec scoped narrowly (A-5);
     *  2. the medium was read and nothing matched — message 2, reporting the bytes, never the phrase
     *     "unknown filesystem" (AC-3);
     *  3. the medium was never read, which is the C-1 wall and the usual case on the no-root path.
     */
    private fun blockedUnmountable(facts: VolumeFacts): VolumeAccess {
        val reason = when (val detected = facts.detected) {
            is DetectedFilesystem.Known ->
                if (facts.isInternalSlot && !facts.rootAvailable && detected.kind in MESSAGE_ONE_KINDS) {
                    InaccessibilityReason.PlatformCannotMountNoRoot(detected.kind)
                } else {
                    InaccessibilityReason.PlatformRefusedFilesystemNoRoot(
                        kind = detected.kind,
                        isInternalSlot = facts.isInternalSlot,
                    )
                }

            is DetectedFilesystem.Unrecognised ->
                InaccessibilityReason.FilesystemUnrecognised(
                    magicBytesHex = detected.magicBytesHex,
                    probedAtByteOffset = detected.probedAtByteOffset,
                )

            DetectedFilesystem.NotProbed ->
                InaccessibilityReason.PlatformUnmountableUnknownFilesystem
        }
        return VolumeAccess.Blocked(reason)
    }

    /** FR-108's refusal test, kept next to the classifier that applies it. */
    private fun foreignEncryptedKind(detected: DetectedFilesystem): FilesystemKind? =
        (detected as? DetectedFilesystem.Known)?.kind?.takeIf { it.isForeignEncrypted }
}
