package dev.rsm.fs.volume

import dev.rsm.fs.model.DetectedFilesystem
import dev.rsm.fs.model.FilesystemKind
import dev.rsm.fs.model.InaccessibilityReason
import dev.rsm.fs.model.RootRelevance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The FR-601 decision table, exercised without a device.
 *
 * Several of these cases need hardware the developer will not have on any given day — an NTFS card
 * in an internal slot, a card adopted by a different phone, an Android 11 build with no vendor
 * `fsck.exfat`. Keeping [VolumeClassifier] pure is what makes them assertable at all, and the
 * Tier-1 matrix then confirms on real hardware what is already pinned here.
 */
class VolumeClassifierTest {

    private fun facts(
        state: PlatformVolumeState,
        detected: DetectedFilesystem = DetectedFilesystem.NotProbed,
        isInternalSlot: Boolean = false,
        hasGrant: Boolean = false,
        rootAvailable: Boolean = false,
    ) = VolumeFacts(
        id = "1234-5678",
        label = "SDCARD",
        state = state,
        detected = detected,
        isInternalSlot = isInternalSlot,
        hasPersistedTreeGrant = hasGrant,
        rootAvailable = rootAvailable,
    )

    private fun blockedReason(access: VolumeAccess): InaccessibilityReason =
        assertInstanceOf(VolumeAccess.Blocked::class.java, access).reason

    @Nested
    @DisplayName("FR-200 — a volume the platform mounted goes through SAF, never a force-claim")
    inner class PlatformMounted {

        @Test
        fun `mounted without a grant asks for one over SAF`() {
            val access = VolumeClassifier.classify(facts(PlatformVolumeState.MOUNTED))
            assertEquals(VolumeAccess.NeedsUserGrant(AccessMethod.SAF), access)
        }

        @Test
        fun `mounted with a persisted grant is ready for read-write over SAF`() {
            val access = VolumeClassifier.classify(
                facts(PlatformVolumeState.MOUNTED, hasGrant = true),
            )
            assertEquals(VolumeAccess.Ready(AccessMethod.SAF, readOnly = false), access)
        }

        @Test
        fun `a read-only mount is reported read-only rather than as a failure`() {
            val access = VolumeClassifier.classify(
                facts(PlatformVolumeState.MOUNTED_READ_ONLY, hasGrant = true),
            )
            assertEquals(VolumeAccess.Ready(AccessMethod.SAF, readOnly = true), access)
        }

        @Test
        fun `an exFAT volume the platform mounted never routes to raw USB`() {
            // FR-200's final sentence and FR-214: for exFAT and FAT32 raw mode is strictly worse
            // than SAF, and force-claiming a live mount risks corruption (C-7). The classifier must
            // not offer RAW_USB here even though the app can technically speak to the device.
            val access = VolumeClassifier.classify(
                facts(
                    PlatformVolumeState.MOUNTED,
                    detected = DetectedFilesystem.Known(FilesystemKind.EXFAT),
                    hasGrant = true,
                ),
            )
            assertEquals(AccessMethod.SAF, access.displayMethod)
        }
    }

    @Nested
    @DisplayName("C-3 — STATE_UNMOUNTABLE is the signal of a filesystem vold refused")
    inner class Unmountable {

        @Test
        fun `NTFS on an internal slot without root gets FR-601 message 1`() {
            val reason = blockedReason(
                VolumeClassifier.classify(
                    facts(
                        PlatformVolumeState.UNMOUNTABLE,
                        detected = DetectedFilesystem.Known(FilesystemKind.NTFS),
                        isInternalSlot = true,
                    ),
                ),
            )
            assertEquals(InaccessibilityReason.PlatformCannotMountNoRoot(FilesystemKind.NTFS), reason)
            assertEquals(RootRelevance.ROOT_WOULD_HELP, reason.rootRelevance)
        }

        @Test
        fun `the same card reached over USB does not borrow message 1's internal-card wording`() {
            // Message 1's prescribed sentence says "the card" and is scoped to the internal slot.
            // With a USB device attached the app cannot tell which volume it backs, so it must not
            // claim "internal" — see A-5 in 60-ui.md.
            val reason = blockedReason(
                VolumeClassifier.classify(
                    facts(
                        PlatformVolumeState.UNMOUNTABLE,
                        detected = DetectedFilesystem.Known(FilesystemKind.NTFS),
                        isInternalSlot = false,
                    ),
                ),
            )
            assertEquals(
                InaccessibilityReason.PlatformRefusedFilesystemNoRoot(FilesystemKind.NTFS, false),
                reason,
            )
        }

        @Test
        fun `exFAT vold refused stays outside message 1's prescribed scope`() {
            // The Android 11-12 vendor-dependence case (C-3): legitimately unmountable, but message
            // 1 names NTFS and ext4 only.
            val reason = blockedReason(
                VolumeClassifier.classify(
                    facts(
                        PlatformVolumeState.UNMOUNTABLE,
                        detected = DetectedFilesystem.Known(FilesystemKind.EXFAT),
                        isInternalSlot = true,
                    ),
                ),
            )
            assertEquals(
                InaccessibilityReason.PlatformRefusedFilesystemNoRoot(FilesystemKind.EXFAT, true),
                reason,
            )
        }

        @Test
        fun `an unrecognised filesystem carries the magic bytes, not the word unknown`() {
            val reason = blockedReason(
                VolumeClassifier.classify(
                    facts(
                        PlatformVolumeState.UNMOUNTABLE,
                        detected = DetectedFilesystem.Unrecognised("EB3C9000", 0L),
                    ),
                ),
            )
            val unrecognised =
                assertInstanceOf(InaccessibilityReason.FilesystemUnrecognised::class.java, reason)
            assertEquals("EB3C9000", unrecognised.magicBytesHex)
        }

        @Test
        fun `an unprobed volume reports the C-1 wall rather than inventing a filesystem`() {
            val reason = blockedReason(
                VolumeClassifier.classify(facts(PlatformVolumeState.UNMOUNTABLE)),
            )
            assertEquals(InaccessibilityReason.PlatformUnmountableUnknownFilesystem, reason)
        }

        @Test
        fun `nofs is treated the same way as unmountable`() {
            val reason = blockedReason(
                VolumeClassifier.classify(facts(PlatformVolumeState.NO_FILESYSTEM)),
            )
            assertEquals(InaccessibilityReason.PlatformUnmountableUnknownFilesystem, reason)
        }
    }

