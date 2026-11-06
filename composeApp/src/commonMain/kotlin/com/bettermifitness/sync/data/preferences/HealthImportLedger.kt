package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** A target identity is stable when the source record is edited or the read window changes. */
data class HealthImportJournalEntry(
    val targetIdentity: String,
    val contentFingerprint: String,
    val sourceRecordKeys: Set<String>,
    /** Set only after this version submitted the row and read its content back. */
    val ownedUpload: Boolean = false,
    /** Transport metadata for the canonical target row; not HTTP credentials. */
    val zoneOffsetSeconds: Int? = null,
)

enum class HealthImportRecoveryOutcome { RESERVED, ACCEPTED, REJECTED, TRANSPORT_UNKNOWN, READBACK_UNVERIFIED, VERIFIED }

@Serializable
data class HealthImportRecoveryAttempt(
    val token: String,
    val createdAtEpochSeconds: Long,
    val contentFingerprint: String,
    val sourceDigest: String,
    /** Includes identity, canonical content fingerprint and zone, not the HTTP envelope. */
    val wireFingerprint: String,
    val legacyWireMetadataUnknown: Boolean,
    val outcome: String = HealthImportRecoveryOutcome.RESERVED.name,
    val accepted: Boolean? = null,
    val businessCode: Int? = null,
    val readbackStatus: String? = null,
)

data class HealthImportPendingSnapshot(
    val targetIdentity: String,
    /** Exact persisted value, used only as a local compare-and-consume token. */
    val pendingToken: String,
    val contentFingerprint: String?,
    val sourceDigest: String?,
    val zoneOffsetSeconds: Int?,
    val valid: Boolean,
    val recoveryConsumed: Boolean,
    val recoveryAttempt: HealthImportRecoveryAttempt?,
) {
    fun matches(entry: HealthImportJournalEntry): Boolean = valid &&
        targetIdentity == entry.targetIdentity && contentFingerprint == entry.contentFingerprint &&
        sourceDigest == HealthImportLedger.sourceDigest(entry.sourceRecordKeys) &&
        (zoneOffsetSeconds == null || zoneOffsetSeconds == entry.zoneOffsetSeconds)
}

data class HealthImportRecoveryReservation(val targetIdentity: String, val attempt: HealthImportRecoveryAttempt)

/** Source provenance authorizes the local CAS; observed content is what the cloud actually returned. */
data class HealthImportPendingReconciliation(
    val expected: HealthImportPendingSnapshot,
    val sourceEntry: HealthImportJournalEntry,
    val observedContentFingerprint: String,
    /** Actual cloud target; only a validated SpO2 minute alias may differ. */
    val observedTargetIdentity: String = sourceEntry.targetIdentity,
)

/** A read-only minute match with no pending source write to reconcile. */
data class HealthImportObservedAlias(
    val sourceEntry: HealthImportJournalEntry,
    val observedTargetIdentity: String,
    val observedContentFingerprint: String,
)

interface HealthImportJournal {
    suspend fun pendingIdentities(account: String, identities: Set<String>): Set<String>
    suspend fun markPending(account: String, entries: List<HealthImportJournalEntry>)
    suspend fun markVerified(account: String, entries: List<HealthImportJournalEntry>)
    suspend fun verifiedFingerprints(account: String): Map<String, String>
    suspend fun ownedVerifiedFingerprints(account: String): Map<String, String> = emptyMap()
    suspend fun legacyVerifiedFingerprints(account: String): Map<String, String> = emptyMap()
    suspend fun pendingSnapshots(account: String, identities: Set<String>): Map<String, HealthImportPendingSnapshot> = emptyMap()
    suspend fun reserveSingleRecovery(account: String, expected: HealthImportPendingSnapshot,
        entry: HealthImportJournalEntry): HealthImportRecoveryReservation? = null
    suspend fun recordRecoveryOutcome(account: String, reservation: HealthImportRecoveryReservation,
        outcome: HealthImportRecoveryOutcome, accepted: Boolean? = null, businessCode: Int? = null,
        readbackStatus: String? = null): Boolean = false
    /** Complete only unchanged pending targets already semantically matched by a cloud read. */
    suspend fun reconcileObservedPending(account: String,
        entries: List<HealthImportPendingReconciliation>): Set<String> = emptySet()
    /** Record both identities only when neither has a pending or conflicting historical write. */
    suspend fun recordObservedAliases(account: String,
        entries: List<HealthImportObservedAlias>): Set<String> = emptySet()
}

