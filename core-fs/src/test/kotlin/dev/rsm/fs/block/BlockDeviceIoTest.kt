package dev.rsm.fs.block

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows

class BlockDeviceIoTest {
    @Test
    fun readAt_spansUnalignedBlocksWithoutAssuming512ByteSectors() {
        val image = ByteArray(8 * 4096) { (it % 251).toByte() }
        val device = InMemoryBlockDevice(image, logicalBlockSize = 4096)

        val result = device.readAt(offset = 4090, length = 20)

        assertArrayEquals(image.copyOfRange(4090, 4110), result)
        assertEquals(listOf(BlockCall(firstLba = 0, blocks = 2)), device.readCalls)
    }

    @Test
    fun writeAt_updatesOnlyRequestedBytesInPartialBlocks() {
        val image = ByteArray(3 * 512) { 0x2A }
        val device = InMemoryBlockDevice(image, logicalBlockSize = 512)
        val payload = ByteArray(20) { (it + 1).toByte() }

        device.writeAt(offset = 500, src = payload, srcOffset = 0, length = payload.size)

        assertArrayEquals(payload, image.copyOfRange(500, 520))
        assertEquals(0x2A.toByte(), image[499])
        assertEquals(0x2A.toByte(), image[520])
        assertEquals(listOf(BlockCall(firstLba = 0, blocks = 2)), device.readCalls)
        assertEquals(listOf(BlockCall(firstLba = 0, blocks = 2)), device.writeCalls)
    }

    @Test
    fun writeAt_refusesReadOnlyDevicesBeforeReadingOrWriting() {
        val device = InMemoryBlockDevice(
            bytes = ByteArray(512),
            logicalBlockSize = 512,
            isReadOnly = true,
        )

        val error = assertThrows<BlockDeviceException> {
            device.writeAt(offset = 0, src = byteArrayOf(1), srcOffset = 0, length = 1)
        }

        assertEquals(BlockFailure.WriteRefused, error.failure)
        assertEquals(emptyList<BlockCall>(), device.readCalls)
        assertEquals(emptyList<BlockCall>(), device.writeCalls)
    }

    @Test
    fun blockDeviceWindow_translatesAddressesAndEnforcesItsOwnRange() {
        val image = ByteArray(5 * 512)
        val device = InMemoryBlockDevice(image, logicalBlockSize = 512)
        val window = BlockDeviceWindow(device, firstLba = 2, blockCount = 2, label = "partition-1")
        val block = ByteArray(512) { 0x5A }

        window.writeBlocks(firstLba = 1, src = block, srcOffset = 0, blocks = 1)

        assertArrayEquals(block, image.copyOfRange(3 * 512, 4 * 512))
        val error = assertThrows<BlockDeviceException> {
            window.readBlocks(firstLba = 2, dst = ByteArray(512), dstOffset = 0, blocks = 1)
        }
        assertEquals(BlockFailure.OutOfRange, error.failure)
    }
}

private data class BlockCall(val firstLba: Long, val blocks: Int)

private class InMemoryBlockDevice(
    private val bytes: ByteArray,
    override val logicalBlockSize: Int,
    override val isReadOnly: Boolean = false,
) : BlockDevice {
    init {
        require(bytes.size % logicalBlockSize == 0)
    }

    override val blockCount: Long = bytes.size.toLong() / logicalBlockSize
    override val describe: String = "in-memory image"
    val readCalls = mutableListOf<BlockCall>()
    val writeCalls = mutableListOf<BlockCall>()

    override fun readBlocks(firstLba: Long, dst: ByteArray, dstOffset: Int, blocks: Int) {
        validateRange(firstLba, blocks)
        readCalls += BlockCall(firstLba, blocks)
        bytes.copyInto(
            destination = dst,
            destinationOffset = dstOffset,
            startIndex = (firstLba * logicalBlockSize).toInt(),
            endIndex = ((firstLba + blocks) * logicalBlockSize).toInt(),
        )
    }

    override fun writeBlocks(firstLba: Long, src: ByteArray, srcOffset: Int, blocks: Int) {
        if (isReadOnly) {
            throw BlockDeviceException(BlockFailure.WriteRefused, "in-memory image is read-only")
        }
        validateRange(firstLba, blocks)
        writeCalls += BlockCall(firstLba, blocks)
        src.copyInto(
            destination = bytes,
            destinationOffset = (firstLba * logicalBlockSize).toInt(),
            startIndex = srcOffset,
            endIndex = srcOffset + blocks * logicalBlockSize,
        )
    }

    override fun flush() = Unit

    override fun close() = Unit

    private fun validateRange(firstLba: Long, blocks: Int) {
        if (firstLba < 0 || blocks < 0 || firstLba + blocks > blockCount) {
            throw BlockDeviceException(BlockFailure.OutOfRange, "range is outside the image")
        }
    }
}