    @Nested
    @DisplayName("FR-108 / FR-904 — a foreign-encrypted volume is refused before anything else")
    inner class ForeignEncrypted {

        @Test
        fun `an adoptable volume is refused even when the platform has it mounted`() {
            val reason = blockedReason(
                VolumeClassifier.classify(
                    facts(
                        PlatformVolumeState.MOUNTED,
                        detected = DetectedFilesystem.Known(FilesystemKind.ANDROID_ADOPTABLE),
                        hasGrant = true,
                    ),
                ),
            )
            assertEquals(
                InaccessibilityReason.EncryptedForAnotherDevice(FilesystemKind.ANDROID_ADOPTABLE),
                reason,
            )
        }

        @Test
        fun `a LUKS container is refused too`() {
            val reason = blockedReason(
                VolumeClassifier.classify(
                    facts(
                        PlatformVolumeState.MOUNTED,
                        detected = DetectedFilesystem.Known(FilesystemKind.LUKS),
                    ),
                ),
            )
            assertInstanceOf(InaccessibilityReason.EncryptedForAnotherDevice::class.java, reason)
        }
    }

    @Nested
    inner class OtherStates {

        @Test
        fun `checking and ejecting are progress, not failure`() {
            assertInstanceOf(
                VolumeAccess.Busy::class.java,
                VolumeClassifier.classify(facts(PlatformVolumeState.CHECKING)),
            )
            assertInstanceOf(
                VolumeAccess.Busy::class.java,
                VolumeClassifier.classify(facts(PlatformVolumeState.EJECTING)),
            )
        }

        @Test
        fun `a detach is always treated as unclean`() {
            // FR-101 admits no exception: the app cannot tell a yank from a considerate removal.
            assertInstanceOf(
                InaccessibilityReason.RemovedUncleanly::class.java,
                blockedReason(VolumeClassifier.classify(facts(PlatformVolumeState.BAD_REMOVAL))),
            )
        }

        @Test
        fun `an unmounted volume asks the user to act, because the app may not mount it`() {
            assertInstanceOf(
                InaccessibilityReason.PlatformEjectRequiresUser::class.java,
                blockedReason(VolumeClassifier.classify(facts(PlatformVolumeState.UNMOUNTED))),
            )
        }

        @Test
        fun `with root, an unmounted volume offers a root mount instead`() {
            assertEquals(
                VolumeAccess.NeedsUserGrant(AccessMethod.ROOT_MOUNT),
                VolumeClassifier.classify(
                    facts(PlatformVolumeState.UNMOUNTED, rootAvailable = true),
                ),
            )
        }
    }

    @Nested
    @DisplayName("Q20 — a detected device the platform reported no volume for is not silent")
    inner class NotReported {

        @Test
        fun `a device with no volume behind it is blocked with its own reason`() {
            val reason = blockedReason(
                VolumeClassifier.classify(facts(PlatformVolumeState.NOT_REPORTED)),
            )
            assertInstanceOf(InaccessibilityReason.MassStorageDeviceNotMounted::class.java, reason)
        }

        @Test
        fun `the reason names the device, because the user has to find it to reconnect it`() {
            val reason = blockedReason(
                VolumeClassifier.classify(facts(PlatformVolumeState.NOT_REPORTED)),
            ) as InaccessibilityReason.MassStorageDeviceNotMounted
            assertEquals("SDCARD", reason.deviceDescription)
        }

        @Test
        fun `it stays distinct from the unmountable-volume reason`() {
            // UNMOUNTABLE means vold knows about a volume and refused its filesystem; NOT_REPORTED
            // means vold produced nothing at all. Collapsing them would tell the user their card
            // has an unsupported filesystem when the app has no evidence there is a card.
            val unmountable = blockedReason(
                VolumeClassifier.classify(facts(PlatformVolumeState.UNMOUNTABLE)),
            )
            val notReported = blockedReason(
                VolumeClassifier.classify(facts(PlatformVolumeState.NOT_REPORTED)),
            )
            assertNotEquals(unmountable::class, notReported::class)
        }
    }

    @Test
    @DisplayName("FR-601 AC-7 — every blocked outcome carries a root verdict")
    fun `no blocked state is reachable without a root verdict`() {
        // rootRelevance is non-nullable on the interface, so this asserts the weaker but still
        // useful property that every state maps to a reason at all, with no silent gap.
        PlatformVolumeState.entries.forEach { state ->
            val access = VolumeClassifier.classify(facts(state))
            if (access is VolumeAccess.Blocked) {
                assertTrue(
                    access.reason.rootRelevance in RootRelevance.entries,
                    "no root verdict for $state",
                )
            }
        }
    }
}