/**
 * Persist before making a remote mutation. A timeout, process death or changed
 * query window must never silently turn an uncertain POST into a fresh upload.
 * Confirmed fingerprints are retained to suppress exporting the same imported
 * values back to Health Connect. No tokens or health payloads are stored here.
 */
class HealthImportLedger(private val dataStore: DataStore<Preferences>) : HealthImportJournal {
    private fun accountPrefix(account: String): String = HealthRecordIds.version("hc-import-account", account).toString() + ":"
    private fun pendingKey(account: String, identity: String) =
        stringPreferencesKey(PENDING_PREFIX + accountPrefix(account) + identityDigest(identity))
    private fun verifiedKey(account: String, identity: String) =
        stringPreferencesKey(VERIFIED_PREFIX + accountPrefix(account) + identityDigest(identity))
    private fun ownershipKey(account: String, identity: String) =
        stringPreferencesKey(OWNERSHIP_PREFIX + accountPrefix(account) + identityDigest(identity))
    private fun recoveryKey(account: String, identity: String) =
        stringPreferencesKey(RECOVERY_PREFIX + accountPrefix(account) + identityDigest(identity))
    private fun aliasSourceKey(account: String, identity: String) =
        stringPreferencesKey(ALIAS_SOURCE_PREFIX + accountPrefix(account) + identityDigest(identity))

    override suspend fun pendingIdentities(account: String, identities: Set<String>): Set<String> {
        val snapshot = dataStore.data.first()
        return identities.filterTo(mutableSetOf()) { snapshot[pendingKey(account, it)] != null }
    }

    override suspend fun markPending(account: String, entries: List<HealthImportJournalEntry>) {
        if (entries.isEmpty()) return
        dataStore.edit { prefs ->
            val now = Clock.System.now().epochSeconds
            for (entry in entries) {
                val sourceDigest = sourceDigest(entry.sourceRecordKeys)
                val zone = entry.zoneOffsetSeconds?.let { "|$it" }.orEmpty()
                prefs[pendingKey(account, entry.targetIdentity)] = "$now|${entry.contentFingerprint}|$sourceDigest$zone"
            }
        }
    }

    override suspend fun markVerified(account: String, entries: List<HealthImportJournalEntry>) {
        if (entries.isEmpty()) return
        dataStore.edit { prefs ->
            for (entry in entries) {
                val previousFingerprint = prefs[verifiedKey(account, entry.targetIdentity)]
                when {
                    entry.ownedUpload -> prefs[ownershipKey(account, entry.targetIdentity)] = OWNED
                    // Equal read-only observations preserve either owned or legacy provenance.
                    // Changed content cannot inherit ownership from an older fingerprint.
                    previousFingerprint != entry.contentFingerprint ->
                        prefs[ownershipKey(account, entry.targetIdentity)] = OBSERVED
                }
                prefs[verifiedKey(account, entry.targetIdentity)] = entry.contentFingerprint
                prefs.remove(pendingKey(account, entry.targetIdentity))
            }
        }
    }

    override suspend fun verifiedFingerprints(account: String): Map<String, String> {
        val prefix = VERIFIED_PREFIX + accountPrefix(account)
        return dataStore.data.first().asMap().entries
            .filter { it.key.name.startsWith(prefix) }
            .associate { it.key.name.removePrefix(prefix) to it.value.toString() }
    }

    override suspend fun ownedVerifiedFingerprints(account: String): Map<String, String> =
        fingerprintsWithOwnership(account, OWNED)

    override suspend fun legacyVerifiedFingerprints(account: String): Map<String, String> =
        fingerprintsWithOwnership(account, null)

    private suspend fun fingerprintsWithOwnership(account: String, ownership: String?): Map<String, String> {
        val snapshot = dataStore.data.first()
        val verifiedPrefix = VERIFIED_PREFIX + accountPrefix(account)
        val originPrefix = OWNERSHIP_PREFIX + accountPrefix(account)
        return snapshot.asMap().entries.filter { it.key.name.startsWith(verifiedPrefix) }
            .map { it.key.name.removePrefix(verifiedPrefix) to it.value.toString() }
            .filter { (digest, _) -> snapshot[stringPreferencesKey(originPrefix + digest)] == ownership }
            .toMap()
    }

    override suspend fun pendingSnapshots(account: String, identities: Set<String>): Map<String, HealthImportPendingSnapshot> {
        val snapshot = dataStore.data.first()
        return identities.mapNotNull { identity ->
            val pending = snapshot[pendingKey(account, identity)] ?: return@mapNotNull null
            val recovery = snapshot[recoveryKey(account, identity)]
            identity to parsePending(identity, pending, recovery)
        }.toMap()
    }

