package dev.rsm.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import dev.rsm.fs.model.DetectedFilesystem
import dev.rsm.fs.volume.PlatformVolumeState
import dev.rsm.fs.volume.VolumeClassifier
import dev.rsm.fs.volume.VolumeFacts

/**
 * The app's single entry point for "what storage is here, and can we reach it?".
 *
 * Composes the two enumeration halves FR-100 and FR-102 require and hands the result to the pure
 * [VolumeClassifier]. It performs no reasoning of its own — that split is what keeps every FR-601
 * branch testable off-device.
 *
 * ### Change notification (FR-103)
 *
 * Two independent signals, because neither covers the other:
 *  * `StorageManager.registerStorageVolumeCallback` — a public API since API 30, which is this app's
 *    `minSdk`. FR-103 AC-5 restricts the no-root path to public `StorageManager` APIs, so the
 *    reflected/`@SystemApi` listeners that older samples reach for are not an option.
 *  * `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED` — a card in an internal slot produces no USB
 *    broadcast at all, and a USB drive's *device* arrival is visible before any volume state
 *    settles, so each signal sees cases the other misses.
 *
 * FR-720 governs the timing: the kernel binds `usb-storage` at enumeration and `vold` mounts
 * *before* `USB_DEVICE_ATTACHED` reaches the app. Nothing here tries to win that race — a rescan
 * observes whatever state the platform has already reached.
 */
class VolumeDetector(
    private val context: Context,
    private val storageManager: StorageManager,
    private val volumeScanner: StorageVolumeScanner,
    private val usbScanner: UsbMassStorageScanner,
    private val grants: SafTreeGrants,
) {

    /**
     * Enumerates everything and classifies it.
     *
     * Ordering puts volumes that need the user's attention first, then removable media, then the
     * emulated primary volume — which is present in the list for completeness but is never what the
     * user opened this app for.
     */
    fun scan(): List<DetectedVolume> {
        val usb = usbScanner.scan()
        val scanned = volumeScanner.scan(usb) { grants.hasGrantFor(it) }

        val platformVolumes = scanned.map { (facts, intentSource) ->
            DetectedVolume(
                facts = facts,
                access = VolumeClassifier.classify(facts),
                treeIntentSource = intentSource,
            )
        }

        val unreported = unreportedDevices(usb, platformVolumes).map { device ->
            val facts = factsForUnreportedDevice(device)
            DetectedVolume(facts = facts, access = VolumeClassifier.classify(facts))
        }

        return (platformVolumes + unreported)
            .sortedWith(
                compareBy(
                    { !it.facts.isRemovable },
                    { it.label },
                ),
            )
    }

    /**
     * Attached mass-storage devices the platform produced no volume for.
     *
     * ### Why the test is a count and not a match
     *
     * There is no public API mapping a `UsbDevice` to the `StorageVolume` `vold` mounted from it —
     * `StorageVolume` exposes a uuid, a description, a directory and a removable flag, and nothing
     * that names a bus, a device or a serial. Matching them by size or label would be a guess
     * presented as a fact, which is the defect [StorageVolumeScanner.readUsage] documents at length
     * and refuses to commit for capacities.
     *
     * So the only attribution this makes is the one that needs no matching: **when the platform
     * reports no removable volume at all, every attached mass-storage device is unaccounted for.**
     * That is arithmetic, not inference, and it is the state a user hits when a reader is connected
     * and nothing appears — the case that produced [Q20](../../../../../../docs/requirements/96-open-questions.md).
     *
     * The mixed case — one reader mounted, a second one not — is deliberately left alone. The count
     * would show that *a* device is unaccounted for, but not *which*, and a row naming the wrong
     * device is worse than the silence it replaces. Q20 is where that gets a decision; do not guess
     * it here.
     */
    private fun unreportedDevices(
        usb: MassStorageSnapshot,
        platformVolumes: List<DetectedVolume>,
    ): List<AttachedMassStorage> =
        if (platformVolumes.any { it.facts.isRemovable }) emptyList() else usb.devices

    /**
     * Facts for a device with no volume behind it.
     *
     * Almost everything is unknown and is left so. The size is not merely unmeasured but unknowable
     * — there is no filesystem to measure — and the filesystem is [DetectedFilesystem.NotProbed]
     * because C-1 puts the medium out of reach, so FR-600's fields render as "—" rather than as
     * zeroes. `isInternalSlot` is false on an observation, not an assumption: the device came from
     * the USB scan.
     */
    private fun factsForUnreportedDevice(device: AttachedMassStorage) = VolumeFacts(
        id = device.deviceName,
        label = device.description,
        state = PlatformVolumeState.NOT_REPORTED,
        detected = DetectedFilesystem.NotProbed,
        isRemovable = true,
        isInternalSlot = false,
    )

    /** Re-validates persisted grants against the volumes actually present (FR-201, on launch). */
    fun revalidateGrants() {
        grants.releaseGrantsNotMatching(storageManager.storageVolumes)
    }

    /**
     * Withdraws the SAF grant the user gave for [volumeId]. Returns false if there was none to drop.
     *
     * Takes an id rather than a `StorageVolume` so the UI layer never holds a platform handle: the
     * volume is re-resolved here, against the volumes present *now*. That also makes the stale case
     * correct by construction — revoking a card that was pulled between render and tap finds no
     * match and reports false, rather than acting on a handle that no longer describes anything.
     */
    fun revokeGrant(volumeId: String): Boolean {
        val volume = storageManager.storageVolumes.firstOrNull { volumeIdOf(it) == volumeId }
            ?: return false
        return grants.releaseGrantFor(volume)
    }

    /**
     * Registers for change notifications until the returned handle is closed.
     *
     * [onChanged] is invoked on the main thread and is expected to trigger a fresh [scan]. It is
     * deliberately a bare signal rather than a diff: the enumeration is cheap next to the 3-second
     * budget FR-109 sets, and a diff would be one more thing that can disagree with reality.
     */
    fun observe(onChanged: () -> Unit): AutoCloseable {
        val volumeCallback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: android.os.storage.StorageVolume) = onChanged()
        }
        storageManager.registerStorageVolumeCallback(context.mainExecutor, volumeCallback)

        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = onChanged()
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            // System broadcasts: the app must not be reachable by other apps through this receiver.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        return AutoCloseable {
            storageManager.unregisterStorageVolumeCallback(volumeCallback)
            runCatching { context.unregisterReceiver(usbReceiver) }
        }
    }

    companion object {
        /** Builds a detector wired to the real platform services. */
        fun create(context: Context): VolumeDetector {
            val appContext = context.applicationContext
            val storageManager = appContext.getSystemService(StorageManager::class.java)
            return VolumeDetector(
                context = appContext,
                storageManager = storageManager,
                volumeScanner = StorageVolumeScanner(appContext, storageManager),
                usbScanner = UsbMassStorageScanner(appContext.getSystemService(UsbManager::class.java)),
                grants = SafTreeGrants(appContext.contentResolver),
            )
        }
    }
}
