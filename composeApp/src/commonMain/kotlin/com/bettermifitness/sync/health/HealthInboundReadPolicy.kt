package com.bettermifitness.sync.health

/** Selection only: no averaging, stage inference, source merging, or clock changes. */
internal object HealthInboundReadPolicy {
    private const val APP_PACKAGE = "com.bettermifitness.sync"
    private val miExportPackages = setOf("com.mi.health", "com.xiaomi.wearable")

    fun allowsOrigin(
        origin: HealthRecordOrigin,
        request: HealthInboundReadRequest,
        selfPackage: String,
    ): Boolean {
        if (origin.sourcePackage !in request.sourcePackages) return false
        if (origin.sourcePackage == selfPackage ||
            origin.sourcePackage == APP_PACKAGE ||
            origin.sourcePackage.startsWith("$APP_PACKAGE.") ||
            origin.sourcePackage in miExportPackages ||
            origin.clientRecordId?.startsWith("mifit-") == true
        ) return false
        check(origin.recordId.isNotBlank()) {
            "Health Connect returned a record without a stable ID; importing it could create duplicates."
        }
        return true
    }

    fun heartRate(
        records: List<HealthInboundHeartRateRecord>,
        request: HealthInboundReadRequest,
        selfPackage: String,
    ): List<HealthInboundHeartRateRecord> = latestRevisions(records, { it.origin }) { record ->
            allowsOrigin(record.origin, request, selfPackage) && record.samples.any { request.contains(it.epochMillis) }
        }
        .groupBy { it.origin.stableRecordKey }.values.flatMap { revisions ->
            if (revisions.size > 1) {
                // Preserve conflicting equal-latest revisions for the mapper to
                // reject. Clipping one revision away would silently select the
                // other, including at the selected time window's boundary.
                revisions
            } else revisions.map { record ->
                record.copy(samples = record.samples.filter { request.contains(it.epochMillis) }
                    .sortedBy { it.epochMillis })
            }
        }
        .sortedWith(compareBy<HealthInboundHeartRateRecord> { it.startEpochMillis }
            .thenBy { it.origin.stableRecordKey })

    fun restingHeartRate(
        records: List<HealthInboundRestingHeartRateRecord>,
        request: HealthInboundReadRequest,
        selfPackage: String,
    ): List<HealthInboundRestingHeartRateRecord> = latestRevisions(records, { it.origin }) {
            allowsOrigin(it.origin, request, selfPackage) && request.contains(it.epochMillis)
        }
        .sortedWith(compareBy<HealthInboundRestingHeartRateRecord> { it.epochMillis }
            .thenBy { it.origin.stableRecordKey })

    fun sleep(
        records: List<HealthInboundSleepRecord>,
        request: HealthInboundReadRequest,
        selfPackage: String,
    ): List<HealthInboundSleepRecord> = latestRevisions(records, { it.origin }) { record ->
            allowsOrigin(record.origin, request, selfPackage) &&
                record.startEpochMillis < request.untilEpochMillis &&
                record.endEpochMillis > request.fromEpochMillis
        }
        // A session that overlaps the window is retained in full. Clipping or
        // merging stages here would change its meaning and break record identity.
        .sortedWith(compareBy<HealthInboundSleepRecord> { it.startEpochMillis }
            .thenBy { it.origin.stableRecordKey })

    fun hrv(
        records: List<HealthInboundHrvRecord>,
        request: HealthInboundReadRequest,
        selfPackage: String,
    ): List<HealthInboundHrvRecord> = latestRevisions(records, { it.origin }) {
            allowsOrigin(it.origin, request, selfPackage) && request.contains(it.epochMillis)
        }
        .sortedWith(compareBy<HealthInboundHrvRecord> { it.epochMillis }
            .thenBy { it.origin.stableRecordKey })

    fun oxygenSaturation(
        records: List<HealthInboundOxygenSaturationRecord>,
        request: HealthInboundReadRequest,
        selfPackage: String,
    ): List<HealthInboundOxygenSaturationRecord> = latestRevisions(records, { it.origin }) {
            allowsOrigin(it.origin, request, selfPackage) && request.contains(it.epochMillis)
        }
        .sortedWith(compareBy<HealthInboundOxygenSaturationRecord> { it.epochMillis }
            .thenBy { it.origin.stableRecordKey })

    private fun HealthInboundReadRequest.contains(epochMillis: Long): Boolean =
        epochMillis >= fromEpochMillis && epochMillis < untilEpochMillis

    private fun <T> latestRevisions(
        records: List<T>,
        origin: (T) -> HealthRecordOrigin,
        selected: (T) -> Boolean,
    ): List<T> = records.groupBy { origin(it).stableRecordKey }.values.flatMap { revisions ->
        val newest = revisions.maxWith(compareBy<T> { origin(it).lastModifiedEpochMillis }
            .thenBy { origin(it).clientRecordVersion }).let(origin)
        val tied = revisions.filter {
            origin(it).lastModifiedEpochMillis == newest.lastModifiedEpochMillis &&
                origin(it).clientRecordVersion == newest.clientRecordVersion
        }.distinct()
        // Do not fall back to an old in-window record when the latest revision
        // is outside the query. Equal-latest disagreement must reach the mapper,
        // even if only one of the conflicting versions overlaps the query.
        if (tied.any(selected)) tied else emptyList()
    }
}

internal data class HealthReadPage<T>(val records: List<T>, val nextPageToken: String?)

/** Older HC providers finish with an empty string, not always null. */
internal suspend fun <T> collectHealthReadPages(
    readPage: suspend (String?) -> HealthReadPage<T>,
): List<T> {
    val records = mutableListOf<T>()
    val seenTokens = mutableSetOf<String>()
    var token: String? = null
    do {
        val page = readPage(token)
        records += page.records
        token = page.nextPageToken?.takeIf { it.isNotBlank() }
        if (token != null && !seenTokens.add(token)) {
            throw IllegalStateException("Health Connect returned a repeated pagination token.")
        }
    } while (token != null)
    return records
}
