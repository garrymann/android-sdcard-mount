package dev.rsm.fs.model

/**
 * What reading the boot sector or superblock actually established.
 *
 * FR-106 requires identification to come from magic values read directly, and AC-5 forbids producing
 * an identification from an MBR type byte or GPT type GUID alone. So there is no way to construct a
 * [DetectedFilesystem.Known] from partition metadata: [DeclaredPartitionType] is a separate field on
 * [PartitionEntry] and the two are never merged.
 */
sealed interface DetectedFilesystem {

    /**
     * A positive identification from on-disk structures.
     *
     * "Positive" means full magic *and* internal consistency — for FAT, a self-consistent BPB with a
     * cluster count in range for the claimed variant, not merely `55 AA` at offset 510. The dangerous
     * outcome for FR-904 is not an uncertain answer but a confident wrong one: a card whose MBR says
     * NTFS and whose boot sector is a truncated FAT32 must not be identified as either.
     */
    data class Known(
        val kind: FilesystemKind,
        /** Volume label as stored on the medium, if the format carries one. */
        val label: String? = null,
        /** Volume serial or UUID as stored, if any. Never sent off device (FR-930). */
        val uuid: String? = null,
        val totalBytes: Long? = null,
        val freeBytes: Long? = null,
        /** Structures that were read and agreed, for the diagnostics screen. */
        val evidence: String = "",
    ) : DetectedFilesystem

    /**
     * Nothing matched.
     *
     * [magicBytesHex] is the requirement-bearing field: FR-601 message 2 obliges the UI to report the
     * magic bytes found, and AC-3 says "Not 'unknown filesystem' — the bytes." So the bytes are
     * captured at probe time; they cannot be recovered later once the device is gone.
     */
    data class Unrecognised(
        val magicBytesHex: String,
        /** Where those bytes were read from, so the report is reproducible. */
        val probedAtByteOffset: Long,
    ) : DetectedFilesystem

    /**
     * The medium was never read, and cannot be.
     *
     * This is the C-1 case and it is not a failure of the probe. On a device without root there is no
     * path whatsoever to an internal microSD card the OS has not mounted itself, so there is no
     * boot sector to look at. FR-600 nonetheless asks for a detected filesystem for *every* volume;
     * the honest value is this one, rendered as "—" with the FR-601 message beside it.
     * See docs/decisions/0005-unknowable-volume-attributes.md.
     */
    data object NotProbed : DetectedFilesystem
}

/**
 * The partition table's *claim* about a partition's contents.
 *
 * Advisory only. FR-106 sentence 2 requires this to be retained and reported alongside the detected
 * filesystem whenever the two disagree, which is why it is never folded into one resolved value.
 */
sealed interface DeclaredPartitionType {

    /** MBR partition type byte, e.g. 0x07 for "NTFS/exFAT/HPFS", 0x0C for FAT32 LBA. */
    data class Mbr(val typeByte: Int) : DeclaredPartitionType {
        val hex: String get() = "0x" + typeByte.toString(16).uppercase().padStart(2, '0')
    }

    /** GPT partition type GUID, formatted in the usual mixed-endian display form. */
    data class Gpt(val typeGuid: String, val partitionName: String?) : DeclaredPartitionType

    /** No partition table — the filesystem starts at block zero ("superfloppy"). */
    data object None : DeclaredPartitionType
}

/**
 * The filesystem kinds a declared type is normally used for.
 *
 * Used *only* to decide whether declared and detected disagree. It never produces an identification,
 * which FR-106 AC-5 forbids.
 */
object DeclaredTypeExpectations {

    fun expectedKinds(declared: DeclaredPartitionType): Set<FilesystemKind> = when (declared) {
        is DeclaredPartitionType.None -> emptySet()
        is DeclaredPartitionType.Mbr -> when (declared.typeByte) {
            0x01 -> setOf(FilesystemKind.FAT12)
            0x04, 0x06, 0x0E -> setOf(FilesystemKind.FAT16)
            0x0B, 0x0C -> setOf(FilesystemKind.FAT32)
            // 0x07 is shared by NTFS, exFAT and HPFS; all three are unsurprising here.
            0x07 -> setOf(FilesystemKind.NTFS, FilesystemKind.EXFAT)
            0x83 -> setOf(
                FilesystemKind.EXT2,
                FilesystemKind.EXT3,
                FilesystemKind.EXT4,
                FilesystemKind.F2FS,
                FilesystemKind.BTRFS,
                FilesystemKind.XFS,
            )
            0x8E -> setOf(FilesystemKind.LVM)
            0xAF -> setOf(FilesystemKind.HFS_PLUS)
            else -> emptySet()
        }
        is DeclaredPartitionType.Gpt -> when (declared.typeGuid.uppercase()) {
            "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7" ->
                setOf(FilesystemKind.FAT32, FilesystemKind.FAT16, FilesystemKind.EXFAT, FilesystemKind.NTFS)
            "C12A7328-F81F-11D2-BA4B-00A0C93EC93B" -> setOf(FilesystemKind.FAT32, FilesystemKind.FAT16)
            "0FC63DAF-8483-4772-8E79-3D69D8477DE4" -> setOf(
                FilesystemKind.EXT2,
                FilesystemKind.EXT3,
                FilesystemKind.EXT4,
                FilesystemKind.F2FS,
                FilesystemKind.BTRFS,
                FilesystemKind.XFS,
            )
            "CA7D7CCB-63ED-4C53-861C-1742536059CC" -> setOf(FilesystemKind.LUKS)
            "E6D6D379-F507-44C2-A23C-238F2A3DF928" -> setOf(FilesystemKind.LVM)
            "48465300-0000-11AA-AA11-00306543ECAC" -> setOf(FilesystemKind.HFS_PLUS)
            "7C3457EF-0000-11AA-AA11-00306543ECAC" -> setOf(FilesystemKind.APFS)
            else -> emptySet()
        }
    }

    /**
     * Whether the table's claim and the medium contradict each other.
     *
     * An empty expectation set is *not* a disagreement — an unknown type byte says nothing, and
     * reporting "disagreement" for it would cry wolf on every exotic-but-fine partition.
     */
    fun disagree(declared: DeclaredPartitionType, detected: DetectedFilesystem): Boolean {
        if (detected !is DetectedFilesystem.Known) return false
        val expected = expectedKinds(declared)
        return expected.isNotEmpty() && detected.kind !in expected
    }
}
