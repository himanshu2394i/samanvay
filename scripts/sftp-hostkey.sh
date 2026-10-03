#!/usr/bin/env bash
# Prints the SHA256 fingerprint of the RSA host key an SFTP server presents right now. It must equal the pin Samanvay holds
# (SAMANVAY_*_SFTP_HOSTKEY). Usage: scripts/sftp-hostkey.sh <host> <port>
set -euo pipefail
[ $# -eq 2 ] || { echo "usage: $0 <host> <port>" >&2; exit 2; }
ssh-keyscan -t rsa -p "$2" "$1" 2>/dev/null | ssh-keygen -lf - | awk '{print $2}'
