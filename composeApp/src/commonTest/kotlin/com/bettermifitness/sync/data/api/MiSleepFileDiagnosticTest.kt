package com.bettermifitness.sync.data.api

import kotlin.test.Test
import kotlin.test.assertEquals

class MiSleepFileDiagnosticTest {
    @Test fun boundedFailurePhaseAndBusinessCodeSurviveRedaction() {
        val code = "SLEEP_FILE_EXISTING_READ_SERVER_-6"
        assertEquals(code, safeMiHealthImportIssueCode(code))
        assertEquals("SLEEP_FILE_UPLOAD_READBACK_CHECK",
            safeMiHealthImportIssueCode("SLEEP_FILE_UPLOAD_READBACK_CHECK"))
    }

    @Test fun exceptionMessagesAndUnboundedSuffixesRemainRedacted() {
        for (code in listOf("SLEEP_FILE_EXISTING_READ_SERVER_-6:account=synthetic",
            "SLEEP_FILE_ACCOUNT_SERVER_1", "SLEEP_FILE_EXISTING_READ_SERVER_12345678901",
            "SLEEP_FILE_EXISTING_READ_https://synthetic.invalid")) {
            assertEquals("UNKNOWN", safeMiHealthImportIssueCode(code))
        }
    }
}
