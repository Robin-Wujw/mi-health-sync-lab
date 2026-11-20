#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
unset GITHUB_REF_NAME GITHUB_REF_TYPE

assert_version() {
  local expected_name="$1" expected_code="$2" expected_release="$3" expected_prerelease="$4" expected_ios="$5"
  shift 5
  local actual expected
  actual=$(bash resolve-version.sh "$@")
  expected=$(printf 'VERSION_NAME=%s\nVERSION_CODE=%s\nIS_RELEASE=%s\nIS_PRERELEASE=%s\nIOS_MARKETING_VERSION=%s' \
    "$expected_name" "$expected_code" "$expected_release" "$expected_prerelease" "$expected_ios")
  [[ "$actual" == "$expected" ]] || { printf 'Unexpected version:\n%s\n' "$actual" >&2; return 1; }
}

assert_version 1.0.6-healthlab.40 1000046 false false 1.0.6
assert_version 1.0.6-healthlab.40 1000046 true true 1.0.6 v1.0.6-healthlab.40
assert_version 1.0.6-healthlab.41 1000047 true true 1.0.6 refs/tags/v1.0.6-healthlab.41
assert_version 1.0.6 100006999 true false 1.0.6 v1.0.6
assert_version 1.0.7-healthlab.1 100007001 true true 1.0.7 v1.0.7-healthlab.1
GITHUB_REF_TYPE=branch GITHUB_REF_NAME=v1.0.6-healthlab.41 \
  assert_version 1.0.6-healthlab.40 1000046 false false 1.0.6
for invalid in v1.0.6-beta v1.0.6-healthlab.0 v1.0.6-healthlab.999 v01.0.6; do
  if bash resolve-version.sh "$invalid" >/dev/null 2>&1; then
    printf 'Unexpectedly accepted %s\n' "$invalid" >&2
    exit 1
  fi
done
if GITHUB_REF_TYPE=tag GITHUB_REF_NAME=v1.0.6-beta bash resolve-version.sh >/dev/null 2>&1; then
  exit 1
fi
bash verify-release-order.sh v1.0.6-healthlab.41 <<< '1.0.6-healthlab.40'
bash verify-release-order.sh v1.0.7-healthlab.1 <<< $'1.0.6-healthlab.40\nv1.0.6'
for published in v1.0.6-healthlab.41 1.0.6-healthlab.42 v1.0.6 android-unknown; do
  if VERSION_CODE=1 bash verify-release-order.sh v1.0.6-healthlab.41 <<< "$published" >/dev/null 2>&1; then
    printf 'Unexpectedly approved release after %s\n' "$published" >&2
    exit 1
  fi
done
if bash verify-release-order.sh v1.0.6-healthlab.40 </dev/null >/dev/null 2>&1; then
  exit 1
fi
printf 'Version parser checks passed.\n'
