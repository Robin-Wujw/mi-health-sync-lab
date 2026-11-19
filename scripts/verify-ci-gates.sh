#!/usr/bin/env bash
set -euo pipefail

workflow='.github/workflows/build-release-artifacts.yml'
android_build='androidApp/build.gradle.kts'
test -s "$workflow"
test -s "$android_build"

for required in \
  ':composeApp:testAndroidHostTest' \
  ':miclient:androidTest' \
  'detekt' \
  'scripts/test-resolve-version.sh' \
  'scripts/verify-backup-exclusions.sh' \
  'scripts/verify-ios-healthkit-gate.sh' \
  'apksigner" verify' \
  'vars.IOS_HEALTHKIT_VERIFIED_COMMIT == github.sha'; do
  grep -Fq "$required" "$workflow" || {
    echo "Missing required CI gate: $required" >&2
    exit 1
  }
done

grep -Fq 'requireReleaseSigning' "$android_build"
grep -Fq 'dependsOn("requireReleaseSigning")' "$android_build"

grep -Fq 'if: always()' "$workflow"
grep -Fq 'if-no-files-found: error' "$workflow"
echo 'CI gate structure verified.'
