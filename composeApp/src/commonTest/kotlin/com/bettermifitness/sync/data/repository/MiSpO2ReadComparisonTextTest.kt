package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.LocalizedTest
import com.bettermifitness.sync.data.api.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiSpO2ReadComparisonTextTest : LocalizedTest() {
    @Test
    fun reportKeepsComparisonHintsSeparateFromVerifiedProgressAndOmitsSensitiveData() {
        val timestamp = 1_800_001_810L
        val row = MiHealthImportRow(MiHealthImportIdentity(MiHealthImportChannel.RAW,
            MI_HEALTH_CONNECT_IMPORT_SID, "spo2", timestamp),
            buildJsonObject { put("time", timestamp); put("spo2", 93) }, 28_800,
            setOf("private-source-token"))
        val cloudRow = MiHealthImportCloudRow(MI_HEALTH_CONNECT_IMPORT_SID, "spo2", timestamp - 10,
            null, buildJsonObject { put("time", timestamp - 10); put("spo2", 93) })
        val comparison = compareMiSpO2Reads(listOf(row), listOf(cloudRow), setOf(row.identity))
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.PRECHECK,
            pendingReconcile = MiPendingReconcileDiagnostic("INCOMPLETE", true,
                candidateRows = 1, unresolvedRows = 1, missingRows = 1, readComparison = comparison))
        val report = diagnostic.copyableText("test")
        assertTrue("spo2_comparison.read_only=true" in report)
        assertTrue("spo2_comparison.extra_cloud_requests=0" in report)
        assertTrue("spo2_comparison.missing_same_minute_candidate_targets=1" in report)
        assertTrue("spo2_comparison.missing_same_value_candidate_targets=1" in report)
        assertTrue("verified_raw_rows=0\n" in report)
        assertTrue("attempted_raw_rows=0\n" in report)
        assertTrue("candidates_never_verify" in report)
        assertTrue("zero_hints_do_not_prove_absence" in report)
        val human = diagnostic.userFacingDetails()
        assertTrue("clues for investigation, not verified matches" in human)
        for (secret in listOf(timestamp.toString(), (timestamp - 10).toString(), "93", "28800",
            "private-source-token", "RAW|default|spo2|", "\"spo2\"")) {
            assertFalse(secret in report)
            assertFalse(secret in human)
        }
    }
}
