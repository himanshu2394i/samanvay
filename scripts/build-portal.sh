#!/usr/bin/env bash
# Builds the citizen portal (frontend/, `npm run build:portal`) and copies it into each department's
# static/portal/ so every department serves the same SPA at /portal/ (git-ignored generated copies).
# Usage (repo root): scripts/build-portal.sh
set -euo pipefail
cd "$(dirname "$0")/.."

( cd frontend
  [ -d node_modules ] || npm ci
  npm run build:portal )

for d in revenue dbt education agriculture; do
  target="departments/$d/src/main/resources/static/portal"
  rm -rf "$target"
  mkdir -p "$target"
  cp -R frontend/dist-portal/. "$target/"
  echo "portal copied to $target"
done