    /** Never clears pending. The durable one-use record is committed before the caller can POST. */
    override suspend fun reserveSingleRecovery(account: String, expected: HealthImportPendingSnapshot,
        entry: HealthImportJournalEntry): HealthImportRecoveryReservation? {
        require(account.isNotBlank())
        if (!expected.matches(entry) || entry.zoneOffsetSeconds == null || entry.zoneOffsetSeconds !in -64_800..64_800) return null
        var reserved: HealthImportRecoveryReservation? = null
        dataStore.edit { prefs ->
            val pendingName = pendingKey(account, expected.targetIdentity)
            val recoveryName = recoveryKey(account, expected.targetIdentity)
            if (prefs[pendingName] != expected.pendingToken || prefs[recoveryName] != null) return@edit
            if (!parsePending(expected.targetIdentity, expected.pendingToken, null).matches(entry)) return@edit
            val now = Clock.System.now()
            val rowFingerprint = wireFingerprint(entry)
            val attempt = HealthImportRecoveryAttempt(
                token = HealthRecordIds.version("single-recovery", expected.targetIdentity,
                    expected.pendingToken, rowFingerprint, now.toEpochMilliseconds()).toString(),
                createdAtEpochSeconds = now.epochSeconds, contentFingerprint = entry.contentFingerprint,
                sourceDigest = sourceDigest(entry.sourceRecordKeys), wireFingerprint = rowFingerprint,
                legacyWireMetadataUnknown = expected.zoneOffsetSeconds == null,
            )
            prefs[recoveryName] = Json.encodeToString(attempt)
            reserved = HealthImportRecoveryReservation(expected.targetIdentity, attempt)
        }
        return reserved
    }

    override suspend fun recordRecoveryOutcome(account: String, reservation: HealthImportRecoveryReservation,
        outcome: HealthImportRecoveryOutcome, accepted: Boolean?, businessCode: Int?, readbackStatus: String?): Boolean {
        require(readbackStatus == null || readbackStatus in setOf("VERIFIED", "MISSING", "MISMATCH", "AMBIGUOUS", "UNQUERIED"))
        var recorded = false
        dataStore.edit { prefs ->
            val key = recoveryKey(account, reservation.targetIdentity)
            val previous = parseRecovery(prefs[key]) ?: return@edit
            if (previous.token != reservation.attempt.token) return@edit
            // A delayed failure must not undo a later successful reconciliation.
            if (previous.outcome == HealthImportRecoveryOutcome.VERIFIED.name && outcome != HealthImportRecoveryOutcome.VERIFIED)
                return@edit
            prefs[key] = Json.encodeToString(previous.copy(outcome = outcome.name,
                accepted = accepted ?: previous.accepted, businessCode = businessCode ?: previous.businessCode,
                readbackStatus = readbackStatus ?: previous.readbackStatus))
            recorded = true
        }
        return recorded
    }

