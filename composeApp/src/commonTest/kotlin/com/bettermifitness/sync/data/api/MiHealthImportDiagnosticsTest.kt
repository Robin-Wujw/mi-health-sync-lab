package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.data.repository.copyableText
import com.mifitness.miclient.api.MiApiException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic protocol fixtures, not a claim of live Xiaomi-account validation. */
class MiHealthImportDiagnosticsTest {
    @Test
    fun nonzeroBusinessCodeAndGenericMessageSurviveWithoutBecomingARecordCount() {
        val result = parseMiHealthImportUploadResponse(Json.parseToJsonElement(
            """{"code":10007,"message":"invalid parameter","result":false}"""))
        assertFalse(result.accepted)
        assertEquals(10007, result.code)
        assertEquals("invalid parameter", result.safeMessage)
        assertFalse(result.messageWithheld)
    }

    @Test
    fun codeZeroAndExplicitFalseRemainVisibleAsSeparateFacts() {
        val result = parseMiHealthImportUploadResponse(Json.parseToJsonElement(
            """{"code":0,"result":{"result":false,"msg":"参数错误"}}"""))
        assertEquals(0, result.code)
        assertFalse(result.accepted)
        assertEquals("参数错误", result.safeMessage)
    }

    @Test
    fun codeZeroWithoutAnExplicitBooleanIsUnknownAndRetainsOnlySafeMetadata() {
        val cases = listOf(
            "" to MiHealthImportUploadResultShape.MISSING,
            ",\"result\":null" to MiHealthImportUploadResultShape.NULL,
            ",\"result\":{}" to MiHealthImportUploadResultShape.OBJECT_WITHOUT_RESULT,
            ",\"result\":{\"result\":null}" to MiHealthImportUploadResultShape.NESTED_NULL,
            ",\"result\":\"true\"" to MiHealthImportUploadResultShape.INVALID,
            ",\"result\":1" to MiHealthImportUploadResultShape.INVALID,
            ",\"result\":[]" to MiHealthImportUploadResultShape.INVALID,
            ",\"result\":{\"result\":\"false\"}" to MiHealthImportUploadResultShape.NESTED_INVALID,
            ",\"result\":{\"result\":1}" to MiHealthImportUploadResultShape.NESTED_INVALID,
            ",\"result\":{\"result\":{},\"private-id\":\"private-health-value\"}" to MiHealthImportUploadResultShape.NESTED_INVALID,
        )
        for ((resultJson, expectedShape) in cases) {
            val error = assertFailsWith<MiHealthImportTransportException> {
                parseMiHealthImportUploadResponse(Json.parseToJsonElement("{\"code\":0,\"message\":\"ok\"$resultJson}"))
            }
            val summary = error.responseSummary
            assertNull(summary.accepted)
            assertEquals(0, summary.businessCode)
            assertEquals("ok", summary.safeMessage)
            assertEquals(MiHealthImportTransportFailure.INVALID_RESPONSE, summary.transportFailure)
            assertEquals(expectedShape, summary.uploadResultShape)
            val copied = MiHealthImportDiagnostic("spo2", MiHealthImportStage.READBACK,
                attemptedRawRows = 1, uploadResponse = summary).copyableText("test")
            assertTrue("upload_result_shape=${expectedShape.name}" in copied)
            assertTrue("upload_business_code=0" in copied)
            assertFalse("upload_accepted=" in copied)
            assertFalse("private-" in copied)
        }
    }

    @Test
    fun onlyExplicitTrueMeansAcceptedAndNonzeroCodeAlwaysRejects() {
        for (accepted in listOf(true, false)) {
            for ((resultJson, shape) in listOf(
                "$accepted" to MiHealthImportUploadResultShape.BOOLEAN,
                "{\"result\":$accepted}" to MiHealthImportUploadResultShape.NESTED_BOOLEAN,
            )) {
                val result = parseMiHealthImportUploadResponse(Json.parseToJsonElement("{\"code\":0,\"result\":$resultJson}"))
                assertEquals(accepted, result.accepted)
                assertEquals(shape, result.responseSummary().uploadResultShape)
                val rejected = parseMiHealthImportUploadResponse(Json.parseToJsonElement("{\"code\":100,\"result\":$resultJson}"))
                assertFalse(rejected.accepted)
                assertEquals(100, rejected.code)
            }
        }
    }

    @Test
    fun serverMessagesThatCouldEchoCredentialsOrMeasurementsAreNotRetained() {
        for (message in listOf("invalid parameter token=private-token", "bpm 73 for user 12345678",
            "visit https://example.invalid/login?ticket=secret", "健康数据 value={heart:73}")) {
            val result = parseMiHealthImportUploadResponse(Json.parseToJsonElement(
                """{"code":7,"message":${kotlinx.serialization.json.JsonPrimitive(message)}}"""))
            assertNull(result.safeMessage)
            assertTrue(result.messageWithheld)
        }
    }

    @Test
    fun readFailurePreservesBusinessCodeAndNeverPretendsTheCloudIsEmpty() {
        val error = assertFailsWith<MiHealthImportTransportException> {
            parseMiHealthImportReadPage(Json.parseToJsonElement(
                """{"code":17,"description":"forbidden","result":{"data_list":[]}}"""),
                MiHealthImportChannel.RAW, "heart_rate")
        }
        assertEquals(17, error.responseSummary.businessCode)
        assertEquals("forbidden", error.responseSummary.safeMessage)
        assertNull(error.responseSummary.httpStatus)
    }

    @Test
    fun missingBusinessStatusIsUncertainNotAccepted() {
        assertFailsWith<MiApiException.Unexpected> {
            parseMiHealthImportUploadResponse(Json.parseToJsonElement("""{"result":true}"""))
        }
    }

