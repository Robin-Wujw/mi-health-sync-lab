package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.WorkoutRoutePoint
import com.bettermifitness.sync.data.api.WorkoutSession
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKSample
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutBuilder
import platform.HealthKit.HKWorkoutConfiguration
import platform.HealthKit.HKWorkoutRouteBuilder
import platform.HealthKit.HKWorkoutSessionLocationTypeIndoor
import platform.HealthKit.HKWorkoutSessionLocationTypeOutdoor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Imports a single Mi workout into HealthKit (SRP: orchestration only).
 * Sample construction is delegated to [IosWorkoutSampleFactory].
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosWorkoutImporter(
    private val healthStore: HKHealthStore,
    private val revisions: IosHealthRevisions,
    private val samples: IosWorkoutSampleFactory = IosWorkoutSampleFactory(healthStore),
    private val saveObjects: suspend (List<HKSample>) -> Unit,
) {

    suspend fun importAll(sessions: List<WorkoutSession>) {
        val clean = HealthDataNormalizer.normalizeWorkouts(sessions)
        writeAppleWorkouts(clean, ::importWithBuilder, ::importLegacy)
    }

    /**
     * Historical import via [HKWorkoutBuilder] so form metrics associate with the workout
     * (WWDC22 / Apple docs — statistics need builder-collected samples).
     */
    private suspend fun importWithBuilder(session: WorkoutSession): Int {
        val mapping = SportTypeMapper.map(session.activityType)
        val version = WorkoutFormSeries.contentVersion(session, mapping.title, "builder-v3-stride")
        val meta = revisions.metadata(HKObjectType.workoutType(), samples.workoutMetadata(session, version)) ?: return 0
        val core = revisions.prepare(samples.coreSamples(session, version)).filterIsInstance<HKQuantitySample>()
        val form = revisions.prepare(samples.formSamples(session, version)).filterIsInstance<HKQuantitySample>()
        val start = epochDate(session.startTime)
        val end = epochDate(session.endTime)

        val config = HKWorkoutConfiguration()
        config.activityType = mapping.healthKitType.toULong()
        config.locationType = locationType(session.activityType)

        val builder = HKWorkoutBuilder(
            healthStore = healthStore,
            configuration = config,
            device = null,
        )

        var omitted = 0
        try {
            beginCollection(builder, start)
            addMetadata(builder, meta)
            addSamples(builder, core)
            form.groupBy { it.quantityType.identifier }.values.forEach { list ->
                omitted += writeOptionalAppleDetail { addSamples(builder, list) }
            }
            endCollection(builder, end)
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            builder.discardWorkout()
            throw cancelled
        } catch (e: Exception) {
            builder.discardWorkout()
            throw WorkoutNotYetSaved(e)
        }
        val workout = finishWorkout(builder)
        revisions.verifyMetadata(HKObjectType.workoutType(), meta)
        revisions.verify(core)
        if (session.route.size >= 2) {
            omitted += writeOptionalAppleDetail { saveRoute(workout, session.route) }
        }
        return omitted + saveRecoverHr(session, version)
    }

    private suspend fun importLegacy(session: WorkoutSession): Int {
        val mapping = SportTypeMapper.map(session.activityType)
        val version = WorkoutFormSeries.contentVersion(session, mapping.title, "builder-v3-stride")
        val meta = revisions.metadata(HKObjectType.workoutType(), samples.workoutMetadata(session, version)) ?: return 0
        val kcal = HKUnit.unitFromString("kcal")
        val meters = HKUnit.unitFromString("m")
        val workout = HKWorkout.workoutWithActivityType(
            workoutActivityType = mapping.healthKitType.toULong(),
            startDate = epochDate(session.startTime),
            endDate = epochDate(session.endTime),
            workoutEvents = null,
            totalEnergyBurned = session.caloriesKcal?.let { HKQuantity.quantityWithUnit(kcal, it) },
            totalDistance = session.distanceMeters?.let { HKQuantity.quantityWithUnit(meters, it) },
            metadata = meta,
        )
        currentCoroutineContext().ensureActive()
        saveObjects(listOf(workout))
        var omitted = 0
        if (session.route.size >= 2) {
            omitted += writeOptionalAppleDetail { saveRoute(workout, session.route) }
        }
        samples.allSamples(session, version).chunked(200).forEach { chunk ->
            omitted += writeOptionalAppleDetail { saveObjects(revisions.prepare(chunk)) }
        }
        return omitted + saveRecoverHr(session, version)
    }

    private suspend fun saveRecoverHr(session: WorkoutSession, version: Long): Int = writeOptionalAppleDetail {
        val list = samples.recoverHeartRateSamples(session, version)
        if (list.isNotEmpty()) saveObjects(revisions.prepare(list))
    }

    private suspend fun beginCollection(builder: HKWorkoutBuilder, start: NSDate) {
        currentCoroutineContext().ensureActive()
        suspendCoroutine { cont ->
            builder.beginCollectionWithStartDate(start) { ok, error ->
                if (ok) cont.resume(Unit)
                else cont.resumeWithException(
                    Exception(error?.localizedDescription ?: "beginCollection failed"),
                )
            }
        }
    }

    private suspend fun addMetadata(builder: HKWorkoutBuilder, meta: Map<Any?, Any?>) {
        currentCoroutineContext().ensureActive()
        suspendCoroutine { cont ->
            builder.addMetadata(meta) { ok, error ->
                if (ok) cont.resume(Unit)
                else cont.resumeWithException(
                    Exception(error?.localizedDescription ?: "addMetadata failed"),
                )
            }
        }
    }

    private suspend fun addSamples(builder: HKWorkoutBuilder, list: List<HKQuantitySample>) {
        if (list.isEmpty()) return
        list.chunked(100).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            suspendCoroutine { cont ->
                @Suppress("UNCHECKED_CAST")
                builder.addSamples(chunk as List<HKSample>) { ok, error ->
                    if (ok) cont.resume(Unit)
                    else cont.resumeWithException(
                        Exception(error?.localizedDescription ?: "addSamples failed"),
                    )
                }
            }
        }
    }

    private suspend fun endCollection(builder: HKWorkoutBuilder, end: NSDate) {
        currentCoroutineContext().ensureActive()
        suspendCoroutine { cont ->
            builder.endCollectionWithEndDate(end) { ok, error ->
                if (ok) cont.resume(Unit)
                else cont.resumeWithException(
                    Exception(error?.localizedDescription ?: "endCollection failed"),
                )
            }
        }
    }

    private suspend fun finishWorkout(builder: HKWorkoutBuilder): HKWorkout =
        suspendCoroutine { cont ->
            builder.finishWorkoutWithCompletion { finished, error ->
                if (finished != null) cont.resume(finished)
                else cont.resumeWithException(
                    Exception(error?.localizedDescription ?: "finishWorkout failed"),
                )
            }
        }

    private suspend fun saveRoute(workout: HKWorkout, route: List<WorkoutRoutePoint>) {
        val locations = route.map { p ->
            val date = epochDate(p.timeSec)
            CLLocation(
                coordinate = CLLocationCoordinate2DMake(p.latitude, p.longitude),
                altitude = p.altitudeMeters ?: 0.0,
                horizontalAccuracy = p.horizontalAccuracyMeters ?: 10.0,
                verticalAccuracy = if (p.altitudeMeters != null) 10.0 else -1.0,
                timestamp = date,
            )
        }
        val routeBuilder = HKWorkoutRouteBuilder(healthStore = healthStore, device = null)
        suspendCoroutine { cont ->
            routeBuilder.insertRouteData(locations) { success, error ->
                if (!success) {
                    cont.resumeWithException(
                        Exception(error?.localizedDescription ?: "insertRouteData failed"),
                    )
                    return@insertRouteData
                }
                routeBuilder.finishRouteWithWorkout(workout, metadata = null) { savedRoute, finishError ->
                    if (savedRoute != null && finishError == null) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(Exception(finishError?.localizedDescription ?: "Route save not confirmed"))
                    }
                }
            }
        }
    }

    private fun locationType(activityType: String): Long {
        val a = activityType.lowercase()
        return if (
            a.contains("indoor") || a.contains("treadmill") || a.contains("spinning")
        ) {
            HKWorkoutSessionLocationTypeIndoor
        } else {
            HKWorkoutSessionLocationTypeOutdoor
        }
    }

    private fun epochDate(sec: Long): NSDate =
        NSDate.dateWithTimeIntervalSince1970(sec.toDouble())
}