    /**
     * One local transaction for observed verification and the consumed attempt's
     * final state. No upload allowance is consumed or removed by this operation.
     * A changed pending/recovery snapshot or ambiguous duplicate input is skipped.
     */
    override suspend fun reconcileObservedPending(account: String,
        entries: List<HealthImportPendingReconciliation>): Set<String> {
        require(account.isNotBlank())
        if (entries.isEmpty()) return emptySet()
        val grouped = entries.groupBy { it.expected.targetIdentity }
        val observedCounts = entries.groupingBy { it.observedTargetIdentity }.eachCount()
        val candidates = grouped.values.filter { it.size == 1 }.map { it.single() }
            .filter { observedCounts[it.observedTargetIdentity] == 1 }
        if (candidates.isEmpty()) return emptySet()
        val committed = linkedSetOf<String>()
        dataStore.edit { prefs ->
            for (candidate in candidates) {
                val expected = candidate.expected
                val entry = candidate.sourceEntry
                if (!expected.matches(entry) || entry.sourceRecordKeys.isEmpty() ||
                    candidate.observedContentFingerprint.isBlank()) continue
                val observedIdentity = candidate.observedTargetIdentity
                val usesAlias = observedIdentity != entry.targetIdentity
                if (usesAlias && (!isSpO2MinuteAlias(entry.targetIdentity, observedIdentity) ||
                        entry.zoneOffsetSeconds == null || entry.zoneOffsetSeconds !in -64_800..64_800)) continue
                val pendingName = pendingKey(account, expected.targetIdentity)
                val pendingValue = prefs[pendingName] ?: continue
                if (pendingValue != expected.pendingToken) continue
                val recoveryName = recoveryKey(account, expected.targetIdentity)
                val current = parsePending(expected.targetIdentity, pendingValue, prefs[recoveryName])
                if (!current.matches(entry) || current.recoveryConsumed != expected.recoveryConsumed) continue
                val recovery = if (current.recoveryConsumed) {
                    val actual = current.recoveryAttempt ?: continue
                    val previous = expected.recoveryAttempt ?: continue
                    if (actual.token.isBlank() || actual.token != previous.token ||
                        actual.contentFingerprint != entry.contentFingerprint ||
                        actual.sourceDigest != sourceDigest(entry.sourceRecordKeys) ||
                        actual.wireFingerprint != wireFingerprint(entry)) continue
                    actual
                } else null

                if (usesAlias) {
                    // Never clear or replace a different target's pending write.
                    // Read this inside the same transaction as the original CAS.
                    if (!canObserveAliasTarget(prefs, account, observedIdentity, candidate.observedContentFingerprint)) continue
                    val boundSource = prefs[aliasSourceKey(account, observedIdentity)]
                    if (boundSource != null && boundSource != identityDigest(entry.targetIdentity)) continue
                }

                if (recovery != null) {
                    // Keep the latest business outcome metadata and the durable
                    // token; an observation is not a second accepted upload.
                    prefs[recoveryName] = Json.encodeToString(recovery.copy(
                        outcome = HealthImportRecoveryOutcome.VERIFIED.name, readbackStatus = "VERIFIED"))
                }
                val verifiedName = verifiedKey(account, expected.targetIdentity)
                if (prefs[verifiedName] != candidate.observedContentFingerprint ||
                    (usesAlias && prefs[ownershipKey(account, expected.targetIdentity)] != OWNED)) {
                    // Never promote an observed match using sourceEntry.ownedUpload.
                    prefs[ownershipKey(account, expected.targetIdentity)] = OBSERVED
                }
                prefs[verifiedName] = candidate.observedContentFingerprint
                if (usesAlias) {
                    val aliasVerifiedName = verifiedKey(account, observedIdentity)
                    val aliasOwnershipName = ownershipKey(account, observedIdentity)
                    if (prefs[aliasVerifiedName] != candidate.observedContentFingerprint || prefs[aliasOwnershipName] != OWNED) {
                        prefs[aliasOwnershipName] = OBSERVED
                    }
                    prefs[aliasVerifiedName] = candidate.observedContentFingerprint
                    prefs[aliasSourceKey(account, observedIdentity)] = identityDigest(entry.targetIdentity)
                }
                prefs.remove(pendingName)
                committed += expected.targetIdentity
            }
        }
        return committed
    }

    override suspend fun recordObservedAliases(account: String,
        entries: List<HealthImportObservedAlias>): Set<String> {
        require(account.isNotBlank())
        val grouped = entries.groupBy { it.sourceEntry.targetIdentity }
        val aliasCounts = entries.groupingBy { it.observedTargetIdentity }.eachCount()
        val candidates = grouped.values.filter { it.size == 1 }.map { it.single() }
            .filter { aliasCounts[it.observedTargetIdentity] == 1 }
        if (candidates.isEmpty()) return emptySet()
        val committed = linkedSetOf<String>()
        dataStore.edit { prefs ->
            for (candidate in candidates) {
                val source = candidate.sourceEntry
                val alias = candidate.observedTargetIdentity
                val fingerprint = candidate.observedContentFingerprint
                if (source.sourceRecordKeys.isEmpty() || fingerprint.isBlank() ||
                    source.zoneOffsetSeconds == null || source.zoneOffsetSeconds !in -64_800..64_800 ||
                    !isSpO2MinuteAlias(source.targetIdentity, alias)) continue
                if (!canObserveAliasTarget(prefs, account, source.targetIdentity, fingerprint) ||
                    !canObserveAliasTarget(prefs, account, alias, fingerprint)) continue
                val boundSource = prefs[aliasSourceKey(account, alias)]
                if (boundSource != null && boundSource != identityDigest(source.targetIdentity)) continue
                for (identity in listOf(source.targetIdentity, alias)) {
                    val verifiedName = verifiedKey(account, identity)
                    val ownershipName = ownershipKey(account, identity)
                    if (prefs[verifiedName] != fingerprint || prefs[ownershipName] != OWNED) prefs[ownershipName] = OBSERVED
                    prefs[verifiedName] = fingerprint
                }
                prefs[aliasSourceKey(account, alias)] = identityDigest(source.targetIdentity)
                committed += source.targetIdentity
            }
        }
        return committed
    }

