package dev.rsm.fs.block

/**
 * A randomly addressable block device.
 *
 * This is the seam that keeps the whole filesystem layer off Android. Three things implement it:
 * a SCSI device over USB bulk transfers (no-root path), a privileged file handle over the root
 * service, and an in-memory or file-backed image (the FR-920 test corpus).
 *
 * Addressing is in logical blocks, not bytes, and LBAs are [Long]:
 *  - FR-211a requires devices larger than 2 TiB to work, which overflows a 32-bit LBA.
 *  - FR-211b requires logical block sizes of both 512 and 4096 bytes, so no code may assume 512.
 */
interface BlockDevice : AutoCloseable {

    /** 512 or 4096 in practice (FR-211b). Never assume; always read this. */
    val logicalBlockSize: Int

    /** Total addressable blocks. `blockCount * logicalBlockSize` can exceed 2^32 (FR-211a). */
    val blockCount: Long

    /**
     * Whether writes are refused outright.
     *
     * This is not merely a hint. FR-900 requires refusing read-write rather than risking
     * corruption, and FR-904 forbids writing to a volume identified as adoptable, encrypted, LUKS,
     * or unrecognised — even when the user explicitly asks. A device that reports `true` here must
     * throw from [writeBlocks] rather than silently dropping the write.
     */
    val isReadOnly: Boolean

    /** Human-meaningful identity for diagnostics. Must not be used for capability decisions. */
    val describe: String

    /**
     * Reads [blocks] logical blocks starting at [firstLba] into [dst] at [dstOffset].
     *
     * @throws BlockDeviceException on transport failure or out-of-range addressing.
     */
    fun readBlocks(firstLba: Long, dst: ByteArray, dstOffset: Int, blocks: Int)

    /**
     * Writes [blocks] logical blocks starting at [firstLba] from [src] at [srcOffset].
     *
     * @throws BlockDeviceException if [isReadOnly], on transport failure, or out-of-range addressing.
     */
    fun writeBlocks(firstLba: Long, src: ByteArray, srcOffset: Int, blocks: Int)

    /**
     * Makes previously written blocks durable on the medium.
     *
     * On the USB path this must issue SYNCHRONIZE CACHE (10) — FR-902 AC-6 requires an actual
     * command on the wire, not a no-op, before success is reported to the user or to a SAF client.
     */
    fun flush()

    val sizeBytes: Long get() = blockCount * logicalBlockSize.toLong()
}

/**
 * Failure of a block-level operation.
 *
 * Carries a [BlockFailure] rather than only a string. FR-602 forbids generic failure messaging
 * anywhere, and a free-form message is how generic messaging gets in: whoever renders this needs
 * enough structure to say something specific.
 */
class BlockDeviceException(
    val failure: BlockFailure,
    val detail: String,
    cause: Throwable? = null,
) : Exception("$failure: $detail", cause)

/** The distinguishable ways a block operation fails. Deliberately closed. */
enum class BlockFailure {
    /** LBA or count outside the device. A bug in the caller, or a truncated image. */
    OutOfRange,

    /** The device reported a medium error for this region. */
    MediumError,

    /** The transport itself failed — cable, stall, timeout, device gone. */
    TransportFailure,

    /** The device disappeared mid-operation. Routes to the FR-304b unclean-removal path. */
    DeviceDetached,

    /** A write was attempted on a device that refuses writes (FR-900, FR-904). */
    WriteRefused,

    /** The device is not ready — no medium in a card reader, spinning up. */
    NotReady,
}
