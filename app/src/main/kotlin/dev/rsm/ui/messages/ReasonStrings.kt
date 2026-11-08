package dev.rsm.ui.messages

import androidx.annotation.StringRes
import dev.rsm.R
import dev.rsm.fs.model.BlockFailureKind
import dev.rsm.fs.model.InaccessibilityReason
import dev.rsm.fs.model.RootRelevance

/**
 * A resolved message: a string resource plus its format arguments.
 *
 * Returning this rather than a `String` keeps [ReasonStrings] free of `Context`, so every branch is
 * assertable in a plain JVM test, and keeps user-visible copy in resources where the repository
 * conventions require it.
 */
data class ReasonMessage(
    @param:StringRes val stringRes: Int,
    val formatArgs: List<Any> = emptyList(),
)

/**
 * Maps every [InaccessibilityReason] to the specific explanation the user sees.
 *
 * ### This file is the enforcement point for FR-602
 *
 * FR-602 — *"The app MUST NOT show a generic 'Mount failed' message anywhere"* — has no exception
 * clause and no scope limiter, and AC-8 extends it past FR-601's five named cases to *any* other
 * failure. The corpus notes this is the hardest criterion it contains to verify, because nothing
 * stops a future maintainer adding a failure path with a hand-written string.
 *
 * The mechanism that makes it hold is structural rather than editorial:
 *  * [InaccessibilityReason] is a sealed interface with no catch-all subtype and no message field;
 *  * the `when` below is exhaustive **with no `else` branch**, so adding a reason stops the app
 *    compiling until someone writes its explanation.
 *
 * Do not add an `else` here. An `else` would convert every future failure mode into whichever
 * generic string it happened to point at, which is precisely the defect FR-602 names.
 */
object ReasonStrings {

    fun messageFor(reason: InaccessibilityReason): ReasonMessage = when (reason) {

        // ---- FR-601's five required messages ----------------------------------------------------

        // Prescribed wording; no arguments, because the spec fixes the sentence exactly.
        is InaccessibilityReason.PlatformCannotMountNoRoot ->
            ReasonMessage(R.string.reason_platform_cannot_mount_no_root)

        is InaccessibilityReason.FilesystemUnrecognised ->
            ReasonMessage(
                R.string.reason_filesystem_unrecognised,
                listOf(reason.magicBytesHex, reason.probedAtByteOffset),
            )

        is InaccessibilityReason.UsbPermissionDenied ->
            ReasonMessage(R.string.reason_usb_permission_denied, listOf(reason.deviceDescription))

        is InaccessibilityReason.MountedByPlatformRawRequested ->
            ReasonMessage(R.string.reason_mounted_raw_requested, listOf(reason.volumeDescription))

        // Prescribed wording; the kind is deliberately not interpolated.
        is InaccessibilityReason.EncryptedForAnotherDevice ->
            ReasonMessage(R.string.reason_encrypted_for_another_device)

        // ---- FR-602 AC-8: everything else still needs a specific reason -------------------------

        is InaccessibilityReason.PlatformRefusedFilesystemNoRoot ->
            ReasonMessage(
                R.string.reason_platform_refused_filesystem,
                listOf(reason.kind.displayName),
            )

        InaccessibilityReason.PlatformUnmountableUnknownFilesystem ->
            ReasonMessage(R.string.reason_platform_unmountable_unknown)

        is InaccessibilityReason.UasOnlyEnclosure ->
            ReasonMessage(R.string.reason_uas_only_enclosure, listOf(reason.deviceDescription))

        is InaccessibilityReason.MassStorageDeviceNotMounted ->
            ReasonMessage(
                R.string.reason_mass_storage_not_mounted,
                listOf(reason.deviceDescription),
            )

        InaccessibilityReason.RootManagerNotPresent ->
            ReasonMessage(R.string.reason_root_manager_not_present)

        InaccessibilityReason.RootGrantDeclined ->
            ReasonMessage(R.string.reason_root_grant_declined)

        is InaccessibilityReason.MountMetamoduleMissing ->
            ReasonMessage(R.string.reason_mount_metamodule_missing, listOf(reason.rootProviderName))

        is InaccessibilityReason.NtfsUncleanReadOnly ->
            ReasonMessage(R.string.reason_ntfs_unclean_read_only)

        InaccessibilityReason.NoNtfsDriverAvailable ->
            ReasonMessage(R.string.reason_no_ntfs_driver)

        is InaccessibilityReason.MountVerificationFailed ->
            ReasonMessage(R.string.reason_mount_verification_failed, listOf(reason.mountPoint))

        is InaccessibilityReason.DeclaredAndDetectedTypesDisagree ->
            ReasonMessage(
                R.string.reason_declared_detected_disagree,
                listOf(reason.declaredDescription, reason.detectedKind.displayName),
            )

        is InaccessibilityReason.ReadRoundTripFailed ->
            ReasonMessage(R.string.reason_read_round_trip_failed, listOf(reason.atByteOffset))

        is InaccessibilityReason.PartitionTableUnreadable ->
            ReasonMessage(
                R.string.reason_partition_table_unreadable,
                listOf(reason.anomalies.size),
            )

        is InaccessibilityReason.DeviceFailure ->
            ReasonMessage(deviceFailureRes(reason.failure), listOf(reason.deviceDescription))

        is InaccessibilityReason.RemovedUncleanly ->
            ReasonMessage(R.string.reason_removed_uncleanly)

        InaccessibilityReason.ProviderNotEnumeratedByPicker ->
            ReasonMessage(R.string.reason_provider_not_enumerated)

        is InaccessibilityReason.SafWriteBlockedDespiteGrant ->
            ReasonMessage(R.string.reason_saf_write_blocked)

        is InaccessibilityReason.PlatformEjectRequiresUser ->
            ReasonMessage(R.string.reason_platform_eject_requires_user)

        is InaccessibilityReason.DriverNotImplementedYet ->
            ReasonMessage(R.string.reason_driver_not_implemented, listOf(reason.kind.displayName))

        is InaccessibilityReason.FilesystemNotSupported ->
            ReasonMessage(R.string.reason_filesystem_not_supported, listOf(reason.kind.displayName))
    }

    /**
     * FR-601 AC-7: "A reason without the root verdict does not satisfy FR-601."
     *
     * Always rendered next to [messageFor]'s output, never conditionally.
     */
    @StringRes
    fun rootVerdictFor(relevance: RootRelevance): Int = when (relevance) {
        RootRelevance.ROOT_WOULD_HELP -> R.string.root_would_help
        RootRelevance.ROOT_WOULD_NOT_HELP -> R.string.root_would_not_help
        RootRelevance.ROOT_ALREADY_IN_USE -> R.string.root_already_in_use
    }

    @StringRes
    private fun deviceFailureRes(failure: BlockFailureKind): Int = when (failure) {
        BlockFailureKind.MediumError -> R.string.reason_device_medium_error
        BlockFailureKind.TransportFailure -> R.string.reason_device_transport_failure
        BlockFailureKind.DeviceDetached -> R.string.reason_device_detached
        BlockFailureKind.NotReady -> R.string.reason_device_not_ready
        BlockFailureKind.OutOfRange -> R.string.reason_device_out_of_range
        BlockFailureKind.WriteRefused -> R.string.reason_device_write_refused
    }
}
