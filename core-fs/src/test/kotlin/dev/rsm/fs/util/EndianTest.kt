package dev.rsm.fs.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class EndianTest {
    @Test
    fun littleEndianRoundTrip_preservesUnsignedFieldValues() {
        val bytes = ByteArray(15)

        bytes.putU8(0, 0xFE)
        bytes.putU16(1, 0xBEEF)
        bytes.putU32(3, 0xFEDCBA98)
        bytes.putU64(7, 0x0123456789ABCDEFL)

        assertEquals(0xFE, bytes.u8(0))
        assertEquals(0xBEEF, bytes.u16(1))
        assertEquals(0xFEDCBA98, bytes.u32(3))
        assertEquals(0x0123456789ABCDEFL, bytes.u64(7))
    }

    @Test
    fun bigEndianRoundTrip_preservesScsiSizedFields() {
        val bytes = ByteArray(14)

        bytes.putBe16(0, 0xBEEF)
        bytes.putBe32(2, 0xFEDCBA98)
        bytes.putBe64(6, 0x0123456789ABCDEFL)

        assertEquals(0xBEEF, bytes.be16(0))
        assertEquals(0xFEDCBA98, bytes.be32(2))
        assertEquals(0x0123456789ABCDEFL, bytes.be64(6))
    }

    @Test
    fun toHexBytes_rendersUppercaseSpaceSeparatedDiagnosticBytes() {
        assertEquals("00 AB FF", byteArrayOf(0, 0xAB.toByte(), 0xFF.toByte()).toHexBytes())
    }
}
