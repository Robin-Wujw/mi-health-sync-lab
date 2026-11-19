#!/usr/bin/env bash
set -euo pipefail

backup='androidApp/src/main/res/xml/backup_rules.xml'
extraction='androidApp/src/main/res/xml/data_extraction_rules.xml'

for file in "$backup" "$extraction"; do
  test -s "$file"
  grep -Fq 'path="mi_fitness_prefs.preferences_pb"' "$file"
  grep -Fq 'domain="sharedpref" path="authorized_workout_routes_v1.xml"' "$file"
done

grep -Fq '<device-transfer>' "$extraction"
grep -Fq '<cloud-backup>' "$extraction"
echo 'Backup exclusions verified.'
