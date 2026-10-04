#!/usr/bin/env bash
# Builds the four department jars (departments/<d>/target/samanvay-dept-<d>.jar) together with the shared kit. The end-to-end test
# (DepartmentsEndToEndIT) runs them as real processes; it is skipped, not failed, if they have not been built.
# Usage (repo root): scripts/build-departments.sh
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q -f departments/pom.xml -DskipTests package
for d in revenue dbt education agriculture; do
  echo "built departments/$d/target/samanvay-dept-$d.jar"
done
