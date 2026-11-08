package dev.rsm.storage

import android.os.Environment
import dev.rsm.fs.volume.PlatformVolumeState
import dev.rsm.fs.volume.VolumeAccess
import dev.rsm.fs.volume.VolumeFacts

/**
 * A volume as the app has observed it, paired with the decision made about it.
 *
 * [facts] and [access] are kept separate rather than flattened because they have different origins
 * and different trust levels: facts are observations, access is the conclusion
 * `dev.rsm.fs.volume.VolumeClassifier` drew from them. The diagnostics surface needs to show both,
 * and FR-106's "report the disagreement" style of honesty depends on the observation surviving.
 */
data class DetectedVolume(
    val facts: VolumeFacts,
    val access: VolumeAccess,

    /**
     * Opaque handle the SAF layer needs to build this volume's tree-picker intent (FR-200).
     *
     * `StorageVolume.createOpenDocumentTreeIntent()` is scoped to one volume, and there is no way to
     * reconstruct it from an id, so the handle is carried rather than looked up again later.
     */
    val treeIntentSource: TreeIntentSource? = null,
) {
    val id: String get() = facts.id
    val label: String get() = facts.label

    /**
     * True when this row describes an attached device rather than a volume.
     *
     * The list holds both: `StorageManager` volumes, and mass-storage devices the platform reported
     * no volume for at all (see `VolumeDetector.unreportedDevices` and Q20). Most of the card renders
     * identically for the two, but a handful of strings would otherwise assert a volume exists — so
     * the distinction is named here once rather than re-derived from the state enum at each site.
     */
    val isDeviceWithoutVolume: Boolean
        get() = facts.state == PlatformVolumeState.NOT_REPORTED
}

/** Indirection so the volume model does not name `android.os.storage.StorageVolume` directly. */
fun interface TreeIntentSource {
    fun createOpenDocumentTreeIntent(): android.content.Intent
}

/**
 * Translates the platform's `Environment.MEDIA_*` state strings into the classifier's enum.
 *
 * The strings are the public, documented contract of `StorageVolume.getState()`. Anything not on
 * the list becomes [PlatformVolumeState.UNKNOWN] rather than being guessed at — an unrecognised
 * state is exactly the situation where inventing a meaning risks telling the user something false.
 */
internal fun platformVolumeStateOf(state: String?): PlatformVolumeState = when (state) {
    Environment.MEDIA_MOUNTED -> PlatformVolumeState.MOUNTED
    Environment.MEDIA_MOUNTED_READ_ONLY -> PlatformVolumeState.MOUNTED_READ_ONLY
    Environment.MEDIA_CHECKING -> PlatformVolumeState.CHECKING
    Environment.MEDIA_EJECTING -> PlatformVolumeState.EJECTING
    Environment.MEDIA_UNMOUNTED -> PlatformVolumeState.UNMOUNTED
    Environment.MEDIA_UNMOUNTABLE -> PlatformVolumeState.UNMOUNTABLE
    Environment.MEDIA_NOFS -> PlatformVolumeState.NO_FILESYSTEM
    Environment.MEDIA_BAD_REMOVAL -> PlatformVolumeState.BAD_REMOVAL
    Environment.MEDIA_REMOVED -> PlatformVolumeState.REMOVED
    else -> PlatformVolumeState.UNKNOWN
}
