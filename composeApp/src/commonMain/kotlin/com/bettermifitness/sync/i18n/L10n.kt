package com.bettermifitness.sync.i18n

import androidx.compose.runtime.Composable
import com.bettermifitness.sync.MR
import com.bettermifitness.sync.data.preferences.SyncPreferences
// moko-resources 0.27 generates string accessors as extension vals in one
// package (see detekt.yml WildcardImport exclusion); explicit imports churn.
import com.bettermifitness.sync.*
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource

/**
 * Single localization surface for Compose, shared Kotlin state, and native SwiftUI.
 * The catalog lives in moko-resources and follows the system language.
 */
object L10n {
    val importExcludedPoints = MR.strings.import_excluded_points
    val importHrvExcludedSamples = MR.strings.import_hrv_excluded_samples
    val importOwnedHeartRateRevision = MR.strings.import_owned_heart_rate_revision
    val importOwnedHrvRevision = MR.strings.import_owned_hrv_revision
    val importOwnedRhrRevision = MR.strings.import_owned_rhr_revision
    val syncStatusWithExclusions = MR.strings.sync_status_with_exclusions
    val inboundHistoryUnavailable = MR.strings.inbound_history_unavailable
    val inboundHistoryRequired = MR.strings.inbound_history_required
    val settingsHistoryRangeHint = MR.strings.settings_history_range_hint
    @Composable
    fun string(res: StringResource): String = stringResource(res)

    @Composable
    fun stringFmt(res: StringResource, vararg args: Any): String = stringResource(res, *args)

    /** Non-Compose access for ViewModels and shared platform bridges. */
    fun text(res: StringResource): String = L10nPlatform.text(res)

    fun textFmt(res: StringResource, vararg args: Any): String = L10nPlatform.format(res, args)

    fun metric(key: String): String = text(metricResource(key))

    /** Only fixed status codes are rendered; arbitrary server/exception text is never exposed. */
    fun pendingRecoveryStatus(code: String): String = text(when (code) {
        "NOT_REQUESTED" -> MR.strings.import_pending_recovery_not_requested
        "UNSUPPORTED" -> MR.strings.import_pending_recovery_unsupported
        "NO_PENDING" -> MR.strings.import_pending_recovery_none
        "NO_PENDING_WITH_EXCLUSIONS" -> MR.strings.import_pending_recovery_none_with_exclusions
        "SOURCE_CHANGED" -> MR.strings.import_pending_recovery_source_changed
        "SOURCE_COLLISION" -> MR.strings.import_pending_recovery_source_collision
        "READ_FAILED" -> MR.strings.import_pending_recovery_read_failed
        "HISTORY_INCOMPLETE" -> MR.strings.import_pending_recovery_history_incomplete
        "CONFLICT" -> MR.strings.import_pending_recovery_conflict
        "TOMBSTONE_PRESENT" -> MR.strings.import_pending_recovery_deleted
        "OTHER_SOURCE_PRESENT" -> MR.strings.import_pending_recovery_other_source
        "ALREADY_PRESENT" -> MR.strings.import_pending_recovery_already_present
        "RETRY_ALREADY_CONSUMED" -> MR.strings.import_pending_recovery_consumed
        "RESERVATION_CHANGED" -> MR.strings.import_pending_recovery_reservation_changed
        "UPLOAD_REJECTED" -> MR.strings.import_pending_recovery_rejected
        "UPLOAD_UNVERIFIED" -> MR.strings.import_pending_recovery_unverified
        "VERIFIED" -> MR.strings.import_pending_recovery_verified
        else -> MR.strings.import_pending_recovery_unknown
    })

    fun pendingReconcileStatus(code: String): String = text(when (code) {
        "NOT_REQUESTED" -> MR.strings.import_pending_reconcile_not_requested
        "UNSUPPORTED" -> MR.strings.import_pending_reconcile_unsupported
        "NO_PENDING" -> MR.strings.import_pending_reconcile_none
        "COMPLETE" -> MR.strings.import_pending_reconcile_complete
        "PARTIAL" -> MR.strings.import_pending_reconcile_partial
        "INCOMPLETE" -> MR.strings.import_pending_reconcile_incomplete
        "LOCAL_SAVE_FAILED" -> MR.strings.import_pending_reconcile_save_failed
        else -> MR.strings.import_pending_reconcile_unknown
    })

