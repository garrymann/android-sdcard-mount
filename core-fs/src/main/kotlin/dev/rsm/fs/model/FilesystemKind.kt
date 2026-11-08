package dev.rsm.fs.model

/**
 * The filesystems this app can name.
 *
 * FR-107 splits into two obligations and this enum keeps the split visible:
 *  - [Recognition.IDENTIFY] — the nine the app MUST identify.
 *  - [Recognition.LABEL_UNSUPPORTED] — the eight it SHOULD identify *and explicitly label as
 *    unsupported*. Naming one of these is a success, not a failure: it lets the UI say what the
 *    volume is instead of printing bytes.
 *
 * Support for *access* is deliberately absent from this type. Whether a volume can be read or
 * written depends on the access path, not on the filesystem alone — FAT32 is readable through SAF,
 * through the userspace driver, and through a root mount, while NTFS on an internal card without
 * root is readable through none of them (C-1, C-3). That decision belongs to the access layer.
 */
enum class FilesystemKind(
    val displayName: String,
    val recognition: Recognition,
) {
    // FR-107, MUST identify.
    FAT12("FAT12", Recognition.IDENTIFY),
    FAT16("FAT16", Recognition.IDENTIFY),
    FAT32("FAT32", Recognition.IDENTIFY),
    EXFAT("exFAT", Recognition.IDENTIFY),
    NTFS("NTFS", Recognition.IDENTIFY),
    EXT2("ext2", Recognition.IDENTIFY),
    EXT3("ext3", Recognition.IDENTIFY),
    EXT4("ext4", Recognition.IDENTIFY),
    F2FS("F2FS", Recognition.IDENTIFY),

    // FR-107, SHOULD identify and explicitly label unsupported.
    HFS_PLUS("HFS+", Recognition.LABEL_UNSUPPORTED),
    APFS("APFS", Recognition.LABEL_UNSUPPORTED),
    BTRFS("Btrfs", Recognition.LABEL_UNSUPPORTED),
    XFS("XFS", Recognition.LABEL_UNSUPPORTED),
    ZFS("ZFS", Recognition.LABEL_UNSUPPORTED),
    LVM("LVM", Recognition.LABEL_UNSUPPORTED),
    LUKS("LUKS / dm-crypt", Recognition.LABEL_UNSUPPORTED),

    /**
     * An Android adoptable (private) volume — a dm-crypt container keyed to the host device.
     *
     * FR-108 makes this a MUST rather than the SHOULD its neighbours carry, and the obligation is to
     * *refuse to touch it*: any write is data loss because the key lives on the phone that adopted
     * it. FR-904 turns that into an absolute write prohibition and FR-601 message 5 fixes the exact
     * wording the user sees.
     */
    ANDROID_ADOPTABLE("Android adoptable storage", Recognition.LABEL_UNSUPPORTED),
    ;

    /** True for the FAT family, which is the only family with a P0 userspace driver (FR-215). */
    val isFat: Boolean get() = this == FAT12 || this == FAT16 || this == FAT32

    /**
     * True where the volume is encrypted to a device rather than to a user secret.
     *
     * Drives FR-904's refusal and FR-601 message 5's prescribed wording.
     */
    val isForeignEncrypted: Boolean get() = this == LUKS || this == ANDROID_ADOPTABLE

    enum class Recognition { IDENTIFY, LABEL_UNSUPPORTED }
}
