package dev.rsm.storage

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Enumerates attached USB mass-storage devices (FR-100, second half).
 *
 * The `USB_DEVICE_ATTACHED` intent filter in the manifest fires **only on the attach edge**, so a
 * drive that was already plugged in when the app started produces no intent at all. FR-100's
 * rationale is blunt about it: "A design that registers the receiver and stops there is silently
 * blind to the most common case in testing: the drive was already attached." This class is the
 * other half.
 *
 * It deliberately reports devices, not volumes. There is no public API mapping a `UsbDevice` to the
 * `StorageVolume` that `vold` mounted from it, and inventing one by matching sizes or labels would
 * be a guess presented as a fact. What the count is actually used for is narrower and sound: see
 * [MassStorageSnapshot.anyAttached].
 */
class UsbMassStorageScanner(
    private val usbManager: UsbManager?,
) {

    fun scan(): MassStorageSnapshot {
        val devices = usbManager?.deviceList?.values.orEmpty().filter(::isMassStorage)
        return MassStorageSnapshot(
            devices = devices.map {
                AttachedMassStorage(
                    deviceName = it.deviceName,
                    description = describe(it),
                    hasPermission = usbManager?.hasPermission(it) == true,
                )
            },
        )
    }

    /**
     * True if any interface advertises class 0x08.
     *
     * Class only, matching `res/xml/usb_device_filter.xml`: subclass and protocol are left
     * unconstrained so both BOT (0x50) and UAS (0x62) enclosures are seen. FR-211d requires UAS
     * devices to be *detected* and then either handled or explained — filtering them out here would
     * produce exactly the silent failure its AC-8 calls a defect.
     */
    private fun isMassStorage(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
        }

    private fun describe(device: UsbDevice): String {
        val manufacturer = device.manufacturerName?.trim().orEmpty()
        val product = device.productName?.trim().orEmpty()
        val name = listOf(manufacturer, product).filter { it.isNotEmpty() }.joinToString(" ")
        return name.ifEmpty {
            // Never a bare "Unknown device": FR-602 rules out generic text on every surface, and the
            // ids are what a user can actually match against the hardware in their hand.
            "USB %04X:%04X".format(device.vendorId, device.productId)
        }
    }
}

/** One attached mass-storage device. */
data class AttachedMassStorage(
    val deviceName: String,
    val description: String,
    val hasPermission: Boolean,
)

data class MassStorageSnapshot(
    val devices: List<AttachedMassStorage> = emptyList(),
) {
    /**
     * Whether any mass-storage device is attached at all.
     *
     * This is the one inference drawn from USB state, and it is used only to decide whether a
     * removable volume may safely be *called* an internal microSD card — which matters because
     * FR-601 message 1's wording is prescribed and names the internal card explicitly. With a USB
     * device attached the app cannot tell which volume it backs, so it declines to claim "internal"
     * and routes to the unscoped reason instead. That errs away from showing prescribed wording in a
     * situation it was not written for, which is the safe direction (see A-5 in 60-ui.md).
     *
     * Note this is a runtime observation, not an inference from OEM or Android version (FR-005).
     */
    val anyAttached: Boolean get() = devices.isNotEmpty()
}
