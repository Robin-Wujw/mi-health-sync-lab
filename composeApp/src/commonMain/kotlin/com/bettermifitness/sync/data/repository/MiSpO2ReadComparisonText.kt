package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiSpO2ReadComparison
import com.bettermifitness.sync.i18n.L10n

/** Counts and fixed labels only; no target, health value or timestamp enters this formatter. */
fun MiSpO2ReadComparison.copyableText(): String = buildList {
    val prefix = "spo2_comparison"
    add("$prefix.read_only=true")
    add("$prefix.extra_cloud_requests=0")
    add("$prefix.source_targets=$sourceTargets")
    add("$prefix.exact_matched_targets=${exactMatched.targets}")
    add("$prefix.missing_targets=${missing.targets}")
    add("$prefix.other_targets=$otherTargets")
    add("$prefix.matched_minute_aligned_targets=${exactMatched.minuteAlignedTargets}")
    add("$prefix.missing_minute_aligned_targets=${missing.minuteAlignedTargets}")
    add("$prefix.matched_shared_source_minute_targets=${exactMatched.sharedSourceMinuteTargets}")
    add("$prefix.missing_shared_source_minute_targets=${missing.sharedSourceMinuteTargets}")
    add("$prefix.missing_inner_time_candidate_targets=$missingInnerTimeCandidateTargets")
    add("$prefix.missing_same_minute_candidate_targets=$missingSameMinuteCandidateTargets")
    add("$prefix.missing_same_value_candidate_targets=$missingSameValueCandidateTargets")
    add("$prefix.missing_different_value_candidate_targets=$missingDifferentValueCandidateTargets")
    add("$prefix.missing_unusable_value_candidate_targets=$missingUnusableValueCandidateTargets")
    add("$prefix.missing_multiple_candidate_targets=$missingMultipleCandidateTargets")
    add("$prefix.missing_other_source_candidate_targets=$missingOtherSourceCandidateTargets")
    add("$prefix.missing_deleted_candidate_targets=$missingDeletedCandidateTargets")
    add("$prefix.exact_outer_inner_time_different_targets=$exactOuterInnerTimeDifferentTargets")
    add("$prefix.count_notes=matched_are_observations_not_new_uploads;missing_requires_complete_target_query;other_includes_unqueried_or_conflicting;inner_time_precedes_same_minute;value_hints_may_overlap;candidates_never_verify;zero_hints_do_not_prove_absence")
}.joinToString("\n")

fun MiSpO2ReadComparison.userFacingDetails(): String = listOf(
    L10n.textFmt(L10n.spo2ComparisonSummary, exactMatched.targets, missing.targets),
    L10n.textFmt(L10n.spo2ComparisonHints, missingInnerTimeCandidateTargets, missingSameMinuteCandidateTargets),
    L10n.text(L10n.spo2ComparisonLimit),
).joinToString("\n")
