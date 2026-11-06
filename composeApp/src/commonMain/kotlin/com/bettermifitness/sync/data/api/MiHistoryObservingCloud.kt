package com.bettermifitness.sync.data.api

/**
 * One SpO2 or heart-rate sync operation's evidence, not a persistent cache. A cloud match
 * reconciled just now must not become a missing row in the subsequent bounded
 * precheck. Fresh conflicts remain alongside earlier observations so the
 * verifier can block them. Never use across operations or accounts.
 */
internal class MiHistoryObservingCloud(private val delegate: MiHealthImportCloud) : MiHealthImportCloud {
    private val observedRows = linkedSetOf<MiHealthImportCloudRow>()
    private var guardedTargets = emptySet<MiHealthImportIdentity>()

    /** Reconciliation must receive conflict rows for its combined classification before this guard. */
    fun beginImportPrecheck(targets: List<MiHealthImportRow>) {
        guardedTargets = targets.map { it.identity }.filter {
            it.channel == MiHealthImportChannel.RAW && it.key == "spo2"
        }.toSet()
    }

    override suspend fun readRows(channel: MiHealthImportChannel, key: String,
        fromEpochSeconds: Long, untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
        val fresh = delegate.readRows(channel, key, fromEpochSeconds, untilEpochSeconds)
        if (channel == MiHealthImportChannel.RAW && key in setOf("spo2", "heart_rate")) {
            // Retain broad observations too: a later native match or omitted tombstone must not
            // erase a conflict already seen by reconciliation earlier in this operation.
            observedRows += fresh.filter { it.key == key && it.time >= fromEpochSeconds && it.time < untilEpochSeconds }
            val combined = observedRows.filter { it.key == key && it.time >= fromEpochSeconds && it.time < untilEpochSeconds }
            // The ordinary verifier ignores tombstones/other explicit SIDs.
            // During reconciliation return all evidence; throwing here would hide the conflict
            // from its combined classifier and could let an earlier match clear pending.
            val byTime = combined.groupBy { it.time }
            check(guardedTargets.none { target ->
                target.key == key && byTime[target.time].orEmpty().any { it.deleted || it.sid.isNullOrBlank() || it.sid != target.sid }
            }) { "Cloud observations conflict within this sync operation" }
            return (fresh + combined).distinct()
        }
        return fresh
    }

    override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long,
        maxPages: Int): MiHealthImportHistoryRead = delegate.readRawRowsWithoutStart(key, untilEpochSeconds, maxPages).also {
        if (key in setOf("spo2", "heart_rate")) observedRows += it.rows.filter { row ->
            row.key == key && row.time >= 0 && row.time < untilEpochSeconds
        }
    }

    override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
        // Earlier observations must never verify a write that has not been read back.
        val identities = rows.map { it.identity }.toSet()
        observedRows.removeAll { cloud -> identities.any { it.channel == MiHealthImportChannel.RAW && it.key == cloud.key &&
            (it.time == cloud.time || it.key == "spo2" && it.time.floorDiv(60) == cloud.time.floorDiv(60)) } }
        return delegate.uploadRows(rows, experimentalEnabled)
    }
}
