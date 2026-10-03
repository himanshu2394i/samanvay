#!/usr/bin/env bash
# Regenerates src/test/resources/manifests/*.json from the REAL manifests of the four departments/ services, so the
# core's manifest-parsing tests always check what the departments actually publish.
# Usage (repo root): scripts/capture-department-manifests.sh
set -euo pipefail
cd "$(dirname "$0")/.."
out=src/test/resources/manifests
mkdir -p "$out"
pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT
for pair in revenue:8091 dbt:8092 education:8093 agriculture:8094; do
  d=${pair%%:*}
  ./mvnw -q -f "departments/$d/pom.xml" -DskipTests package
  java -jar "departments/$d/target/samanvay-dept-$d.jar" >/dev/null 2>&1 &
  pids+=("$!")
done
for pair in revenue:8091 dbt:8092 education:8093 agriculture:8094; do
  d=${pair%%:*}; p=${pair##*:}
  for _ in $(seq 1 40); do
    curl -sf "http://localhost:$p/.well-known/samanvay/manifest" -o "$out/$d.json" && break
    sleep 1
  done
  test -s "$out/$d.json" || { echo "no manifest from $d" >&2; exit 1; }
done
echo "captured: $(ls $out)"
