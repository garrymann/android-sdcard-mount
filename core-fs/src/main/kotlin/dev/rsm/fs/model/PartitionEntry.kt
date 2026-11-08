package dev.rsm.fs.model

/** How the device is partitioned. Hybrid is a real case and FR-920 requires a fixture for it. */
enum class PartitionScheme {
    /** MBR only. */
    MBR,

    /** GPT with a protective MBR — the normal GPT layout. */
    GPT,

    /**
     * A GPT *and* a non-protective MBR describing overlapping content.
     *
     * Reported rather than silently resolved: which table an OS believes depends on the OS, so a
     * hybrid disk is a disagreement in itself and the user should know before writing.
     */
    HYBRID_MBR_GPT,

    /** No table; a filesystem starts at LBA 0. */
    NONE,

    /** A table was present but too damaged to enumerate. */
    UNREADABLE,
}

/**
 * One partition, listed individually.
 *
 * FR-105 is explicit that multi-partition cards and drives MUST NOT be reduced to a single volume,
 * and that partitions beyond the first are included. Every partition the table describes becomes one
 * of these — including ones with no recognisable filesystem, which are the interesting ones for
 * FR-601 message 2.
 */
data class PartitionEntry(
    /** 1-based position within its table, as a user would count them. */
    val index: Int,
    val scheme: PartitionScheme,

    /** Start, in the parent device's logical blocks. */
    val firstLba: Long,
    val blockCount: Long,
    val logicalBlockSize: Int,

    /**
     * What the table claims. Advisory (FR-106).
     */
    val declaredType: DeclaredPartitionType,

    /**
     * What reading the medium established. Authoritative for identification (FR-106).
     */
    val detectedFilesystem: DetectedFilesystem,

    /** GPT partition name, or an MBR-derived description. Not a volume label. */
    val partitionName: String? = null,
) {
    val sizeBytes: Long get() = blockCount * logicalBlockSize.toLong()

    /**
     * True when the table's claim contradicts the medium.
     *
     * FR-106 requires both to be reported in this case, and AC-6 is unusually pointed about it:
     * "Silently preferring the magic is a defect against the second sentence even though it satisfies
     * the first." So this flag exists to be *rendered*, not to pick a winner. WriteGuard also treats
     * it as a reason to withhold write access — a disagreement is not a tie to break.
     */
    val typesDisagree: Boolean
        get() = DeclaredTypeExpectations.disagree(declaredType, detectedFilesystem)

    /** Stable identity for a partition within a device, used to build document ids. */
    val partitionKey: String get() = "p$index@$firstLba"
}

/** The result of parsing a device's partition table. */
data class PartitionTable(
    val scheme: PartitionScheme,
    val partitions: List<PartitionEntry>,
    /**
     * Set when [scheme] is [PartitionScheme.UNREADABLE], or when a table parsed but something in it
     * was inconsistent and the user should be told. Structured, never a bare message (FR-602).
     */
    val anomalies: List<PartitionTableAnomaly> = emptyList(),
)

/** Specific, enumerable things that can be wrong with a partition table. */
sealed interface PartitionTableAnomaly {
    /** GPT header CRC did not match; the backup header may still be usable. */
    data class GptHeaderCrcMismatch(val usedBackup: Boolean) : PartitionTableAnomaly

    /** GPT partition entry array CRC did not match. */
    data object GptEntriesCrcMismatch : PartitionTableAnomaly

    /** Both primary and backup GPT headers were unusable. */
    data object GptBothHeadersUnusable : PartitionTableAnomaly

    /** A GPT is present and the MBR is not merely protective. */
    data object HybridMbrPresent : PartitionTableAnomaly

    /** Two partitions describe overlapping blocks. */
    data class OverlappingPartitions(val firstIndex: Int, val secondIndex: Int) : PartitionTableAnomaly

    /** A partition extends past the end of the device. */
    data class PartitionExceedsDevice(val index: Int) : PartitionTableAnomaly

    /** An MBR extended-partition chain looped or exceeded a sane length. */
    data object ExtendedChainMalformed : PartitionTableAnomaly

    /** No signature found where a table was expected. */
    data object NoRecognisableTable : PartitionTableAnomaly
}