    fun importWarning(code: String): String {
        val resource = when (code) {
            "SOURCE_TIMEZONE_UNAVAILABLE" -> MR.strings.import_warning_timezone_unavailable
            "SOURCE_TIMEZONE_CHANGED" -> MR.strings.import_warning_timezone_changed
            "SOURCE_TIMEZONE_NOT_QUARTER_HOUR" -> MR.strings.import_warning_timezone_unusual
            "PARTIAL_SLEEP_STAGES" -> MR.strings.import_warning_partial_sleep_stages
            "SUBSECOND_PRECISION" -> MR.strings.import_warning_subsecond_precision
            "SUBSECOND_SLEEP_STAGE" -> MR.strings.import_warning_subsecond_sleep_stage
            "INCOMPLETE_LOCAL_DAY" -> MR.strings.import_warning_incomplete_day
            "RESTING_HEART_RATE_RAW_ONLY" -> MR.strings.import_warning_resting_heart_rate_raw_only
            "SOURCE_REVISION_DEDUPLICATED" -> MR.strings.import_warning_source_revision
            "EMPTY_SAMPLES" -> MR.strings.import_warning_empty_samples
            "OVERLAPPING_SLEEP_SESSIONS" -> MR.strings.import_warning_overlapping_sleep
            "EXISTING_DAILY_REPORT_PRESERVED" -> MR.strings.import_warning_existing_daily_report
            "TEST_MODE_SINGLE_RECORD" -> MR.strings.import_warning_test_mode
            "LEGACY_SLEEP_HRV_ENRICHMENT" -> MR.strings.import_warning_legacy_hrv
            "OWNED_SLEEP_REVISION" -> MR.strings.import_warning_owned_sleep_revision
            "RHR_DAILY_CHECK_FAILED" -> MR.strings.import_warning_rhr_daily_check_failed
            "RMSSD_EXPERIMENTAL_MAPPING" -> MR.strings.import_warning_rmssd_experimental
            "HRV_INTEGER_ROUNDING" -> MR.strings.import_warning_hrv_rounding
            "HRV_AWAKE_SAMPLE_EXCLUDED" -> MR.strings.import_warning_hrv_awake_excluded
            "HRV_SLEEP_RANGE_ASSUMPTION" -> MR.strings.import_warning_hrv_sleep_range
            "HRV_DUPLICATE_SAMPLE" -> MR.strings.import_warning_hrv_duplicate
            "HRV_NO_MATCHING_SLEEP" -> MR.strings.import_issue_hrv_no_sleep
            "HRV_AMBIGUOUS_SLEEP" -> MR.strings.import_issue_hrv_ambiguous_sleep
            "INVALID_HRV_SAMPLE" -> MR.strings.import_issue_hrv_invalid
            "UNREPRESENTABLE_HRV_SUMMARY" -> MR.strings.import_issue_hrv_unrepresentable
            "CONFLICTING_HRV_REVISIONS" -> MR.strings.import_issue_hrv_revisions
            "HRV_TIME_COLLISION" -> MR.strings.import_issue_hrv_collision
            "HRV_ENRICHMENT_CHANGED" -> MR.strings.import_issue_hrv_changed
            "SLEEP_REVISION_CHANGED" -> MR.strings.import_issue_sleep_revision_changed
            "RESTING_HEART_RATE_DATE_UNKNOWN" -> MR.strings.import_issue_rhr_date_unknown
            "INVALID_RESTING_HEART_RATE_DATE" -> MR.strings.import_issue_rhr_invalid_date
            "RESTING_HEART_RATE_DAY_COLLISION" -> MR.strings.import_issue_rhr_collision
            "RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED" -> MR.strings.import_warning_rhr_daily_minimum
            "INVALID_OXYGEN_SATURATION" -> MR.strings.import_issue_spo2_invalid
            "UNREPRESENTABLE_OXYGEN_SATURATION" -> MR.strings.import_issue_spo2_unrepresentable
            "OXYGEN_SATURATION_PRECISION" -> MR.strings.import_warning_spo2_precision
            "INVALID_INTERVAL" -> MR.strings.import_issue_heart_interval
            "INVALID_HEART_RATE_SAMPLE" -> MR.strings.import_issue_heart_sample
            "INVALID_RESTING_HEART_RATE" -> MR.strings.import_issue_rhr_invalid
            "INVALID_SLEEP_INTERVAL" -> MR.strings.import_issue_sleep_interval
            "INVALID_SLEEP_STAGES" -> MR.strings.import_issue_sleep_stages
            "SUBSECOND_SLEEP_INTERVAL" -> MR.strings.import_issue_sleep_precision
            "INVALID_SOURCE" -> MR.strings.import_issue_source_invalid
            "CONFLICTING_SOURCE_REVISIONS" -> MR.strings.import_issue_source_revisions
            "TARGET_TIME_COLLISION", "source_target_collision" -> MR.strings.import_issue_target_collision
            "HEART_RATE_SOURCE_POINT_EXCLUDED" -> MR.strings.import_heart_source_point_excluded
            "REJECTED_SOURCE_PREVENTS_DAILY_REPORT" -> MR.strings.import_issue_source_prevents_daily
            "remaining_batches_not_submitted" -> MR.strings.import_issue_batches_not_submitted
            "UNKNOWN" -> MR.strings.import_issue_unknown
            else -> return code
        }
        return text(resource)
    }

