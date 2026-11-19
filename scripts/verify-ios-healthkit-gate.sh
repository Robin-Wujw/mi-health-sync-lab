#!/usr/bin/env bash
set -euo pipefail

entitlements='iosApp/iosApp.entitlements'
project='iosApp/project.yml'
workflow='.github/workflows/build-release-artifacts.yml'
bridge='iosApp/iosApp/HealthKit/HealthKitBridge.swift'

grep -Fq '<key>com.apple.developer.healthkit</key>' "$entitlements"
grep -Fq 'com.apple.developer.healthkit: true' "$project"
grep -Fq 'import HealthKit' "$bridge"
grep -Fq 'IOS_HEALTHKIT_VERIFIED_COMMIT == github.sha' "$workflow"
grep -Fq 'IOS_HEALTHKIT_VERIFIED_COMMIT != github.sha' "$workflow"
echo 'iOS HealthKit entitlement and release gate verified.'
