package dev.rsm.fs.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class DeclaredTypeExpectationsTest {
    @Test
    fun sharedMbrType07_acceptsBothNtfsAndExfatButNotFat32() {
        val declared = DeclaredPartitionType.Mbr(0x07)

        assertFalse(DeclaredTypeExpectations.disagree(declared, known(FilesystemKind.NTFS)))
        assertFalse(DeclaredTypeExpectations.disagree(declared, known(FilesystemKind.EXFAT)))
        assertTrue(DeclaredTypeExpectations.disagree(declared, known(FilesystemKind.FAT32)))
    }

    @Test
    fun unrecognisedFilesystemDoesNotClaimATypeDisagreement() {
        val unrecognised = DetectedFilesystem.Unrecognised(
            magicBytesHex = "00 00 00 00",
            probedAtByteOffset = 0,
        )

        assertFalse(DeclaredTypeExpectations.disagree(DeclaredPartitionType.Mbr(0x0C), unrecognised))
    }

    @Test
    fun partitionEntry_exposesADeclaredAndDetectedMismatch() {
        val entry = PartitionEntry(
            index = 1,
            scheme = PartitionScheme.MBR,
            firstLba = 2048,
            blockCount = 1024,
            logicalBlockSize = 512,
            declaredType = DeclaredPartitionType.Mbr(0x0C),
            detectedFilesystem = known(FilesystemKind.NTFS),
        )

        assertTrue(entry.typesDisagree)
    }

    private fun known(kind: FilesystemKind) = DetectedFilesystem.Known(kind = kind)
}