    fun metricResource(key: String): StringResource = when (key) {
        "heart_rate" -> MR.strings.metric_heart_rate
        "resting_heart_rate" -> MR.strings.metric_resting_heart_rate
        "sleep" -> MR.strings.metric_sleep
        "hrv" -> MR.strings.metric_hrv
        "steps" -> MR.strings.metric_steps
        "distance" -> MR.strings.metric_distance
        "active_calories" -> MR.strings.metric_active_calories
        "spo2" -> MR.strings.metric_spo2
        "weight" -> MR.strings.metric_weight
        "workouts" -> MR.strings.metric_workouts
        "blood_pressure" -> MR.strings.metric_blood_pressure
        "temperature" -> MR.strings.metric_temperature
        "vo2_max" -> MR.strings.metric_vo2_max
        SyncPreferences.HEART_RATE_IMPORT_KEY -> MR.strings.metric_import_heart_rate
        SyncPreferences.RESTING_HEART_RATE_IMPORT_KEY -> MR.strings.metric_import_resting_heart_rate
        SyncPreferences.SLEEP_IMPORT_KEY -> MR.strings.metric_import_sleep
        SyncPreferences.HRV_IMPORT_KEY -> MR.strings.metric_import_hrv
        SyncPreferences.SPO2_IMPORT_KEY -> MR.strings.metric_import_spo2
        else -> MR.strings.metric_workouts
    }

    // Common
    val appName get() = MR.strings.app_name
    val back get() = MR.strings.back
    val openHealth get() = MR.strings.open_health
    val healthFallback get() = MR.strings.health_fallback
    val healthAllowAccess get() = MR.strings.health_allow_access
    val healthReady get() = MR.strings.health_ready
    val healthChecking get() = MR.strings.health_checking
    val healthNotAvailable get() = MR.strings.health_not_available
    val healthPermissionsIncomplete get() = MR.strings.health_permissions_incomplete
    val healthStatusCheckFailed get() = MR.strings.health_status_check_failed
    val healthWriteFailed get() = MR.strings.health_write_failed

