package dev.rsm.fs.block

import dev.rsm.fs.util.checkedIntLength

/**
 * Byte-addressed helpers over the block-addressed [BlockDevice].
 *
 * Filesystem structures do not sit on block boundaries — a FAT32 FSInfo field is 4 bytes at offset
 * 488 of a sector, an ext superblock magic is 2 bytes at byte offset 1080 of the partition. Rather
 * than let every parser do its own block arithmetic (and get the 4096-byte case wrong), all
 * unaligned access goes through here.
 */

/** Reads [length] bytes from absolute byte [offset]. Handles unaligned start and end. */
fun BlockDevice.readAt(offset: Long, length: Int): ByteArray {
    val out = ByteArray(checkedIntLength(length, "read length"))
    readAt(offset, out, 0, length)
    return out
}

/** Reads [length] bytes from absolute byte [offset] into [dst] at [dstOffset]. */
fun BlockDevice.readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
    require(offset >= 0) { "negative offset $offset" }
    require(length >= 0) { "negative length $length" }
    if (length == 0) return

    val blockSize = logicalBlockSize
    val firstLba = offset / blockSize
    val skew = (offset % blockSize).toInt()
    val blocksNeeded = ((skew + length) + blockSize - 1) / blockSize

    if (firstLba + blocksNeeded > blockCount) {
        throw BlockDeviceException(
            BlockFailure.OutOfRange,
            "read of $length bytes at $offset runs past the end of $describe " +
                "($blockCount blocks of $blockSize bytes)",
        )
    }

    if (skew == 0 && length % blockSize == 0) {
        // Aligned: no staging copy.
        readBlocks(firstLba, dst, dstOffset, length / blockSize)
        return
    }

    val staging = ByteArray(blocksNeeded * blockSize)
    readBlocks(firstLba, staging, 0, blocksNeeded)
    staging.copyInto(dst, dstOffset, skew, skew + length)
}

/**
 * Writes [length] bytes from [src] to absolute byte [offset], read-modify-writing the partial
 * blocks at either end.
 *
 * The read-modify-write is why FAT writes must not be issued byte-at-a-time from arbitrary places:
 * a partial-block write reads the block back first, and a torn sequence of those is how a directory
 * region ends up half-updated. FatWriteOrder owns the ordering; this function only guarantees that
 * one call leaves whole blocks consistent.
 */
fun BlockDevice.writeAt(offset: Long, src: ByteArray, srcOffset: Int, length: Int) {
    require(offset >= 0) { "negative offset $offset" }
    require(length >= 0) { "negative length $length" }
    if (length == 0) return
    if (isReadOnly) {
        throw BlockDeviceException(
            BlockFailure.WriteRefused,
            "write of $length bytes at $offset refused: $describe is read-only",
        )
    }

    val blockSize = logicalBlockSize
    val firstLba = offset / blockSize
    val skew = (offset % blockSize).toInt()
    val blocksNeeded = ((skew + length) + blockSize - 1) / blockSize

    if (firstLba + blocksNeeded > blockCount) {
        throw BlockDeviceException(
            BlockFailure.OutOfRange,
            "write of $length bytes at $offset runs past the end of $describe",
        )
    }

    if (skew == 0 && length % blockSize == 0) {
        writeBlocks(firstLba, src, srcOffset, length / blockSize)
        return
    }

    val staging = ByteArray(blocksNeeded * blockSize)
    readBlocks(firstLba, staging, 0, blocksNeeded)
    src.copyInto(staging, skew, srcOffset, srcOffset + length)
    writeBlocks(firstLba, staging, 0, blocksNeeded)
}

/** Reads one whole logical block. */
fun BlockDevice.readBlock(lba: Long): ByteArray {
    val out = ByteArray(logicalBlockSize)
    readBlocks(lba, out, 0, 1)
    return out
}

/**
 * A window onto part of a device, addressed from zero — how a partition is handed to a filesystem
 * driver without the driver knowing it is inside a partition table.
 *
 * [firstLba] and [blockCount] are in the *parent's* logical blocks, and the window keeps the parent's
 * block size, so a 4096-byte device stays a 4096-byte device inside the partition (FR-211b).
 */
class BlockDeviceWindow(
    private val parent: BlockDevice,
    private val firstLba: Long,
    override val blockCount: Long,
    override val isReadOnly: Boolean = parent.isReadOnly,
    private val label: String = "window",
) : BlockDevice {

    init {
        require(firstLba >= 0) { "negative firstLba $firstLba" }
        require(blockCount >= 0) { "negative blockCount $blockCount" }
        if (firstLba + blockCount > parent.blockCount) {
            throw BlockDeviceException(
                BlockFailure.OutOfRange,
                "window [$firstLba, ${firstLba + blockCount}) exceeds ${parent.describe} " +
                    "(${parent.blockCount} blocks)",
            )
        }
    }

    override val logicalBlockSize: Int get() = parent.logicalBlockSize
    override val describe: String get() = "$label@$firstLba of ${parent.describe}"

    override fun readBlocks(firstLba: Long, dst: ByteArray, dstOffset: Int, blocks: Int) {
        checkRange(firstLba, blocks, "read")
        parent.readBlocks(this.firstLba + firstLba, dst, dstOffset, blocks)
    }

    override fun writeBlocks(firstLba: Long, src: ByteArray, srcOffset: Int, blocks: Int) {
        if (isReadOnly) {
            throw BlockDeviceException(
                BlockFailure.WriteRefused,
                "write refused: $describe is read-only",
            )
        }
        checkRange(firstLba, blocks, "write")
        parent.writeBlocks(this.firstLba + firstLba, src, srcOffset, blocks)
    }

    override fun flush() = parent.flush()

    /** Closing a window does not close the device it is a window onto. */
    override fun close() = Unit

    private fun checkRange(lba: Long, blocks: Int, op: String) {
        if (lba < 0 || blocks < 0 || lba + blocks > blockCount) {
            throw BlockDeviceException(
                BlockFailure.OutOfRange,
                "$op of $blocks blocks at $lba is outside $describe ($blockCount blocks)",
            )
        }
    }
}
