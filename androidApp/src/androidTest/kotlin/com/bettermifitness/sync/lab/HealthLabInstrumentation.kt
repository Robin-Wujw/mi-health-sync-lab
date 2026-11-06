package com.bettermifitness.sync.lab

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import com.bettermifitness.sync.MainActivity
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.repository.HealthRepository
import com.bettermifitness.sync.data.repository.copyableText
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundReadUnavailableException
import com.bettermifitness.sync.sync.LiveTestSessionGate
import com.mifitness.miclient.api.MiApiException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.mp.KoinPlatform

/**
 * Explicit ADB harness, packaged only in the separate same-signer androidTest APK.
 *
 * It uses the target application's existing account, Health Connect permission,
 * and source allowlist. No shell permission adoption, root, credential export,
 * arbitrary payload/URL input, or production exported test component is used.
 * The default is read-only health-data inspection. Live writes require both
 * an explicit write mode and allowWrites=true. sync-one uses the bounded normal
 * runner; recover-one-pending considers one existing SpO2 pending target only.
 * reconcile-pending reads the cloud and resolves only matched local pending
 * entries. It never uploads health data and does not require the write flag.
 */
class HealthLabInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private var foregroundActivity: Activity? = null

    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application {
        // Must precede target Application.onCreate and any foreground auto-sync.
        LiveTestSessionGate.activateForInstrumentation()
        return super.newApplication(cl, className, context)
    }

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments?.let(::Bundle) ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = mutableListOf("Mi Health Sync Lab live test v1")
        var completed = false
        try {
            check(LiveTestSessionGate.isActive)
            val options = parseOptions()
            val version = targetVersion()
            report += "runner_mode=${options.mode}"
            report += "selected_metric=${options.metric.metricKey}"
            report += "window_days=${options.rangeDays}"
            report += "health_data_writes_requested=${options.mode in WRITE_MODES}"
            val readOnlyModes = setOf(
                MODE_INSPECT, MODE_INSPECT_MINUTE, MODE_INSPECT_HEART, MODE_PREVIEW_HEART, MODE_INSPECT_HEART_COVERAGE, MODE_INSPECT_CHAIN,
            )
            report += "local_journal_changes_requested=${options.mode !in readOnlyModes}"
            report += "normal_sync_suppressed=true"

            val keyguard = targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguard.isKeyguardLocked) throw LiveTestFailure("DEVICE_LOCKED")
            if (HealthConnectClient.getSdkStatus(targetContext) != HealthConnectClient.SDK_AVAILABLE) {
                throw LiveTestFailure("HEALTH_CONNECT_UNAVAILABLE")
            }

            // HC foreground access uses the real target Activity and the user's
            // existing grants; this harness never grants permissions itself.
            // Android 17 can defer startActivitySync indefinitely under background
            // launch restrictions. The test-only shell launch uses the same explicit
            // Activity after the synchronization gate is active; no permission is adopted.
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
                    "am start -n ${targetContext.packageName}/${MainActivity::class.java.name}",
                )).bufferedReader().use { it.readText() } // Discard shell output.
                foregroundActivity = monitor.waitForActivityWithTimeout(30_000L)
                    ?: throw LiveTestFailure("FOREGROUND_START_TIMEOUT")
            } finally {
                removeMonitor(monitor)
            }
            runOnMainSync {
                foregroundActivity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            waitForIdleSync()

            val body = runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    val healthClient = HealthConnectClient.getOrCreate(targetContext)
                    report += "history_read_available=" + (healthClient.features.getFeatureStatus(
                        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE)
                    report += "history_read_granted=" + (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in
                        healthClient.permissionController.getGrantedPermissions())
                    val koin = KoinPlatform.getKoin()
                    if (options.mode != MODE_INSPECT_CHAIN && !koin.get<MiSessionManager>().ensureActive())
                        throw LiveTestFailure("NOT_LOGGED_IN")
                    val repository = koin.get<HealthRepository>()
                    val until = Clock.System.now()
                    val from = until.minus(options.rangeDays.days)
                    when (options.mode) {
                        MODE_INSPECT_CHAIN -> inspectHealthConnectChain(targetContext)
                        MODE_INSPECT_MINUTE -> inspectSpO2Minute(koin.get(), koin.get(), koin.get(), from, until)
                        MODE_VERIFY_EXISTING -> verifyExistingSpO2(koin.get(), koin.get(), koin.get(), from, until, version)
                        MODE_INSPECT_HEART -> inspectHeartHistory(koin.get(), koin.get(), koin.get(), from, until)
                        MODE_PREVIEW_HEART -> previewHeartRecovery(koin.get(), koin.get(), koin.get(), from, until, version)
                        MODE_INSPECT_HEART_COVERAGE -> inspectHeartCoverage(koin.get(), koin.get(), koin.get(), from, until)
                        MODE_RECONCILE_HEART -> reconcileHeartHistory(koin.get(), koin.get(), koin.get(), from, until, version)
                        MODE_VERIFY_HEART ->
                            verifyExistingSpO2(koin.get(), koin.get(), koin.get(), from, until, version, HealthInboundMetric.HEART_RATE)
                        MODE_INSPECT -> repository.inspectInboundForLiveTest(
                            options.metric, from.toString(), until.toString(),
                        ).copyableText(version)
                        MODE_SYNC_ONE -> repository.runInboundForLiveTest(
                            options.metric, from.toString(), until.toString(), singleRecord = true,
                        ).copyableText(version)
                        MODE_SYNC_ONE_DAY -> repository.runOneDayInboundForLiveTest(
                            options.metric, from.toString(), until.toString(),
                        ).copyableText(version)
                        MODE_REPAIR_ONE -> repository.repairOneImportForLiveTest(
                            options.metric, from.toString(), until.toString(),
                        ).copyableText(version)
                        MODE_RECOVER_ONE_PENDING -> repository.recoverPendingSpO2ForLiveTest(
                            from.toString(), until.toString(),
                        ).copyableText(version)
                        MODE_RECONCILE_PENDING -> repository.reconcilePendingSpO2ForLiveTest(
                            from.toString(), until.toString(),
                        ).copyableText(version)
                        else -> throw LiveTestFailure("INVALID_MODE")
                    }
                }
            }
            // COMPLETED describes the command only. The diagnostic's own outcome
            // still reports partial, pending, rejected, or verified sync results.
            report += "runner_outcome=COMPLETED"
            report += body
            completed = true
        } catch (error: Exception) {
            report += "runner_outcome=FAILED"
            report += "runner_failure=" + when (error) {
                is LiveTestFailure -> error.code
                is TimeoutCancellationException -> "TIMEOUT"
                is CancellationException -> "CANCELLED"
                is HealthInboundReadUnavailableException -> "HEALTH_READ_UNAVAILABLE"
                is MiApiException.AuthExpired -> "AUTH_EXPIRED"
                is SecurityException -> "ACCESS_DENIED"
                is IllegalArgumentException -> "INVALID_ARGUMENT_OR_CONFIGURATION"
                is IllegalStateException -> "INVALID_STATE_OR_CONFIGURATION"
                else -> "UNCLASSIFIED_ERROR"
            }
            report += "exception_message_withheld=true"
        } finally {
            // Keep the process gate active. The local driver force-stops this test
            // process; the next ordinary app launch restores normal auto-sync.
            runCatching {
                runOnMainSync {
                    foregroundActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    foregroundActivity?.finish()
                }
            }
            report += "excluded=credentials,account_ids,device_ids,source_record_ids,timestamps,health_values," +
                "request_bodies,response_bodies"
            val text = "\n$REPORT_BEGIN\n${report.joinToString("\n")}\n$REPORT_END\n"
            finish(if (completed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", text) })
        }
    }

    @Suppress("ThrowsCount") // Each invalid argument gets its own failure code for the PC test driver.
    private fun parseOptions(): Options {
        if (arguments.keySet().any { it !in setOf("mode", "metric", "rangeDays", "allowWrites") }) {
            throw LiveTestFailure("UNSUPPORTED_ARGUMENT")
        }
        val mode = arguments.getString("mode") ?: MODE_INSPECT
        if (mode !in setOf(MODE_INSPECT, MODE_INSPECT_MINUTE, MODE_VERIFY_EXISTING, MODE_INSPECT_HEART,
                MODE_PREVIEW_HEART, MODE_INSPECT_HEART_COVERAGE, MODE_RECONCILE_HEART, MODE_VERIFY_HEART,
                MODE_INSPECT_CHAIN, MODE_SYNC_ONE, MODE_SYNC_ONE_DAY, MODE_REPAIR_ONE,
                MODE_RECOVER_ONE_PENDING, MODE_RECONCILE_PENDING)) throw LiveTestFailure("INVALID_MODE")
        val metricKey = arguments.getString("metric") ?: "spo2"
        val metric = HealthInboundMetric.entries.singleOrNull { it.metricKey == metricKey }
            ?: throw LiveTestFailure("INVALID_METRIC")
        if (mode == MODE_REPAIR_ONE && metric !in setOf(HealthInboundMetric.HEART_RATE,
                HealthInboundMetric.RESTING_HEART_RATE, HealthInboundMetric.HRV, HealthInboundMetric.SLEEP))
            throw LiveTestFailure("REPAIR_REQUIRES_PULSE_SLEEP_OR_HRV")
        if (mode == MODE_RECOVER_ONE_PENDING && metric != HealthInboundMetric.OXYGEN_SATURATION) {
            throw LiveTestFailure("RECOVERY_REQUIRES_SPO2")
        }
        if (mode == MODE_RECONCILE_PENDING && metric != HealthInboundMetric.OXYGEN_SATURATION) {
            throw LiveTestFailure("RECONCILIATION_REQUIRES_SPO2")
        }
        if (mode == MODE_INSPECT_MINUTE && metric != HealthInboundMetric.OXYGEN_SATURATION)
            throw LiveTestFailure("MINUTE_INSPECTION_REQUIRES_SPO2")
        if (mode == MODE_VERIFY_EXISTING && metric != HealthInboundMetric.OXYGEN_SATURATION)
            throw LiveTestFailure("EXISTING_VERIFICATION_REQUIRES_SPO2")
        if (mode in setOf(MODE_INSPECT_HEART, MODE_PREVIEW_HEART, MODE_INSPECT_HEART_COVERAGE, MODE_RECONCILE_HEART, MODE_VERIFY_HEART) &&
            metric != HealthInboundMetric.HEART_RATE) throw LiveTestFailure("HEART_INSPECTION_REQUIRES_HEART_RATE")
        val rangeDays = (arguments.getString("rangeDays") ?: "3").toIntOrNull()
            // Stay inside the normal HC reader's 28-day conservative boundary
            // even if session refresh or multiple metric reads advance the clock.
            ?.takeIf { it in 1..(if (mode in setOf(MODE_INSPECT_CHAIN, MODE_PREVIEW_HEART, MODE_INSPECT_HEART_COVERAGE)) 30 else 27) }
                ?: throw LiveTestFailure("INVALID_RANGE")
        val writeFlag = arguments.getString("allowWrites") ?: "false"
        if (mode == MODE_SYNC_ONE_DAY && rangeDays != 1) throw LiveTestFailure("ONE_DAY_RANGE_REQUIRED")
        if (writeFlag !in setOf("true", "false")) throw LiveTestFailure("INVALID_WRITE_FLAG")
        if (mode in WRITE_MODES && writeFlag != "true") throw LiveTestFailure("WRITE_FLAG_REQUIRED")
        if (mode in setOf(MODE_INSPECT, MODE_INSPECT_CHAIN) && writeFlag != "false") throw LiveTestFailure("WRITE_FLAG_WITH_INSPECT")
        if (mode == MODE_INSPECT_MINUTE && writeFlag != "false") throw LiveTestFailure("WRITE_FLAG_WITH_INSPECT")
        if (mode == MODE_VERIFY_EXISTING && writeFlag != "false") throw LiveTestFailure("WRITE_FLAG_WITH_EXISTING_VERIFICATION")
        if (mode in setOf(MODE_INSPECT_HEART, MODE_PREVIEW_HEART, MODE_INSPECT_HEART_COVERAGE, MODE_RECONCILE_HEART, MODE_VERIFY_HEART) &&
            writeFlag != "false") throw LiveTestFailure("WRITE_FLAG_WITH_INSPECT")
        if (mode == MODE_RECONCILE_PENDING && writeFlag != "false") throw LiveTestFailure("WRITE_FLAG_WITH_RECONCILE")
        return Options(mode, metric, rangeDays)
    }

    @Suppress("DEPRECATION")
    private fun targetVersion(): String {
        if (targetContext.packageName != TARGET_PACKAGE) throw LiveTestFailure("WRONG_TARGET")
        val info = targetContext.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
        if (info.longVersionCode != EXPECTED_VERSION_CODE || info.versionName != EXPECTED_VERSION_NAME) {
            throw LiveTestFailure("TARGET_VERSION_MISMATCH")
        }
        if (targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            throw LiveTestFailure("RELEASE_TARGET_REQUIRED")
        }
        return "$EXPECTED_VERSION_NAME ($EXPECTED_VERSION_CODE)"
    }

    private data class Options(val mode: String, val metric: HealthInboundMetric, val rangeDays: Int)
    private class LiveTestFailure(val code: String) : IllegalStateException()

    companion object {
        const val REPORT_BEGIN = "MI_HEALTH_LIVE_REPORT_BEGIN"
        const val REPORT_END = "MI_HEALTH_LIVE_REPORT_END"
        private const val TARGET_PACKAGE = "com.bettermifitness.sync.healthlab"
        private const val EXPECTED_VERSION_NAME = "1.0.6-healthlab.19"
        private const val EXPECTED_VERSION_CODE = 1000025L
        private const val MODE_INSPECT = "inspect"
        private const val MODE_INSPECT_MINUTE = "inspect-spo2-minute"
        private const val MODE_VERIFY_EXISTING = "verify-existing-spo2"
        private const val MODE_INSPECT_HEART = "inspect-heart-history"
        private const val MODE_PREVIEW_HEART = "preview-heart-recovery"
        private const val MODE_INSPECT_HEART_COVERAGE = "inspect-heart-coverage"
        private const val MODE_RECONCILE_HEART = "reconcile-heart"
        private const val MODE_VERIFY_HEART = "verify-existing-heart"
        private const val MODE_INSPECT_CHAIN = "inspect-hc-chain"
        private const val MODE_SYNC_ONE = "sync-one"
        private const val MODE_SYNC_ONE_DAY = "sync-one-day"
        private const val MODE_REPAIR_ONE = "repair-one"
        private const val MODE_RECOVER_ONE_PENDING = "recover-one-pending"
        private const val MODE_RECONCILE_PENDING = "reconcile-pending"
        private val WRITE_MODES = setOf(MODE_SYNC_ONE, MODE_SYNC_ONE_DAY, MODE_REPAIR_ONE, MODE_RECOVER_ONE_PENDING)
        private const val TEST_TIMEOUT_MILLIS = 600_000L
    }
}