    // Login
    val loginTitle get() = MR.strings.login_title
    val loginSubtitle get() = MR.strings.login_subtitle
    val loginEmailOrPhone get() = MR.strings.login_email_or_phone
    val loginPassword get() = MR.strings.login_password
    val loginSignIn get() = MR.strings.login_sign_in
    val loginPrivacy get() = MR.strings.login_privacy
    val loginVerifyIdentity get() = MR.strings.login_verify_identity
    val loginCheckEmail get() = MR.strings.login_check_email
    val loginCodeSent get() = MR.strings.login_code_sent
    val loginCodeSentTo get() = MR.strings.login_code_sent_to
    val loginVerify get() = MR.strings.login_verify
    val loginDidntGet get() = MR.strings.login_didnt_get
    val loginResend get() = MR.strings.login_resend
    val loginBrowserTrouble get() = MR.strings.login_browser_trouble
    val loginBrowserTitle get() = MR.strings.login_browser_title
    val loginComplete get() = MR.strings.login_complete
    val loginStepOne get() = MR.strings.login_step_one
    val loginStepOneDetail get() = MR.strings.login_step_one_detail
    val loginOpenXiaomi get() = MR.strings.login_open_xiaomi
    val loginLinkCopied get() = MR.strings.login_link_copied
    val loginCopyLink get() = MR.strings.login_copy_link
    val loginStepTwo get() = MR.strings.login_step_two
    val loginStepTwoDetail get() = MR.strings.login_step_two_detail
    val loginLinkPasted get() = MR.strings.login_link_pasted
    val loginPasteDifferent get() = MR.strings.login_paste_different
    val loginRemoveLink get() = MR.strings.login_remove_link
    val loginTapPaste get() = MR.strings.login_tap_paste
    val loginCopyFromBrowser get() = MR.strings.login_copy_from_browser
    val loginReady get() = MR.strings.login_ready
    val loginTip get() = MR.strings.login_tip
    val loginNothingToPaste get() = MR.strings.login_nothing_to_paste
    val loginInvalidLink get() = MR.strings.login_invalid_link
    val loginNotWebLink get() = MR.strings.login_not_web_link
    val loginVerificationCode get() = MR.strings.login_verification_code
    val loginEmpty get() = MR.strings.login_empty
    val loginDigits get() = MR.strings.login_digits
    val loginSendCodeFailed get() = MR.strings.login_send_code_failed
    val loginFailed get() = MR.strings.login_failed
    val loginVerificationFailed get() = MR.strings.login_verification_failed
    val loginBrowserRequired get() = MR.strings.login_browser_required
    val loginCodeResent get() = MR.strings.login_code_resent
    val loginResendFailed get() = MR.strings.login_resend_failed
    val loginPasteRedirect get() = MR.strings.login_paste_redirect
    val loginBrowserFinishFailed get() = MR.strings.login_browser_finish_failed
    val loginMissingPassToken get() = MR.strings.login_missing_pass_token
    val loginMissingDeviceId get() = MR.strings.login_missing_device_id
    val loginCaptchaTitle get() = MR.strings.login_captcha_title
    val loginCaptchaDetail get() = MR.strings.login_captcha_detail
    val loginCaptchaCode get() = MR.strings.login_captcha_code
    val loginCaptchaContinue get() = MR.strings.login_captcha_continue
    val loginCaptchaRefresh get() = MR.strings.login_captcha_refresh
    val loginCaptchaLoading get() = MR.strings.login_captcha_loading
    val loginCaptchaWrong get() = MR.strings.login_captcha_wrong
    val loginCaptchaEmpty get() = MR.strings.login_captcha_empty
    val loginCaptchaLoadFailed get() = MR.strings.login_captcha_load_failed

    // Home
    val homeAccount get() = MR.strings.home_account
    val homeSignedIn get() = MR.strings.home_signed_in
    val homeConnected get() = MR.strings.home_connected
    val homeLoadingProfile get() = MR.strings.home_loading_profile
    val homeSetup get() = MR.strings.home_setup
    val homeActivity get() = MR.strings.home_activity
    val homeSyncOptions get() = MR.strings.home_sync_options
    val homeNoneSelected get() = MR.strings.home_none_selected
    val homeMetricOne get() = MR.strings.home_metric_one
    val homeMetricMany get() = MR.strings.home_metric_many
    val homeDayOne get() = MR.strings.home_day_one
    val homeDayMany get() = MR.strings.home_day_many
    val homeAutoOn get() = MR.strings.home_auto_on
    val homeManual get() = MR.strings.home_manual
    val homeNothingSelectedTitle get() = MR.strings.home_nothing_selected_title
    val homeNothingSelectedDetail get() = MR.strings.home_nothing_selected_detail
    val homeAllowAccessDetail get() = MR.strings.home_allow_access_detail
    val homeLastSync get() = MR.strings.home_last_sync
    val homeBackgroundSync get() = MR.strings.home_background_sync
    val homeNever get() = MR.strings.home_never
    val homeSyncing get() = MR.strings.home_syncing
    val homeSyncNow get() = MR.strings.home_sync_now
    val homeOpenSettings get() = MR.strings.home_open_settings
    val homeFailedSuffix get() = MR.strings.home_failed_suffix
    val homePartialSuffix get() = MR.strings.home_partial_suffix
    val homeSettingsAccessibility get() = MR.strings.home_settings_accessibility
    val homeHealthAccessibility get() = MR.strings.home_health_accessibility
    val homeHealthReadyAccessibility get() = MR.strings.home_health_ready_accessibility
    val homeSettingsHint get() = MR.strings.home_settings_hint

