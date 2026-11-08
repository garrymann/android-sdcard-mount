package dev.rsm.storage

import android.content.Context
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.os.StatFs
import dev.rsm.fs.model.DetectedFilesystem
import dev.rsm.fs.volume.PlatformVolumeState
import dev.rsm.fs.volume.VolumeFacts
import java.io.File

/**
 * Enumerates platform-managed volumes via `StorageManager.getStorageVolumes()` (FR-102).
 *
 * FR-102 AC-2 is the requirement that shapes this class: every volume the platform reports is
 * surfaced **including** the ones in `STATE_UNMOUNTABLE`, which must be "present in the list, not
 * silently dropped". A filter that keeps only mounted volumes would be the single most natural way
 * to write this and would delete the app's primary diagnostic signal (C-3), so there is no filter
 * here at all beyond the caller's own choice to hide the emulated primary volume.
 *
 * Public `StorageManager` APIs only, per FR-103 AC-5: no hidden, reflected or `@SystemApi` surface.
 */
class StorageVolumeScanner(
    private val context: Context,
    private val storageManager: StorageManager,
) {

    /**
     * @param usb what the USB scan found, used only to decide whether a removable volume may be
     *   described as an internal card — see [MassStorageSnapshot.anyAttached].
     * @param hasPersistedGrant re-validated tree grants, keyed by volume (FR-201).
     */
    fun scan(
        usb: MassStorageSnapshot,
        hasPersistedGrant: (StorageVolume) -> Boolean,
    ): List<Pair<VolumeFacts, TreeIntentSource>> =
        storageManager.storageVolumes.map { volume ->
            factsOf(volume, usb, hasPersistedGrant(volume)) to
                TreeIntentSource { volume.createOpenDocumentTreeIntent() }
        }

    private fun factsOf(
        volume: StorageVolume,
        usb: MassStorageSnapshot,
        hasGrant: Boolean,
    ): VolumeFacts {
        val state = platformVolumeStateOf(volume.state)
        val directory = volume.directory
        val usage = readUsage(volume)

        return VolumeFacts(
            id = volumeIdOf(volume),
            label = volume.getDescription(context) ?: "Storage",
            state = state,
            // The medium itself has not been read: on the no-root path there is no route to the
            // block device (C-1), so the honest value is NotProbed rather than a kind inferred from
            // the fact that vold managed to mount it. FR-106 forbids identification from anything
            // but on-disk structures, and a successful mount is not one.
            detected = DetectedFilesystem.NotProbed,
            isRemovable = volume.isRemovable,
            isInternalSlot = volume.isRemovable && !usb.anyAttached,
            totalBytes = usage?.first,
            freeBytes = usage?.second,
            hasPersistedTreeGrant = hasGrant,
            rootAvailable = false,
        )
    }

    /**
     * Total and free bytes, or null when neither route works.
     *
     * Two routes are tried, because the obvious one fails on exactly the volumes this app is for:
     *
     *  1. `StatFs` on the volume root. Works for the emulated primary volume, and throws for a
     *     removable volume — `/storage/065A-7025` is not readable by an unprivileged app under
     *     scoped storage, so the app that most needs the number is the one that cannot have it.
     *  2. `StatFs` on this app's own directory *on that volume*. `getExternalFilesDirs()` returns a
     *     package-private path on every shared volume, needs no permission at all, and `statvfs`
     *     reports the whole filesystem rather than the subtree — so the totals are the volume's.
     *
     * Null remains a legitimate answer rather than a swallowed error: for a volume the platform
     * never mounted there is no path to stat, the size is genuinely unknowable (C-1), and FR-600 is
     * satisfied by rendering "—".
     */
    /**
     * Total and free bytes, or null when no path on this volume can be stat'd.
     *
     * ### Why this is fussier than it looks
     *
     * Measured on a Galaxy S24 Ultra with an exFAT USB drive attached, the three obvious candidates
     * behave like this:
     *
     * | Path                                            | Volume    | Result                    |
     * |-------------------------------------------------|-----------|---------------------------|
     * | `/storage/emulated/0`                           | primary   | stats correctly           |
     * | `/mnt/media_rw/065A-7025` (its `getDirectory()`) | USB drive | does not exist to the app |
     * | `/storage/065A-7025`                            | USB drive | does not exist to the app |
     *
     * `getDirectory()` hands back the raw `vold` mount, which lives in a mount namespace an
     * unprivileged app cannot see, and `getExternalFilesDirs()` on this device returns a path on the
     * emulated volume *only* — the USB drive gets no app-specific directory at all.
     *
     * The trap is the last one. `/storage/emulated/0/Android/data/…/files` stats perfectly well
     * while the loop is looking at the *USB* volume, so a "first candidate that stats wins" version
     * of this function reports 494 GB of internal storage as though it were the drive's capacity.
     * A confidently wrong number is worse than no number — the same principle FR-106 applies to
     * filesystem identification — so every candidate is checked back against
     * `StorageManager.getStorageVolume()` and discarded unless it really is on this volume.
     *
     * Returning null is therefore a real answer: for a USB volume reachable only through SAF, the
     * capacity is not exposed by any public path-based API, and FR-600 is satisfied by rendering "—"
     * rather than by guessing. Granting all-files access (FR-820) makes the raw path readable and
     * the number appears; FR-202 requires the app to stay fully functional without it, and it does.
     */
    private fun readUsage(volume: StorageVolume): Pair<Long, Long>? =
        candidatePathsFor(volume)
            .firstOrNull { it.isOn(volume) }
            ?.let(::statvfsOf)

    private fun candidatePathsFor(volume: StorageVolume): List<File> = buildList {
        volume.directory?.let(::add)
        addAll(context.getExternalFilesDirs(null).filterNotNull())
    }

    /**
     * Whether [this] path is genuinely on [volume].
     *
     * `getStorageVolume()` is the authoritative public answer, and comparing UUIDs rather than
     * paths is what makes the emulated volume (UUID null) distinguishable from a removable one
     * without string-matching mount points that differ per OEM.
     */
    private fun File.isOn(volume: StorageVolume): Boolean =
        exists() && runCatching {
            storageManager.getStorageVolume(this)?.uuid == volume.uuid
        }.getOrDefault(false)

    private fun statvfsOf(directory: File): Pair<Long, Long>? = runCatching {
        val stat = StatFs(directory.absolutePath)
        stat.blockCountLong * stat.blockSizeLong to stat.availableBlocksLong * stat.blockSizeLong
    }.getOrNull()
}

/** Convenience for the common case where the volume list is wanted without the intent handles. */
internal fun PlatformVolumeState.isUsable(): Boolean =
    this == PlatformVolumeState.MOUNTED || this == PlatformVolumeState.MOUNTED_READ_ONLY

/**
 * The identity the UI lists a volume under, and the key an action names it by.
 *
 * Shared rather than inlined at the two call sites because it is the join between a rendered card
 * and the `StorageVolume` an action has to be applied to: `VolumeDetector.revokeGrant` finds its
 * target by comparing this against the id carried on the card. If the two ever computed the id
 * differently, revoke would silently match nothing.
 *
 * `hashCode()` is the last resort rather than the first: a volume with neither a UUID nor a
 * directory is unidentifiable by any stable property, and an id that changes between scans is still
 * better than two distinct volumes colliding on one.
 */
internal fun volumeIdOf(volume: StorageVolume): String =
    volume.uuid ?: volume.directory?.absolutePath ?: volume.hashCode().toString()
