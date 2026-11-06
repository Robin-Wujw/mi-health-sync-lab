<#
.SYNOPSIS
Run the same-signer Mi Health Sync Lab test APK through an authorized ADB device.

.DESCRIPTION
Defaults to read-only health-data inspection of SpO2 for the last three days.
Uses the installed app's account, source selection, and Health Connect grants.
Only installs APKs if their paths are explicitly supplied. Never clears app data,
uninstalls the main app, grants permissions, exports tokens, or reads raw logcat.

sync-one requires -AllowCloudWrite and considers at most one raw target through
the normal import runner. It does not force retries of pending/conflicting rows.
recover-one-pending also requires -AllowCloudWrite and -Metric spo2. It considers
at most one existing pending target through the dedicated recovery workflow.
Prechecks can result in zero uploads; no pending set is cleared globally.
No automatic repetition of a failed instrumentation command is performed.

reconcile-pending supports only -Metric spo2 and must omit -AllowCloudWrite.
It reads the cloud and updates matched local pending entries in one action.
It uploads no health data and leaves unmatched/conflicting entries pending.

verify-existing-spo2 uses normal raw import prechecks with new reservations and
POSTs disabled. It may record existing observations in the local journal.
inspect-spo2-minute only reads source, journal and native cloud history.

After the command, the app process is stopped to release the test-session gate.
Reopen the normal app to resume normal foreground/background synchronization.
#>
[CmdletBinding()]
param(
    [string]$AdbPath = ".\adb.exe",
    [string]$Serial,
    [ValidateSet("inspect", "inspect-spo2-minute", "verify-existing-spo2", "inspect-heart-history", "inspect-heart-coverage", "preview-heart-recovery", "reconcile-heart", "verify-existing-heart", "inspect-hc-chain", "sync-one", "sync-one-day", "repair-one", "recover-one-pending", "reconcile-pending")]
    [string]$Mode = "inspect",
    [ValidateSet("spo2", "resting_heart_rate", "heart_rate", "hrv", "sleep")]
    [string]$Metric = "spo2",
    [ValidateRange(1, 30)]
    [int]$RangeDays = 3,
    [switch]$AllowCloudWrite,
    [string]$AppApk,
    [string]$TestApk,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$TargetPackage = "com.bettermifitness.sync.healthlab"
$TestComponent = "com.bettermifitness.sync.healthlab.test/com.bettermifitness.sync.lab.HealthLabInstrumentation"
$ExpectedVersionName = "1.0.6-healthlab.20"
$ExpectedVersionCode = "1000026"
$Mode = $Mode.ToLowerInvariant()
$Metric = $Metric.ToLowerInvariant()

$isWriteMode = $Mode -in @("sync-one", "sync-one-day", "repair-one", "recover-one-pending")
if ($Mode -eq "sync-one-day" -and $RangeDays -ne 1) { throw "sync-one-day requires -RangeDays 1." }
if ($isWriteMode -and -not $AllowCloudWrite) {
    throw "$Mode requires -AllowCloudWrite. Omit it and use -Mode inspect for read-only health-data checks."
}
if (-not $isWriteMode -and $AllowCloudWrite) {
    throw "-AllowCloudWrite is not valid with $Mode."
}
if ($Mode -in @("recover-one-pending", "reconcile-pending", "inspect-spo2-minute", "verify-existing-spo2") -and $Metric -ne "spo2") {
    throw "$Mode only supports -Metric spo2."
}
if ($Serial -and $Serial -notmatch "^[A-Za-z0-9._:-]{1,128}$") {
    throw "Invalid ADB serial argument."
}

$AdbExecutable = (Get-Command $AdbPath -CommandType Application -ErrorAction Stop).Source

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$AdbArguments,
        [Parameter(Mandatory = $true)]
        [string]$Action
    )
    # Capture native stderr without printing it or writing raw output to a report.
    # Use a local preference so Windows PowerShell 5.1 does not throw before we
    # can check the native exit code.
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& $AdbExecutable @AdbArguments 2>&1 | ForEach-Object { $_.ToString() })
        $nativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedErrorPreference
    }
    if ($nativeExitCode -ne 0) {
        throw "ADB $Action failed (exit code $nativeExitCode). Check USB debugging, device authorization, and the supplied APKs."
    }
    return $lines
}

function Resolve-Apk {
    param([string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf) -or
        [System.IO.Path]::GetExtension($resolved) -ne ".apk") {
        throw "An explicitly supplied APK path must point to an existing .apk file."
    }
    return $resolved
}

$deviceLines = @(Invoke-Adb -AdbArguments @("devices") -Action "device listing")
$devices = @()
foreach ($line in $deviceLines) {
    if ($line -match "^(\S+)\s+device\s*$") { $devices += $Matches[1] }
}
if ($Serial) {
    if ($Serial -notin $devices) {
        throw "The requested device is not connected and authorized. Check the USB connection and phone's debugging prompt."
    }
}
elseif ($devices.Count -eq 1) {
    $Serial = $devices[0]
}
elseif ($devices.Count -eq 0) {
    throw "No connected, authorized ADB device. Connect and unlock the phone, then approve USB debugging."
}
else {
    throw "More than one authorized ADB device. Select one explicitly with -Serial."
}
$deviceArguments = @("-s", $Serial)