    // Sync
    val syncTitle get() = MR.strings.sync_title
    val syncLastOneDay get() = MR.strings.sync_last_one_day
    val syncLastNDays get() = MR.strings.sync_last_n_days
    val syncNow get() = MR.strings.sync_now
    val syncOpenHealth get() = MR.strings.sync_open_health
    val syncNoMetrics get() = MR.strings.sync_no_metrics
    val syncPreparing get() = MR.strings.sync_preparing
    val syncStatusSyncing get() = MR.strings.sync_status_syncing
    val syncStatusDone get() = MR.strings.sync_status_done
    val syncStatusFailed get() = MR.strings.sync_status_failed
    val syncStatusWaiting get() = MR.strings.sync_status_waiting
    val syncRecords get() = MR.strings.sync_records
    val syncFailed get() = MR.strings.sync_failed
    val syncNotLoggedIn get() = MR.strings.sync_not_logged_in
    val syncNothingToSync get() = MR.strings.sync_nothing_to_sync
    val syncAllMetricsSynced get() = MR.strings.sync_all_metrics_synced
    val syncHealthUnavailable get() = MR.strings.sync_health_unavailable

    // Relative time
    val timeJustNow get() = MR.strings.time_just_now
    val timeMinuteAgo get() = MR.strings.time_minute_ago
    val timeHourOneAgo get() = MR.strings.time_hour_one_ago
    val timeHourManyAgo get() = MR.strings.time_hour_many_ago
    val timeYesterday get() = MR.strings.time_yesterday
    val timeDayOneAgo get() = MR.strings.time_day_one_ago
    val timeDayManyAgo get() = MR.strings.time_day_many_ago
    val timeUnknown get() = MR.strings.time_unknown

    // Outcome/status
    val outcomeUpToDate get() = MR.strings.outcome_up_to_date
    val outcomeAlmostDone get() = MR.strings.outcome_almost_done
    val outcomeCouldNotFinish get() = MR.strings.outcome_could_not_finish
    val outcomePleaseSignIn get() = MR.strings.outcome_please_sign_in
    val outcomePartialLabel get() = MR.strings.outcome_partial_label
    val outcomeHealthNeeded get() = MR.strings.outcome_health_needed
    val outcomeNothingToDo get() = MR.strings.outcome_nothing_to_do
    val outcomeStopped get() = MR.strings.outcome_stopped
    val outcomeInProgress get() = MR.strings.outcome_in_progress
    val outcomeNotSynced get() = MR.strings.outcome_not_synced
    val outcomeSuccessDetail get() = MR.strings.outcome_success_detail
    val outcomePartialDetail get() = MR.strings.outcome_partial_detail
    val outcomeFailedDetail get() = MR.strings.outcome_failed_detail
    val outcomeLoginDetail get() = MR.strings.outcome_login_detail
    val outcomeHealthDetail get() = MR.strings.outcome_health_detail
    val outcomeSkippedDetail get() = MR.strings.outcome_skipped_detail
    val outcomeCancelledDetail get() = MR.strings.outcome_cancelled_detail
    val outcomeRunningDetail get() = MR.strings.outcome_running_detail
    val outcomeIdleDetail get() = MR.strings.outcome_idle_detail
    val outcomeNothingNeeded get() = MR.strings.outcome_nothing_needed
    val outcomeSignInAgain get() = MR.strings.outcome_sign_in_again
    val outcomeHealthUnavailable get() = MR.strings.outcome_health_unavailable

