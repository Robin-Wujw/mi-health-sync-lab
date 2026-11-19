package com.bettermifitness.sync.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiSleepMismatchFieldsTest {
    @Test
    fun stageAndSegmentDifferencesHaveFixedNamesWithoutIndices() {
        assertEquals("items.start_time", safeMiSleepMismatchField("items[0].start_time"))
        assertEquals("items.end_time", safeMiSleepMismatchField("items[123].end_time"))
        assertEquals("items.state", safeMiSleepMismatchField("items[999].state"))
        assertEquals("items.length", safeMiSleepMismatchField("items.length"))
        assertEquals("segment_details.sleep_deep_duration", safeMiSleepMismatchField("segment_details[8].sleep_deep_duration"))
        assertEquals("segment_details.length", safeMiSleepMismatchField("segment_details.length"))
        assertEquals("items.state", safeMiSleepMismatchField("items.state"))
    }

    @Test
    fun unknownFieldsAndUserProvidedTextNeverPassThrough() {
        for (path in listOf("items[0].account", "items[record-id].state", "items[3].state.secret", "prefix.items[0].state",
            "segment_details[0].token", "items[0]", "items[0].state\n", "items[-1].state", "user@example.com")) {
            assertNull(safeMiSleepMismatchField(path))
        }
    }
}