if ($AppApk) {
    $resolvedApp = Resolve-Apk -Path $AppApk
    Write-Host "Updating the main app in place; existing app data is retained."
    Invoke-Adb -AdbArguments ($deviceArguments + @("install", "-r", $resolvedApp)) -Action "main APK installation" | Out-Null
}
if ($TestApk) {
    $resolvedTest = Resolve-Apk -Path $TestApk
    Write-Host "Installing the separate same-signer test APK."
    Invoke-Adb -AdbArguments ($deviceArguments + @("install", "-t", "-r", $resolvedTest)) -Action "test APK installation" | Out-Null
}

# Inspect only version fields in memory; do not save the package dump.
$packageInfo = @(Invoke-Adb -AdbArguments ($deviceArguments + @("shell", "dumpsys", "package", $TargetPackage)) -Action "version check") -join ([Environment]::NewLine)
$versionCodeMatch = [regex]::Match($packageInfo, "(?m)^\s*versionCode=(\d+)\b")
$versionNameMatch = [regex]::Match($packageInfo, "(?m)^\s*versionName=([^\r\n]+)")
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success -or
    $versionCodeMatch.Groups[1].Value -ne $ExpectedVersionCode -or
    $versionNameMatch.Groups[1].Value.Trim() -ne $ExpectedVersionName) {
    throw "The target app must be $ExpectedVersionName ($ExpectedVersionCode). Supply the matching main APK with -AppApk."
}

$instrumentations = @(Invoke-Adb -AdbArguments ($deviceArguments + @("shell", "pm", "list", "instrumentation", $TargetPackage)) -Action "test APK check") -join ([Environment]::NewLine)
$componentPattern = "instrumentation:" + [regex]::Escape($TestComponent) + "\s+\(target=" + [regex]::Escape($TargetPackage) + "\)"
if ($instrumentations -notmatch $componentPattern) {
    throw "The matching test APK is missing. Supply the matching test APK with -TestApk."
}

if (-not $OutputPath) {
    $OutputPath = Join-Path $PSScriptRoot ("mi-health-live-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt")
}
$reportDirectory = Split-Path -Parent ([System.IO.Path]::GetFullPath($OutputPath))
if (-not (Test-Path -LiteralPath $reportDirectory -PathType Container)) {
    throw "The report's parent directory must already exist."
}

Write-Host "Keep the phone unlocked and leave the app in the foreground during the test."
Write-Host ("Running " + $Mode + " for " + $Metric + " over " + $RangeDays + " days.")
if ($Mode -eq "sync-one") {
    Write-Host "This permits at most one raw import target and no daily-summary write; readback can involve several requests."
}
elseif ($Mode -eq "recover-one-pending") {
    Write-Host "This considers at most one existing SpO2 pending target, with no daily-summary write or global pending reset."
    Write-Host "Prechecks can result in zero uploads. Failed or uncertain recovery is not automatically repeated."
}
elseif ($Mode -eq "reconcile-pending") {
    Write-Host "This reads Xiaomi cloud and resolves matched local SpO2 pending entries. It uploads no health data."
    Write-Host "Unmatched, conflicting, changed or unqueried entries remain pending. Existing recovery allowances are retained."
}

$allowWritesValue = if ($AllowCloudWrite) { "true" } else { "false" }
$instrumentArguments = $deviceArguments + @(
    "shell", "am", "instrument", "-w",
    "-e", "mode", $Mode,
    "-e", "metric", $Metric,
    "-e", "rangeDays", $RangeDays.ToString(),
    "-e", "allowWrites", $allowWritesValue,
    $TestComponent
)
$started = $false
try {
    # Instrumentation starts a fresh isolated target process. Never clear data.
    Invoke-Adb -AdbArguments ($deviceArguments + @("shell", "am", "force-stop", $TargetPackage)) -Action "pre-test process stop" | Out-Null
    $started = $true
    $instrumentOutput = @(Invoke-Adb -AdbArguments $instrumentArguments -Action "instrumentation") -join ([Environment]::NewLine)
}
finally {
    if ($started) {
        try {
            Invoke-Adb -AdbArguments ($deviceArguments + @("shell", "am", "force-stop", $TargetPackage)) -Action "post-test process stop" | Out-Null
        }
        catch {
            Write-Warning "Could not stop the test process. Force-stop the app before reopening it normally."
        }
    }
}

# Retain only the explicit allowlisted diagnostic block emitted by the runner.
# Unstructured instrumentation output, exceptions, and raw logcat are discarded.
$reportMatch = [regex]::Match($instrumentOutput, "(?ms)^MI_HEALTH_LIVE_REPORT_BEGIN\r?\n(.*?)\r?\nMI_HEALTH_LIVE_REPORT_END(?:\r?\n|$)")
if (-not $reportMatch.Success -or $reportMatch.Groups[1].Value.Length -gt 131072) {
    throw "No valid sanitized report was returned. No raw device output was saved. Check the matched APK signatures/version and try inspect after resolving the cause."
}
$report = $reportMatch.Groups[1].Value
if (-not $report.StartsWith("Mi Health Sync Lab live test v1")) {
    throw "Unexpected report format. No device output was saved."
}
Set-Content -LiteralPath $OutputPath -Value $report -Encoding UTF8
Write-Host $report
Write-Host ("Saved sanitized report: " + [System.IO.Path]::GetFullPath($OutputPath))
Write-Host "Reopen Mi Health Sync Lab when finished to restore normal automatic synchronization."
if ($report -notmatch "(?m)^runner_outcome=COMPLETED\r?$") {
    throw "The command returned a safe failure report. See runner_failure in the saved file; no automatic retry was attempted."
}
