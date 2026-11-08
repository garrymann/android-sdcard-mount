package dev.rsm.fs.volume

import dev.rsm.fs.model.DetectedFilesystem
import dev.rsm.fs.model.InaccessibilityReason

/**
 * How the app reaches a volume's contents.
 *
 * FR-600 requires the main screen to show the access *method* per volume, not merely success or
 * failure, "so a user can tell why two cards behave differently". The three names are the ones the
 * requirement itself uses.
 */
enum class AccessMethod {
    /** FR-200 — the platform mounted it, so access goes through a SAF tree grant. */
    SAF,

    /** FR-211 — the app claimed the USB interface and parses the filesystem itself. */
    RAW_USB,

    /** FR-303 — a mount in the global namespace, performed by the privileged service. */
    ROOT_MOUNT,

    /** No path exists. Always paired with an [InaccessibilityReason]. */
    NONE,
}

/**
 * The platform's own view of a volume, mirrored off `android.os.Environment`'s `MEDIA_*` constants.
 *
 * This exists so the classifier stays in `:core-fs`. The module may not import `android.*`, and the
 * mapping from the platform's `String` states is a one-liner in the `:app` adapter.
 *
 * [UNMOUNTABLE] is the load-bearing member: per C-3, `vold` tries `vfat` then `exfat` and otherwise
 * logs `unsupported filesystem`, leaving the volume here. FR-102 calls it "the primary signal that
 * the app has encountered a filesystem `vold` refused". It is **not** evidence of corruption, and
 * the platform's own "Format" offer must not be echoed.
 */
enum class PlatformVolumeState {
    MOUNTED,
    MOUNTED_READ_ONLY,
    CHECKING,
    EJECTING,
    UNMOUNTED,
    UNMOUNTABLE,

    /** `nofs` — the medium is readable but carries no filesystem `vold` recognises. */
    NO_FILESYSTEM,
    BAD_REMOVAL,
    REMOVED,
    UNKNOWN,

    /**
     * The platform reported nothing at all — there is no `StorageVolume` behind this entry.
     *
     * The one member here that does **not** come from `Environment.MEDIA_*`, because it describes
     * the absence of a platform volume rather than a state one is in. It is reached only for a
     * mass-storage device the app detected on the USB bus while `getStorageVolumes()` returned
     * nothing for it, which is a real and silent failure mode until it is named — see
     * `InaccessibilityReason.MassStorageDeviceNotMounted` and Q20.
     *
     * Kept distinct from [UNKNOWN], which means the platform gave a state string this app does not
     * recognise. Collapsing the two would lose the difference between "vold said something odd" and
     * "vold said nothing".
     */
    NOT_REPORTED,
}

/**
 * Everything the classifier needs about one volume, as plain data.
 *
 * Deliberately a value type with no platform handles: [VolumeClassifier] is then a pure function and
 * every branch of FR-601 is reachable from a JVM unit test, with no emulator and no real card. The
 * `:app` adapters own the job of filling this in from `StorageManager` and `UsbManager`.
 *
 * Nullable [totalBytes] and [freeBytes] are honest rather than lazy: for a volume the app cannot
 * open, the size is genuinely unknowable (C-1), and FR-600 is satisfied by rendering "—".
 */
data class VolumeFacts(
    /** Stable identity for the UI list. `StorageVolume.getUuid()` where present, else the path. */
    val id: String,

    /** Human label — the medium's own label if known, else the platform's description. */
    val label: String,

    val state: PlatformVolumeState,

    /** What reading the medium established. [DetectedFilesystem.NotProbed] on the C-1 path. */
    val detected: DetectedFilesystem = DetectedFilesystem.NotProbed,

    val isRemovable: Boolean = true,

    /**
     * True for a card in a built-in microSD slot, false for anything reached over USB.
     *
     * This scopes FR-601 message 1, whose prescribed wording names the internal card specifically.
     * It is determined by whether a USB mass-storage device backs the volume — never by OEM name or
     * Android version (FR-005).
     */
    val isInternalSlot: Boolean = false,

    val totalBytes: Long? = null,
    val freeBytes: Long? = null,

    /** A persisted SAF tree grant is held and re-validated for this volume (FR-201). */
    val hasPersistedTreeGrant: Boolean = false,

    /** Whether a working privileged path is available in this build and on this device. */
    val rootAvailable: Boolean = false,
)

/**
 * The outcome of classification.
 *
 * [Blocked] carries an [InaccessibilityReason] rather than a string, which is what makes FR-602
 * ("MUST NOT show a generic 'Mount failed' message anywhere") a compile-time property: there is no
 * way to construct a blocked volume without naming why.
 */
sealed interface VolumeAccess {

    /** Contents are reachable now. */
    data class Ready(
        val method: AccessMethod,
        val readOnly: Boolean,
    ) : VolumeAccess

    /** A path exists but the user must grant it first — the SAF tree picker (FR-200). */
    data class NeedsUserGrant(
        val method: AccessMethod,
    ) : VolumeAccess

    /** The platform is still settling. Not a failure, so it carries no reason. */
    data class Busy(
        val state: PlatformVolumeState,
    ) : VolumeAccess

    /** No path exists, and this is why (FR-601, FR-602). */
    data class Blocked(
        val reason: InaccessibilityReason,
    ) : VolumeAccess

    /**
     * The method to display for this outcome (FR-600).
     *
     * Named distinctly from [Ready.method] on purpose: an interface property also called `method`
     * would be what the branches below resolved to, and the getter would recurse until the stack
     * ran out.
     */
    val displayMethod: AccessMethod
        get() = when (this) {
            is Ready -> method
            is NeedsUserGrant -> method
            is Busy -> AccessMethod.NONE
            is Blocked -> AccessMethod.NONE
        }
}
