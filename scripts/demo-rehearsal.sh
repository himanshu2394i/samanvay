#!/usr/bin/env bash
# Walk HLD §16 three times. No manual steps.
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw --batch-mode --no-transfer-progress -Dit.test=DemoRehearsalIT -Dsurefire.failIfNoSpecifiedTests=false verify
