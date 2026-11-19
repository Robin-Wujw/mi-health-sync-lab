package com.bettermifitness.sync.lab

import android.content.Context
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One durable, delayed invocation of the production worker after instrumentation has exited. */
internal fun scheduleWorkoutBackgroundAcceptance(context: Context): String {
    val manager = backgroundManager(context)
    val previous = backgroundAcceptanceState(context)
    if (previous != "ABSENT") return "background_acceptance.enqueued=false\nbackground_acceptance.state=$previous"
    val builderType = Class.forName("androidx.work.OneTimeWorkRequest\$Builder")
    val builder = builderType.getConstructor(Class::class.java)
        .newInstance(Class.forName("com.bettermifitness.sync.sync.MiSyncWorker"))
    builderType.getMethod("setInitialDelay", Long::class.javaPrimitiveType, TimeUnit::class.java)
        .invoke(builder, 60L, TimeUnit.SECONDS)
    builderType.getMethod("addTag", String::class.java).invoke(builder, BACKGROUND_ACCEPTANCE_NAME)
    val request = builderType.getMethod("build").invoke(builder)
    val policyType = Class.forName("androidx.work.ExistingWorkPolicy")
    val keep = policyType.enumConstants.single { (it as Enum<*>).name == "KEEP" }
    val operation = Class.forName("androidx.work.WorkManager").getMethod("enqueueUniqueWork", String::class.java,
        policyType, Class.forName("androidx.work.OneTimeWorkRequest"))
        .invoke(manager, BACKGROUND_ACCEPTANCE_NAME, keep, request)
    val future = Class.forName("androidx.work.Operation").getMethod("getResult").invoke(operation) as Future<*>
    future.get(10, TimeUnit.SECONDS)
    return "background_acceptance.enqueued=true\nbackground_acceptance.delay_seconds=60\n" +
        "background_acceptance.state=" + backgroundAcceptanceState(context)
}

internal fun backgroundAcceptanceState(context: Context): String {
    val future = Class.forName("androidx.work.WorkManager").getMethod("getWorkInfosForUniqueWork", String::class.java)
        .invoke(backgroundManager(context), BACKGROUND_ACCEPTANCE_NAME) as Future<*>
    val states = (future.get(10, TimeUnit.SECONDS) as List<*>).filterNotNull().map {
        it.javaClass.getMethod("getState").invoke(it).toString()
    }.distinct().sorted()
    return if (states.isEmpty()) "ABSENT" else states.joinToString(",")
}

internal fun backgroundAcceptanceOutcome(context: Context, key: String = "sync_outcome"): String {
    val future = Class.forName("androidx.work.WorkManager").getMethod("getWorkInfosForUniqueWork", String::class.java)
        .invoke(backgroundManager(context), BACKGROUND_ACCEPTANCE_NAME) as Future<*>
    val values = (future.get(10, TimeUnit.SECONDS) as List<*>).filterNotNull().mapNotNull {
        val output = it.javaClass.getMethod("getOutputData").invoke(it)
        output.javaClass.getMethod("getString", String::class.java).invoke(output, key) as? String
    }.filter { it.matches(Regex("[a-z_]{1,40}")) }.distinct().sorted()
    return if (values.isEmpty()) "NOT_RECORDED" else values.joinToString(",")
}

private fun backgroundManager(context: Context): Any = Class.forName("androidx.work.WorkManager")
    .getMethod("getInstance", Context::class.java).invoke(null, context)

private const val BACKGROUND_ACCEPTANCE_NAME = "healthlab40_background_acceptance_v11"
