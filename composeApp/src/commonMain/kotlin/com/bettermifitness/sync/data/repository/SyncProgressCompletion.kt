package com.bettermifitness.sync.data.repository

/** Once the owning run ends, no metric may still advertise active execution. */
internal fun SyncProgress.finishActiveTasks(message: String): SyncProgress {
    fun finish(state: SyncState): SyncState = if (state is SyncState.InProgress) SyncState.Error(message) else state
    return copy(
        heartRate = finish(heartRate),
        restingHeartRate = finish(restingHeartRate),
        sleep = finish(sleep),
        hrv = finish(hrv),
        steps = finish(steps),
        distance = finish(distance),
        activeCalories = finish(activeCalories),
        spo2 = finish(spo2),
        weight = finish(weight),
        workouts = finish(workouts),
        bloodPressure = finish(bloodPressure),
        temperature = finish(temperature),
        vo2Max = finish(vo2Max),
        heartRateImport = finish(heartRateImport),
        restingHeartRateImport = finish(restingHeartRateImport),
        sleepImport = finish(sleepImport),
        hrvImport = finish(hrvImport),
        spo2Import = finish(spo2Import),
        workoutsImport = finish(workoutsImport),
        vo2MaxImport = finish(vo2MaxImport),
        zeppCloudImport = finish(zeppCloudImport),
    )
}
