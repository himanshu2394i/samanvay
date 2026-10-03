#!/usr/bin/env bash
# Builds the four department stand-in jars (departments/*/target/samanvay-dept-<name>.jar). The end-to-end test
# (DepartmentsEndToEndIT) runs them as real processes; it is skipped, not failed, if they have not been built.
# Usage (repo root): scripts/build-departments.sh
set -euo pipefail
cd "$(dirname "$0")/.."
for d in revenue dbt education agriculture; do
  ./mvnw -q -f "departments/$d/pom.xml" -DskipTests package
  echo "built departments/$d/target/samanvay-dept-$d.jar"
done
