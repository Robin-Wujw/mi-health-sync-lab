package com.bettermifitness.sync.health

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.Foundation.NSCompoundPredicate
import platform.Foundation.NSNumber
import platform.HealthKit.HKCategorySample
import platform.HealthKit.HKCorrelation
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKMetadataKeySyncIdentifier
import platform.HealthKit.HKMetadataKeySyncVersion
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuery
import platform.HealthKit.HKSample
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKSource

@OptIn(ExperimentalForeignApi::class)
internal class IosHealthRevisions(private val store: HKHealthStore) {
    suspend fun metadata(type: HKSampleType, candidate: Map<Any?, Any?>): Map<Any?, Any?>? {
        val id = syncId(candidate)
        val current = read(type, listOf(id))[id]
        return revise(candidate, current)
    }

    suspend fun prepare(samples: List<HKSample>): List<HKSample> {
        val unique = samples.groupBy { it.sampleType.identifier to syncId(checkNotNull(it.metadata)) }.values.map { duplicates ->
            check(duplicates.map { version(checkNotNull(it.metadata)) }.distinct().size == 1) {
                "Conflicting Apple Health revisions in the same batch"
            }
            duplicates.first()
        }
        val existing = readForSamples(unique)
        return unique.mapNotNull { sample ->
            val candidate = checkNotNull(sample.metadata)
            val id = syncId(candidate)
            val own = existing[sample.sampleType.identifier to id]
            val current = if (sample is HKCorrelation) {
                val members = listOfNotNull(own) + sample.objects.filterIsInstance<HKSample>()
                    .mapNotNull { existing[it.sampleType.identifier to id] }
                val largest = members.maxByOrNull { it.version }
                if (largest == own) own else largest?.copy(fingerprint = "unconfirmed-correlation")
            } else own
            val revised = revise(candidate, current) ?: return@mapNotNull null
            when (sample) {
                is HKQuantitySample -> sample.withRevision(revised)
                is HKCategorySample -> HKCategorySample.categorySampleWithType(
                    sample.categoryType, sample.value, sample.startDate, sample.endDate, revised,
                )
                is HKCorrelation -> HKCorrelation.correlationWithType(
                    sample.correlationType, sample.startDate, sample.endDate,
                    sample.objects.filterIsInstance<HKQuantitySample>().map { it.withRevision(revised) }.toSet(), revised,
                )
                else -> error("Unsupported Apple Health sample revision")
            }
        }
    }

    suspend fun verify(samples: List<HKSample>) {
        if (samples.isEmpty()) return
        val observed = readForSamples(samples)
        for (sample in samples) {
            val metadata = checkNotNull(sample.metadata)
            val id = syncId(metadata)
            val actual = observed[sample.sampleType.identifier to id]
            requireVerified(metadata, actual)
        }
    }

    suspend fun verifyMetadata(type: HKSampleType, expected: Map<Any?, Any?>) {
        val id = syncId(expected)
        requireVerified(expected, read(type, listOf(id))[id])
    }

    private fun requireVerified(expected: Map<Any?, Any?>, actual: AppleHealthRevision?) {
        check(actual != null && actual.version == version(expected) && actual.fingerprint == expected[FINGERPRINT]) {
            "Apple Health did not confirm the requested sync revision"
        }
    }

    private suspend fun readForSamples(samples: List<HKSample>): Map<Pair<String, String>, AppleHealthRevision> = buildMap {
        val allSamples = samples.flatMap { if (it is HKCorrelation) listOf(it) + it.objects.filterIsInstance<HKSample>() else listOf(it) }
        for ((type, group) in allSamples.groupBy { it.sampleType }) {
            val ids = group.map { syncId(checkNotNull(it.metadata)) }.distinct()
            for (batch in ids.chunked(200)) {
                read(type, batch).forEach { (id, revision) -> put(type.identifier to id, revision) }
            }
        }
    }

    private suspend fun read(type: HKSampleType, ids: List<String>): Map<String, AppleHealthRevision> {
        currentCoroutineContext().ensureActive()
        val predicate = NSCompoundPredicate.andPredicateWithSubpredicates(listOf(
            HKQuery.predicateForObjectsWithMetadataKey(HKMetadataKeySyncIdentifier, allowedValues = ids),
            HKQuery.predicateForObjectsFromSource(HKSource.defaultSource()),
        ))
        val samples = suspendCoroutine<List<HKSample>> { continuation ->
            val query = HKSampleQuery(type, predicate, 0UL, null) { _, results, error ->
                if (error != null || results == null) {
                    continuation.resumeWithException(IllegalStateException("Could not check existing Apple Health sync revisions"))
                } else {
                    continuation.resume(results.filterIsInstance<HKSample>())
                }
            }
            store.executeQuery(query)
        }
        currentCoroutineContext().ensureActive()
        return samples.groupBy { syncId(checkNotNull(it.metadata)) }.mapValues { (_, matching) ->
            matching.map { sample ->
                val meta = checkNotNull(sample.metadata)
                AppleHealthRevision(version(meta), meta[FINGERPRINT] as? String)
            }.maxBy { it.version }
        }
    }

    private fun revise(candidate: Map<Any?, Any?>, current: AppleHealthRevision?): Map<Any?, Any?>? {
        val fingerprint = version(candidate).toString()
        val revision = nextAppleHealthRevision(current, fingerprint) ?: return null
        return candidate + mapOf(HKMetadataKeySyncVersion to NSNumber.numberWithLongLong(revision), FINGERPRINT to fingerprint)
    }

    private fun HKQuantitySample.withRevision(metadata: Map<Any?, Any?>): HKQuantitySample =
        HKQuantitySample.quantitySampleWithType(quantityType, quantity, startDate, endDate, metadata)

    private fun syncId(metadata: Map<Any?, Any?>): String = metadata[HKMetadataKeySyncIdentifier] as? String
        ?: error("Apple Health sample has no stable sync identifier")

    private fun version(metadata: Map<Any?, Any?>): Long = when (val value = metadata[HKMetadataKeySyncVersion]) {
        is NSNumber -> value.stringValue.toLongOrNull() ?: error("Apple Health sync version is not a signed integer")
        is Long -> value
        is Int -> value.toLong()
        else -> error("Apple Health sample has no exact sync version")
    }
}

private const val FINGERPRINT = "MiFitnessContentFingerprintV1"
