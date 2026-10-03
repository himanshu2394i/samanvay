#!/usr/bin/env bash
# DEV ONLY. Prints the environment variables that give Samanvay the DEV credentials of the four department stand-ins
# (the SecretStore reads SAMANVAY_SECRET_<KEY> as base64). These are the stand-ins' fake, env-overridable defaults;
# a real department hands its real credential over out of band. Usage:  eval "$(scripts/dev-department-secrets.sh)"
set -euo pipefail
emit() { printf 'export SAMANVAY_SECRET_%s=%s\n' "$(echo "$1" | tr 'a-z-' 'A-Z_')" "$(printf '%s' "$2" | base64 | tr -d '\n')"; }
emit source-revenue-rest-credential      '{"X-Api-Key":"revenue-dev-key-change-me"}'
emit source-revenue-sftp-credential      'revenue-sftp:revenue-sftp-dev'
emit source-dbt-rest-credential          '{"client_id":"samanvay-dev","client_secret":"dbt-dev-secret-change-me"}'
emit source-education-soap-credential    '{"username":"samanvay-dev","password":"education-dev-secret-change-me"}'
emit source-agriculture-jdbc-credential  'agri_ro:agri_ro_demo'
emit source-agriculture-sftp-credential  'agri-sftp:agri-sftp-dev'
