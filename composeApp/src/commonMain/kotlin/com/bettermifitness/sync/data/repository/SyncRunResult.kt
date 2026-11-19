package com.bettermifitness.sync.data.repository
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.data.preferences.SyncPreferences


/**
 * Aggregate result of a multi-metric sync pass (each metric fails independently).
 */
data class SyncRunResult(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val totalRecords: Int,
    val hadRetryableFailure: Boolean,
    val hadAuthFailure: Boolean,
    val errorMessages: List<String>,
    val noData: Int = 0,
    val noDataMessages: List<String> = emptyList(),
    val excludedSourcePoints: Int = 0,
    val warningMessages: List<String> = emptyList(),
    val excludedHrvSamples: Int = 0,
) {
    /** A metric can verify useful rows and still have unresolved rows. */
    val hasVerifiedProgress: Boolean get() = succeeded > 0 || totalRecords > 0
    // Excluding unrepresentable heart-rate seconds is the user's selected policy,
    // not an unresolved upload. Keep their count separate from verified records.
    val isFullSuccess: Boolean get() = attempted > 0 && failed == 0 && noData == 0
    val isCompletedWithSkips: Boolean get() = attempted > 0 && failed == 0 &&
        (excludedSourcePoints > 0 || excludedHrvSamples > 0 || (succeeded > 0 && noData > 0))
    val isPartialSuccess: Boolean get() = hasVerifiedProgress && failed > 0
    val isTotalFailure: Boolean get() = attempted > 0 && !hasVerifiedProgress && failed > 0
    val isNoData: Boolean get() = attempted > 0 && succeeded == 0 && failed == 0 && noData > 0
    val isEmpty: Boolean get() = attempted == 0

    fun summary(): String = when {
        isEmpty -> L10n.text(L10n.outcomeNothingToDo)
        isCompletedWithSkips -> L10n.text(L10n.outcomeSuccessDetail)
        isFullSuccess -> L10n.text(L10n.outcomeSuccessDetail)
        isNoData -> L10n.text(L10n.syncNoUsableData)
        isPartialSuccess ->
            L10n.text(L10n.outcomePartialLabel) +
                (errorMessages.firstOrNull() ?: noDataMessages.firstOrNull() ?: warningMessages.firstOrNull())?.let { " — $it" }.orEmpty()
        isTotalFailure ->
            errorMessages.firstOrNull()
                ?: L10n.text(L10n.outcomeCouldNotFinish)
        else -> L10n.text(L10n.outcomeInProgress)
    }

    companion object {
        fun from(
            progress: SyncProgress,
            metricKeys: Collection<String>,
            retryableFlags: List<Boolean>,
            authFailure: Boolean,
        ): SyncRunResult {
            val states = metricKeys.mapNotNull { key -> progress.stateForKey(key) }
            var succeeded = 0
            var failed = 0
            var records = 0
            val errors = mutableListOf<String>()
            val emptyMessages = mutableListOf<String>()
            var excludedPoints = 0
            var excludedHrv = 0
            val warnings = mutableListOf<String>()
            for (state in states) {
                when (state) {
                    is SyncState.Success -> {
                        succeeded++
                        records += state.count
                        val excluded = state.diagnostic?.excludedSourcePoints ?: 0
                        excludedPoints += excluded
                        if (excluded > 0) warnings += L10n.textFmt(L10n.importExcludedPoints, state.count, excluded)
                        val hrvExcluded = state.diagnostic?.excludedHrvSamples ?: 0
                        excludedHrv += hrvExcluded
                        if (hrvExcluded > 0) warnings += L10n.textFmt(L10n.importHrvExcludedSamples, state.count, hrvExcluded)
                    }
                    is SyncState.NoData -> emptyMessages += state.message
                    is SyncState.Error -> {
                        failed++
                        // Keep the task incomplete while preserving verified
                        // progress. Affected/attempted rows are never counted.
                        records += maxOf(state.verifiedCount.coerceAtLeast(0),
                            state.diagnostic?.verifiedRawRows?.coerceAtLeast(0) ?: 0)
                        errors += state.message
                    }
                    else -> Unit
                }
            }
            return SyncRunResult(
                attempted = succeeded + failed + emptyMessages.size,
                succeeded = succeeded,
                failed = failed,
                totalRecords = records,
                hadRetryableFailure = retryableFlags.any { it },
                hadAuthFailure = authFailure,
                errorMessages = errors,
                noData = emptyMessages.size,
                noDataMessages = emptyMessages,
                excludedSourcePoints = excludedPoints,
                warningMessages = warnings,
                excludedHrvSamples = excludedHrv,
            )
        }
    }
}

fun SyncProgress.stateForKey(key: String): SyncState? = when (key) {
    "heart_rate" -> heartRate
    "resting_heart_rate" -> restingHeartRate
    "sleep" -> sleep
    "hrv" -> hrv
    "steps" -> steps
    "distance" -> distance
    "active_calories" -> activeCalories
    "spo2" -> spo2
    "weight" -> weight
    "workouts" -> workouts
    "blood_pressure" -> bloodPressure
    "temperature" -> temperature
    "vo2_max" -> vo2Max
    SyncPreferences.HEART_RATE_IMPORT_KEY -> heartRateImport
    SyncPreferences.RESTING_HEART_RATE_IMPORT_KEY -> restingHeartRateImport
    SyncPreferences.SLEEP_IMPORT_KEY -> sleepImport
    SyncPreferences.HRV_IMPORT_KEY -> hrvImport
    SyncPreferences.SPO2_IMPORT_KEY -> spo2Import
    SyncPreferences.WORKOUTS_IMPORT_KEY -> workoutsImport
    SyncPreferences.VO2_MAX_IMPORT_KEY -> vo2MaxImport
    SyncPreferences.ZEPP_CLOUD_IMPORT_KEY -> zeppCloudImport
    else -> null
}