    // Settings
    val settingsTitle get() = MR.strings.settings_title
    val settingsSyncOptionsLoading get() = MR.strings.settings_sync_options_loading
    val settingsSectionSyncRange get() = MR.strings.settings_section_sync_range
    val settingsSectionAutoSync get() = MR.strings.settings_section_auto_sync
    val settingsSectionMetrics get() = MR.strings.settings_section_metrics
    val settingsSectionStatus get() = MR.strings.settings_section_status
    val settingsSectionShortcuts get() = MR.strings.settings_section_shortcuts
    val settingsSectionAccount get() = MR.strings.settings_section_account
    val settingsSectionAbout get() = MR.strings.settings_section_about
    val settingsDaysPicker get() = MR.strings.settings_days_picker
    val settingsRange1Day get() = MR.strings.settings_range_1_day
    val settingsRangeNDays get() = MR.strings.settings_range_n_days
    val settingsBackgroundAutoSync get() = MR.strings.settings_background_auto_sync
    val settingsTestBackgroundRefresh get() = MR.strings.settings_test_background_refresh
    val settingsRunningRefresh get() = MR.strings.settings_running_refresh
    val settingsTestOk get() = MR.strings.settings_test_ok
    val settingsPartialOk get() = MR.strings.settings_partial_ok
    val settingsSkipped get() = MR.strings.settings_skipped
    val settingsNotSignedIn get() = MR.strings.settings_not_signed_in
    val settingsCancelled get() = MR.strings.settings_cancelled
    val settingsFailedStatus get() = MR.strings.settings_failed_status
    val settingsLastSync get() = MR.strings.settings_last_sync
    val settingsLastBackgroundSync get() = MR.strings.settings_last_background_sync
    val settingsShortcutsHelp get() = MR.strings.settings_shortcuts_help
    val settingsLogOut get() = MR.strings.settings_log_out
    val settingsVersion get() = MR.strings.settings_version
    val settingsCredit get() = MR.strings.settings_credit
    val settingsCreditName get() = MR.strings.settings_credit_name
    val backgroundAndroidStatus get() = MR.strings.background_android_status
    val backgroundSimulatorStatus get() = MR.strings.background_simulator_status
    val backgroundOn get() = MR.strings.background_on
    val backgroundOff get() = MR.strings.background_off
    val backgroundRestricted get() = MR.strings.background_restricted
    val backgroundUnknown get() = MR.strings.background_unknown
    val backgroundUnknownStatus get() = MR.strings.background_unknown_status