    private fun canObserveAliasTarget(prefs: Preferences, account: String, identity: String, fingerprint: String): Boolean {
        if (prefs[pendingKey(account, identity)] != null) return false
        val verified = prefs[verifiedKey(account, identity)]
        if (verified != null && verified != fingerprint) return false
        val recoveryValue = prefs[recoveryKey(account, identity)] ?: return true
        val recovery = parseRecovery(recoveryValue) ?: return false
        return verified == fingerprint && recovery.token.isNotBlank() &&
            recovery.outcome == HealthImportRecoveryOutcome.VERIFIED.name && recovery.readbackStatus == "VERIFIED"
    }

    /** Local support/tests may inspect fixed metadata; no raw health payload is retained. */
    suspend fun recoveryAttempts(account: String, identities: Set<String>): Map<String, HealthImportRecoveryAttempt> {
        val snapshot = dataStore.data.first()
        return identities.mapNotNull { id -> parseRecovery(snapshot[recoveryKey(account, id)])?.let { id to it } }.toMap()
    }

    /** Explicit user action only: removes retry locks, never remote or local health records. */
    suspend fun resetPendingImports() {
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(PENDING_PREFIX) }
                // A broad legacy reset must not re-arm a consumed single recovery.
                .filter { prefs[stringPreferencesKey(RECOVERY_PREFIX + it.name.removePrefix(PENDING_PREFIX))] == null }
                .forEach { prefs.remove(stringPreferencesKey(it.name)) }
        }
    }

    companion object {
        private const val PENDING_PREFIX = "hc_import_pending_v1:"
        private const val VERIFIED_PREFIX = "hc_import_verified_v1:"
        private const val OWNERSHIP_PREFIX = "hc_import_ownership_v2:"
        private const val RECOVERY_PREFIX = "hc_import_single_recovery_v1:"
        /** Retains one original target per observed minute across calls, windows and restarts. */
        private const val ALIAS_SOURCE_PREFIX = "hc_import_spo2_alias_source_v1:"
        private const val OWNED = "uploaded_and_verified"
        private const val OBSERVED = "observed_only"
        fun identityDigest(identity: String): String = HealthRecordIds.version("hc-import-target", identity).toString()
        fun sourceDigest(sourceKeys: Set<String>): String = HealthRecordIds.version(*sourceKeys.sorted().toTypedArray()).toString()
        fun wireFingerprint(entry: HealthImportJournalEntry): String = HealthRecordIds.version(
            "hc-import-target-row-v1", entry.targetIdentity, entry.contentFingerprint, entry.zoneOffsetSeconds).toString()

        /** Narrow defense even when a caller constructs an alias without the readback policy. */
        @Suppress("ComplexCondition") // The identity format has exactly these required parts.
        private fun isSpO2MinuteAlias(sourceIdentity: String, observedIdentity: String): Boolean {
            val source = sourceIdentity.split('|')
            val observed = observedIdentity.split('|')
            if (source.size != 5 || observed.size != 5 || source[0] != "RAW" || source[1] != "default" ||
                source[2] != "spo2" || source[4].isNotEmpty()) return false
            val time = source[3].toLongOrNull() ?: return false
            if (time < 0 || time % 60L == 0L || source[3] != time.toString()) return false
            val normalizedTime = time.floorDiv(60L) * 60L
            return observedIdentity == "RAW|default|spo2|$normalizedTime|"
        }

        private fun parseRecovery(value: String?): HealthImportRecoveryAttempt? = value?.let {
            runCatching { Json.decodeFromString<HealthImportRecoveryAttempt>(it) }.getOrNull()
        }

        private fun parsePending(identity: String, pending: String, recovery: String?): HealthImportPendingSnapshot {
            val fields = pending.split('|')
            val timestamp = fields.getOrNull(0)?.toLongOrNull()
            val content = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
            val source = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
            val zone = fields.getOrNull(3)?.toIntOrNull()
            val valid = fields.size in 3..4 && timestamp != null && timestamp >= 0 && content != null && source != null &&
                (fields.size == 3 || (zone != null && zone in -64_800..64_800))
            return HealthImportPendingSnapshot(identity, pending, content, source, zone, valid,
                recoveryConsumed = recovery != null, recoveryAttempt = parseRecovery(recovery))
        }
    }
}
