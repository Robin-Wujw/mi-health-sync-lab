#!/usr/bin/env bash
set -euo pipefail

scripts_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
tag="${1:?Supply the proposed release tag; pass published Android release tags on stdin}"

version_code() (
  source "$scripts_dir/resolve-version.sh" "$1" || exit 1
  printf '%s\n' "$VERSION_CODE"
)

proposed=$(version_code "$tag")
if (( proposed <= 1000046 )); then
  printf 'Version code %s is not newer than the original HealthLab .40 build.\n' "$proposed" >&2
  exit 1
fi

while IFS= read -r published || [[ -n "$published" ]]; do
  [[ -n "$published" ]] || continue
  previous=$(version_code "$published") || {
    printf 'Cannot verify the version of previously published Android tag: %s\n' "$published" >&2
    exit 1
  }
  if (( proposed <= previous )); then
    printf 'Proposed Android version code %s must exceed %s (%s).\n' "$proposed" "$previous" "$published" >&2
    exit 1
  fi
done