    // Health Sync Lab
    val syncCounterDependencyFailed get() = MR.strings.sync_counter_dependency_failed
    val syncStatusNoData get() = MR.strings.sync_status_no_data
    val syncNoUsableData get() = MR.strings.sync_no_usable_data
    val syncAlreadyMatched get() = MR.strings.sync_already_matched
    val syncDirectionSummary get() = MR.strings.sync_direction_summary
    val syncToHealthTitle get() = MR.strings.sync_to_health_title
    val syncWeightTitle get() = MR.strings.sync_weight_title
    val syncInboundTitle get() = MR.strings.sync_inbound_title
    val syncStatusCloudVerified get() = MR.strings.sync_status_cloud_verified
    val syncImportRecords get() = MR.strings.sync_import_records
    val syncImportNoNewRecords get() = MR.strings.sync_import_no_new_records
    val settingsToHealthDetail get() = MR.strings.settings_to_health_detail
    val settingsWeightDetail get() = MR.strings.settings_weight_detail
    val settingsInboundDetail get() = MR.strings.settings_inbound_detail
    val settingsImportAdvanced get() = MR.strings.settings_import_advanced
    val settingsInboundFirstRun get() = MR.strings.settings_inbound_first_run
    val settingsHrvDetail get() = MR.strings.settings_hrv_detail
    val settingsZeppCapabilityDetail get() = MR.strings.settings_zepp_capability_detail
    val settingsInboundPermissions get() = MR.strings.settings_inbound_permissions
    val settingsInboundPermissionsReady get() = MR.strings.settings_inbound_permissions_ready
    val settingsInboundPermissionsMissing get() = MR.strings.settings_inbound_permissions_missing
    val settingsInboundPermissionsFailed get() = MR.strings.settings_inbound_permissions_failed
    val settingsBodyTitle get() = MR.strings.settings_body_title
    val settingsBodyToggle get() = MR.strings.settings_body_toggle
    val settingsBodyDetail get() = MR.strings.settings_body_detail
    val settingsRetryTitle get() = MR.strings.settings_retry_title
    val importCloudConflict get() = MR.strings.import_cloud_conflict
    val importPendingVerification get() = MR.strings.import_pending_verification
    val importCloudRejected get() = MR.strings.import_cloud_rejected
    val importCloudUnverified get() = MR.strings.import_cloud_unverified
    val importRowsRejected get() = MR.strings.import_rows_rejected
    val importRunDetail get() = MR.strings.import_run_detail
    val settingsBodyRetry get() = MR.strings.settings_body_retry
    val settingsBodyRetryDetail get() = MR.strings.settings_body_retry_detail
    val settingsBodyRetryReady get() = MR.strings.settings_body_retry_ready
    val bodyImportNotVerified get() = MR.strings.body_import_not_verified
    val bodyImportRejected get() = MR.strings.body_import_rejected
    val settingsPermissionsTitle get() = MR.strings.settings_permissions_title
    val settingsPermissionsDetail get() = MR.strings.settings_permissions_detail
    val hrvAlgorithmUnconfirmed get() = MR.strings.hrv_algorithm_unconfirmed
    val healthPartialWrite get() = MR.strings.health_partial_write
    val settingsInboundTestMode get() = MR.strings.settings_inbound_test_mode
    val settingsInboundTestModeDetail get() = MR.strings.settings_inbound_test_mode_detail
    val settingsLegacyHrv get() = MR.strings.settings_legacy_hrv
    val settingsLegacyHrvDetail get() = MR.strings.settings_legacy_hrv_detail
    val settingsOwnedSleepRevision get() = MR.strings.settings_owned_sleep_revision
    val settingsOwnedSleepRevisionDetail get() = MR.strings.settings_owned_sleep_revision_detail
    val recoveryHistoryUnavailable get() = MR.strings.recovery_history_unavailable
    val spo2ComparisonSummary get() = MR.strings.spo2_comparison_summary
    val spo2ComparisonHints get() = MR.strings.spo2_comparison_hints
    val spo2ComparisonLimit get() = MR.strings.spo2_comparison_limit
    val importAcceptanceUnknown get() = MR.strings.import_acceptance_unknown
    val recoveryHistoryAccepted get() = MR.strings.recovery_history_accepted
    val recoveryHistoryRejected get() = MR.strings.recovery_history_rejected
    val recoveryHistoryReserved get() = MR.strings.recovery_history_reserved
    val recoveryHistoryAcceptanceUnknown get() = MR.strings.recovery_history_acceptance_unknown
    val recoveryHistoryBusinessCode get() = MR.strings.recovery_history_business_code
    val recoveryHistoryReadbackVerified get() = MR.strings.recovery_history_readback_verified
    val recoveryHistoryReadbackMissing get() = MR.strings.recovery_history_readback_missing
    val recoveryHistoryReadbackMismatch get() = MR.strings.recovery_history_readback_mismatch
    val recoveryHistoryReadbackAmbiguous get() = MR.strings.recovery_history_readback_ambiguous
    val recoveryHistoryReadbackUnqueried get() = MR.strings.recovery_history_readback_unqueried
    val recoveryHistoryReadbackUnknown get() = MR.strings.recovery_history_readback_unknown
    val importSleepRevisionAvailable get() = MR.strings.import_sleep_revision_available
    val importSleepRevisionResult get() = MR.strings.import_sleep_revision_result
    val syncStatusTestVerified get() = MR.strings.sync_status_test_verified
    val syncDiagnosticsShow get() = MR.strings.sync_diagnostics_show
    val syncDiagnosticsHide get() = MR.strings.sync_diagnostics_hide
    val syncDiagnosticsCopy get() = MR.strings.sync_diagnostics_copy
    val syncDiagnosticsCopied get() = MR.strings.sync_diagnostics_copied
    val syncDiagnosticsCopyFailed get() = MR.strings.sync_diagnostics_copy_failed
    val syncDiagnosticsPrivacy get() = MR.strings.sync_diagnostics_privacy
    val importAffectedRecords get() = MR.strings.import_affected_records
    val importPrecheckFailed get() = MR.strings.import_precheck_failed
    val importServerCode get() = MR.strings.import_server_code
    val importHttpStatus get() = MR.strings.import_http_status
    val importServerMessage get() = MR.strings.import_server_message
    val importMessageWithheld get() = MR.strings.import_message_withheld
    val importStagePrepare get() = MR.strings.import_stage_prepare
    val importStagePrecheck get() = MR.strings.import_stage_precheck
    val importStageUpload get() = MR.strings.import_stage_upload
    val importStageReadback get() = MR.strings.import_stage_readback
    val importStageComplete get() = MR.strings.import_stage_complete
    val importChannelRaw get() = MR.strings.import_channel_raw
    val importChannelDaily get() = MR.strings.import_channel_daily
    val importChannelAll get() = MR.strings.import_channel_all
    val importDiagnosticStage get() = MR.strings.import_diagnostic_stage
    val importDiagnosticBatch get() = MR.strings.import_diagnostic_batch
    val importDiagnosticCounts get() = MR.strings.import_diagnostic_counts
    val importBusinessAccepted get() = MR.strings.import_business_accepted
    val importBusinessRejected get() = MR.strings.import_business_rejected
    val rhrDisplayCheckDetail get() = MR.strings.rhr_display_check_detail
    val pendingProbeSummary get() = MR.strings.import_pending_probe_summary
    val pendingProbeNarrowSummary get() = MR.strings.import_pending_probe_narrow_summary
    val pendingProbeNativeSummary get() = MR.strings.import_pending_probe_native_summary
    val pendingProbeOtherSources get() = MR.strings.import_pending_probe_other_sources
    val pendingProbeFailed get() = MR.strings.import_pending_probe_failed
    val pendingRecoveryLegacy get() = MR.strings.import_pending_recovery_legacy
    val pendingRecoverySummary get() = MR.strings.import_pending_recovery_summary
    val pendingReconcileSummary get() = MR.strings.import_pending_reconcile_summary
    val pendingReconcileCloudOnly get() = MR.strings.import_pending_reconcile_cloud_only
    val pendingReconcileBlocked get() = MR.strings.import_pending_reconcile_blocked
    val pendingReconcileUnresolved get() = MR.strings.import_pending_reconcile_unresolved
    val pendingReconcileHistory get() = MR.strings.import_pending_reconcile_history
    val pendingReconcileHistoryIncomplete get() = MR.strings.import_pending_reconcile_history_incomplete
    val syncReconcileDetail get() = MR.strings.sync_reconcile_detail
    val syncReconcileAction get() = MR.strings.sync_reconcile_action
    val syncRecoveryTitle get() = MR.strings.sync_recovery_title
    val syncRecoveryDetail get() = MR.strings.sync_recovery_detail
    val syncRecoveryAction get() = MR.strings.sync_recovery_action
    val syncRecoveryCounts get() = MR.strings.sync_recovery_counts
    val syncRecoveryResult get() = MR.strings.sync_recovery_result
    val syncRecoveryRange get() = MR.strings.sync_recovery_range
    val syncRecoveryEnableSpO2 get() = MR.strings.sync_recovery_enable_spo2
    val syncRecoveryBusy get() = MR.strings.sync_recovery_busy
    val syncRecoveryUnavailable get() = MR.strings.sync_recovery_unavailable
    val syncRecoveryCancelled get() = MR.strings.sync_recovery_cancelled
    val syncRecoveryReadAccess get() = MR.strings.sync_recovery_read_access
    val syncRecoveryAuth get() = MR.strings.sync_recovery_auth
    val syncRecoveryNetwork get() = MR.strings.sync_recovery_network
    val syncRecoveryRateLimited get() = MR.strings.sync_recovery_rate_limited
    val syncRecoveryFailed get() = MR.strings.sync_recovery_failed
    val syncStatusPartialVerified get() = MR.strings.sync_status_partial_verified
    val importPartialProgress get() = MR.strings.import_partial_progress
    val importDiagnosticRawProgress get() = MR.strings.import_diagnostic_raw_progress
    val importDiagnosticReason get() = MR.strings.import_diagnostic_reason
    val importDiagnosticBlocked get() = MR.strings.import_diagnostic_blocked
    val importBlockedVerifiedHistory get() = MR.strings.import_blocked_verified_history
    val importDiagnosticReadback get() = MR.strings.import_diagnostic_readback
    val importDiagnosticPrecheckFields get() = MR.strings.import_diagnostic_precheck_fields
    val importDiagnosticReadbackFields get() = MR.strings.import_diagnostic_readback_fields
    val importDiagnosticPreservedDaily get() = MR.strings.import_diagnostic_preserved_daily
    val importDiagnosticRejectedSource get() = MR.strings.import_diagnostic_rejected_source
    val importDiagnosticIssue get() = MR.strings.import_diagnostic_issue
    val importDiagnosticIssueOverlap get() = MR.strings.import_diagnostic_issue_overlap
    val importIssueRejected get() = MR.strings.import_issue_rejected
    val importIssueWarning get() = MR.strings.import_issue_warning
    val importIssueUnknown get() = MR.strings.import_issue_unknown
    val importReasonConflict get() = MR.strings.import_reason_conflict
    val importReasonPending get() = MR.strings.import_reason_pending
    val importReasonRejected get() = MR.strings.import_reason_rejected
    val importReasonUnverified get() = MR.strings.import_reason_unverified
    val importReasonSourceRejected get() = MR.strings.import_reason_source_rejected
}
