#!/usr/bin/env bash
# Sends every sample email to Mailpit (see scripts/send-mail.sh for the SMTP settings).
set -euo pipefail
cd "$(dirname "$0")/.."
exec scripts/send-mail.sh samples/emails/*.eml