    @Test
    fun copyDiagnosticUsesAnAllowlistAndKeepsBusinessAndHttpCodesDistinct() {
        val text = MiHealthImportDiagnostic(
            metricKey = "heart_rate", stage = MiHealthImportStage.UPLOAD,
            channel = MiHealthImportChannel.RAW, affectedRows = 50, batchRows = 50,
            uploadResponse = MiHealthImportResponseSummary(businessCode = 10007, accepted = false,
                safeMessage = "token=secret-bpm-73"),
            readResponse = MiHealthImportResponseSummary(httpStatus = 503,
                transportFailure = MiHealthImportTransportFailure.HTTP),
        ).copyableText("1.0.6-healthlab.3 (1000009)")
        assertTrue("affected_rows=50" in text)
        assertTrue("upload_business_code=10007" in text)
        assertTrue("read_http_status=503" in text)
        assertTrue("upload_accepted=false" in text)
        assertTrue("upload_message_withheld=true" in text)
        assertFalse("secret-bpm" in text)
        assertFalse("upload_http_status=10007" in text)
        assertFalse("read_business_code=503" in text)
    }

    @Test
    fun issueCountsSeparateOccurrencesFromDistinctSourceRecordsWithoutExportingIdentifiers() {
        val issues = listOf(
            MiHealthImportIssue("RESTING_HEART_RATE_DATE_UNKNOWN", setOf("private-source-a"), "private message"),
            MiHealthImportIssue("RESTING_HEART_RATE_DATE_UNKNOWN", setOf("private-source-a"), "another message"),
            MiHealthImportIssue("HRV_NO_MATCHING_SLEEP", setOf("private-source-b", "private-source-c"),
                "private interval", MiHealthImportIssueSeverity.WARNING),
        )
        val summary = summarizeMiHealthImportIssues(issues)
        val date = summary.single { it.code == "RESTING_HEART_RATE_DATE_UNKNOWN" }
        assertEquals(2, date.occurrences)
        assertEquals(1, date.sourceRecordCount)
        val excluded = summary.single { it.code == "HRV_NO_MATCHING_SLEEP" }
        assertEquals(MiHealthImportIssueSeverity.WARNING, excluded.severity)
        assertEquals(2, excluded.sourceRecordCount)
        val text = MiHealthImportDiagnostic("resting_heart_rate", MiHealthImportStage.PREPARE,
            failureReason = "SOURCE_REJECTED", verifiedRawRows = 1, sourceRejectedRecords = 1,
            issueCounts = summary).copyableText("test")
        assertTrue(text.startsWith("Mi Health Sync Lab import diagnostic v3"))
        assertTrue("outcome=PARTIAL" in text)
        assertTrue("failure_reason=SOURCE_REJECTED" in text)
        assertTrue("source_rejected_records=1" in text)
        assertTrue("severity=WARNING" in text)
        assertFalse("private-" in text)
        assertFalse("private message" in text)
    }

    @Test
    fun mismatchFieldsAreBoundedAndAnUnknownPathCountsOncePerRow() {
        val counts = summarizeMiHealthImportMismatchFields(listOf(
            MiHealthImportVerification(MiHealthImportVerificationStatus.MISMATCH,
                setOf("spo2", "items[73].value", "private-account-id", "private-health-value")),
            MiHealthImportVerification(MiHealthImportVerificationStatus.MISMATCH, setOf("spo2", "zone_offset")),
        ))
        assertEquals(mapOf("other" to 1, "spo2" to 2, "zone_offset" to 1), counts)
        val text = MiHealthImportDiagnostic("spo2", MiHealthImportStage.READBACK,
            readbackMismatchFields = counts + ("private-body-path" to 3),
            precheckMismatchFields = mapOf("duplicate_target" to 4)).copyableText("test")
        assertTrue("readback_mismatch_field.spo2=2" in text)
        assertTrue("readback_mismatch_field.other=4" in text)
        assertTrue("precheck_mismatch_field.duplicate_target=4" in text)
        assertFalse("items[" in text)
        assertFalse("private-" in text)
    }

    @Test
    fun arbitraryIssueAndReasonStringsCannotLeakThroughTheSupportExport() {
        val text = MiHealthImportDiagnostic("hrv", MiHealthImportStage.PREPARE,
            failureReason = "user-123-token-secret",
            issueCounts = listOf(MiHealthImportIssueCount("ACCOUNT_SECRET_987", MiHealthImportIssueSeverity.REJECTED, 1, 1)))
            .copyableText("test")
        assertTrue("failure_reason=UNKNOWN" in text)
        assertTrue("issue.0.code=UNKNOWN" in text)
        assertFalse("user-123" in text)
        assertFalse("ACCOUNT_SECRET" in text)
    }

    @Test
    fun v2PreservesCounterBreakdownInsteadOfTreatingAffectedRowsAsTheTotalFailure() {
        val text = MiHealthImportDiagnostic("spo2", MiHealthImportStage.READBACK,
            plannedRawRows = 1542, attemptedRawRows = 233, verifiedRawRows = 411, affectedRows = 171,
            alreadyPresentRawRows = 349, newlyVerifiedRawRows = 62, notAttemptedRawRows = 960,
            failureReason = "UNVERIFIED", readbackMissingRows = 170, readbackMismatchedRows = 1,
            uploadResponse = MiHealthImportResponseSummary(businessCode = 0, accepted = true))
            .copyableText("test")
        for (line in listOf("already_present_raw_rows=349", "newly_verified_raw_rows=62",
            "not_attempted_raw_rows=960", "readback_missing_rows=170", "readback_mismatched_rows=1",
            "affected_rows=171", "upload_accepted=true", "failure_reason=UNVERIFIED")) assertTrue(line in text)
        assertTrue("not_attempted_includes_blocked" in text)
    }
}
