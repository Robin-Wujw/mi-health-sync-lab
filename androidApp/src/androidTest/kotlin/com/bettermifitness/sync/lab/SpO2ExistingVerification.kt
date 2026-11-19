package com.bettermifitness.sync.lab

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.data.repository.MiHealthImportRunException
import com.bettermifitness.sync.data.repository.MiHealthImportRunner
import com.bettermifitness.sync.data.repository.copyableText
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthStore
import com.bettermifitness.sync.health.HealthInboundMetric
import kotlin.time.Instant

/** Exercise normal precheck on existing data. Both write-ahead and POST are disabled. */
internal suspend fun verifyExistingSpO2(session: MiSessionManager, store: HealthStore,
    ledger: HealthImportLedger, from: Instant, until: Instant, version: String,
    metric: HealthInboundMetric = HealthInboundMetric.OXYGEN_SATURATION): String {
    val source = "com.huami.watch.hmwatchmanager"
    val request = HealthInboundReadRequest(from.epochSeconds, until.epochSeconds, setOf(source))
    val plan = when (metric) {
        HealthInboundMetric.OXYGEN_SATURATION -> MiHealthImportMapper.oxygenSaturation(store.readOxygenSaturation(request), source)
        HealthInboundMetric.HEART_RATE -> MiHealthImportMapper.heartRate(store.readHeartRate(request), source)
        else -> error("Unsupported verification metric")
    }
    var writeBlocked = false
    val cloud = object : MiHealthImportCloud by MiDirectHealthImportCloud(session.api) {
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult =
            error("READ_ONLY_CLOUD_GUARD")
    }
    val journal = object : HealthImportJournal by ledger {
        override suspend fun markPending(account: String, entries: List<HealthImportJournalEntry>) {
            writeBlocked = true
            error("READ_ONLY_RESERVATION_GUARD")
        }
    }
    val diagnostic = try {
        MiHealthImportRunner(cloud, journal).run(checkNotNull(session.currentUserId), plan).diagnostic
    } catch (error: MiHealthImportRunException) {
        error.diagnostic ?: throw error
    } catch (error: IllegalStateException) {
        if (!writeBlocked) throw error
        null
    }
    return "existing_verification.health_uploads=0\nexisting_verification.new_write_blocked=$writeBlocked\n" +
        (diagnostic?.copyableText(version) ?: "existing_verification.status=NEW_WRITE_BLOCKED")
}
