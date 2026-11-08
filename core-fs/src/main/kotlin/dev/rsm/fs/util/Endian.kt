package dev.rsm.fs.util

/**
 * Little-endian field accessors.
 *
 * Every on-disk structure this project reads — MBR, GPT, FAT BPB, exFAT, NTFS, ext, F2FS — is
 * little-endian, so there is exactly one set of accessors and no byte-order parameter to get wrong.
 *
 * Values are widened deliberately: a 32-bit on-disk field is returned as [Long] via [u32] so a
 * 4 294 967 295-block device does not read back as -1. Anything that needs it narrower must say so.
 */

fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)

fun ByteArray.u32(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)

/**
 * 64-bit little-endian read.
 *
 * Returned as a signed [Long]; a value with the top bit set would be a device of more than 8 EiB and
 * is treated as corrupt by callers rather than handled here.
 */
fun ByteArray.u64(offset: Int): Long {
    var result = 0L
    for (i in 7 downTo 0) {
        result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return result
}

fun ByteArray.putU8(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
}

fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

fun ByteArray.putU32(offset: Int, value: Long) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

fun ByteArray.putU64(offset: Int, value: Long) {
    for (i in 0..7) {
        this[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}

/** Big-endian, needed only for SCSI command descriptor blocks and parameter data. */
fun ByteArray.putBe16(offset: Int, value: Int) {
    this[offset] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 1] = (value and 0xFF).toByte()
}

fun ByteArray.putBe32(offset: Int, value: Long) {
    for (i in 0..3) {
        this[offset + i] = ((value ushr (8 * (3 - i))) and 0xFF).toByte()
    }
}

fun ByteArray.putBe64(offset: Int, value: Long) {
    for (i in 0..7) {
        this[offset + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
    }
}

fun ByteArray.be16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

fun ByteArray.be32(offset: Int): Long {
    var result = 0L
    for (i in 0..3) {
        result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return result
}

fun ByteArray.be64(offset: Int): Long {
    var result = 0L
    for (i in 0..7) {
        result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return result
}

/**
 * Renders bytes as `EB 3C 90 …` for user-facing diagnostics.
 *
 * FR-601 message 2 requires an unrecognised filesystem to be reported by *the magic bytes found* —
 * AC-3 is explicit that "unknown filesystem" is not acceptable. This is what produces that text, so
 * it is a requirement-bearing function, not a debugging convenience.
 */
fun ByteArray.toHexBytes(offset: Int = 0, length: Int = size - offset): String =
    (offset until offset + length).joinToString(" ") { index ->
        val value = this[index].toInt() and 0xFF
        value.toString(16).uppercase().padStart(2, '0')
    }

internal fun checkedIntLength(length: Int, what: String): Int {
    require(length >= 0) { "$what must not be negative, was $length" }
    return length
}
