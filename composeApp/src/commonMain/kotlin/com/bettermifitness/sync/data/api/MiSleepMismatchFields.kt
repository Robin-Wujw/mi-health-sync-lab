package com.bettermifitness.sync.data.api

/**
 * Normalize only fixed sleep schema paths. Never expose an array index, arbitrary server field,
 * source ID, or health value in shared diagnostics. Unknown paths remain in the caller's "other".
 */
fun safeMiSleepMismatchField(field: String): String? {
    if (field in SAFE_SLEEP_ARRAY_FIELDS) return field
    SLEEP_ITEM_FIELD.matchEntire(field)?.let { return "items.${it.groupValues[1]}" }
    SLEEP_SEGMENT_FIELD.matchEntire(field)?.let { return "segment_details.${it.groupValues[1]}" }
    return null
}

private val SAFE_SLEEP_ARRAY_FIELDS = setOf("items.length", "items.start_time", "items.end_time", "items.state",
    "segment_details.length", "segment_details.bedtime", "segment_details.wake_up_time", "segment_details.timezone",
    "segment_details.duration", "segment_details.sleep_deep_duration", "segment_details.sleep_light_duration",
    "segment_details.sleep_rem_duration", "segment_details.sleep_awake_duration", "segment_details.awake_count")
private val SLEEP_ITEM_FIELD = Regex("items\\[[0-9]+]\\.(start_time|end_time|state)")
private val SLEEP_SEGMENT_FIELD = Regex("segment_details\\[[0-9]+]\\.(bedtime|wake_up_time|timezone|duration|sleep_deep_duration|sleep_light_duration|sleep_rem_duration|sleep_awake_duration|awake_count)")
