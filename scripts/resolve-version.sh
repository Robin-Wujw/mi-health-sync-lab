#!/usr/bin/env bash
# Resolve VERSION_NAME + VERSION_CODE from a git tag ref or defaults.
# Usage:
#   source scripts/resolve-version.sh           # uses GITHUB_REF or defaults
#   source scripts/resolve-version.sh v1.2.3
#   ./scripts/resolve-version.sh v1.2.3        # prints export lines
# Keep this script LF-terminated because it is sourced by CI shell gates.
set -euo pipefail

input="${1:-${GITHUB_REF_NAME:-}}"
IS_PRERELEASE=false
if [[ "$#" -gt 0 || "${GITHUB_REF_TYPE:-}" != branch ]] && [[ "$input" =~ ^(refs/tags/)?v?([1-9]|1[0-9]|20)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]{0,2})(-healthlab\.([1-9][0-9]{0,2}))?$ ]]; then
  MAJOR="${BASH_REMATCH[2]}"
  MINOR="${BASH_REMATCH[3]}"
  PATCH="${BASH_REMATCH[4]}"
  SUFFIX="${BASH_REMATCH[5]}"
  ORDINAL="${BASH_REMATCH[6]}"
  IOS_MARKETING_VERSION="$MAJOR.$MINOR.$PATCH"
  VERSION_NAME="${IOS_MARKETING_VERSION}${SUFFIX}"
  IS_RELEASE=true
  if [[ -n "$ORDINAL" ]]; then
    if [[ "$ORDINAL" -ge 999 ]]; then
      echo 'HealthLab sequence must be between 1 and 998' >&2
      return 1 2>/dev/null || exit 1
    fi
    IS_PRERELEASE=true
  fi
  if [[ "$IOS_MARKETING_VERSION" == '1.0.6' && "$IS_PRERELEASE" == true ]]; then
    VERSION_CODE=$((1000006 + ORDINAL))
  else
    VERSION_CODE=$((MAJOR * 100000000 + MINOR * 1000000 + PATCH * 1000 + ${ORDINAL:-999}))
  fi
elif [[ "${GITHUB_REF_TYPE:-}" == tag || "$input" == refs/tags/* || "$#" -gt 0 ]]; then
  echo 'Unsupported release tag; expected vMAJOR.MINOR.PATCH[-healthlab.N]' >&2
  return 1 2>/dev/null || exit 1
else
  VERSION_NAME=1.0.6-healthlab.40
  VERSION_CODE=1000046
  IOS_MARKETING_VERSION=1.0.6
  IS_RELEASE=false
fi

export VERSION_NAME VERSION_CODE IS_RELEASE IS_PRERELEASE IOS_MARKETING_VERSION

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "VERSION_NAME=$VERSION_NAME"
  echo "VERSION_CODE=$VERSION_CODE"
  echo "IS_RELEASE=$IS_RELEASE"
  echo "IS_PRERELEASE=$IS_PRERELEASE"
  echo "IOS_MARKETING_VERSION=$IOS_MARKETING_VERSION"
fi
