#!/usr/bin/env bash
# Sends .eml files to Mailpit over SMTP, the way a customer would email the quotes inbox.
#
#   scripts/send-mail.sh samples/emails/01-happy-path-gold.eml
#   scripts/send-mail.sh samples/emails/0{4,5}-*.eml
#
# Re-sending the same file is a no-op for agent-app: intake deduplicates by Message-ID.
set -euo pipefail

SMTP_HOST="${SMTP_HOST:-localhost}"
SMTP_PORT="${SMTP_PORT:-1025}"
QUOTES_INBOX="${QUOTES_INBOX:-quotes@nordline.test}"

if [[ $# -eq 0 ]]; then
  echo "usage: $0 <file.eml> [file.eml ...]" >&2
  exit 64
fi

envelope_sender() {
  local header
  header="$(grep -m1 -iE '^From:' "$1" || true)"
  if [[ "$header" =~ \<([^>]+)\> ]]; then
    echo "${BASH_REMATCH[1]}"
  else
    echo "$header" | sed -E 's/^[Ff][Rr][Oo][Mm]:[[:space:]]*//; s/[[:space:]].*$//'
  fi
}

for eml in "$@"; do
  if [[ ! -f "$eml" ]]; then
    echo "not a file: $eml" >&2
    exit 66
  fi
  from="$(envelope_sender "$eml")"
  if [[ -z "$from" ]]; then
    echo "no From header in $eml" >&2
    exit 65
  fi
  # --crlf: files are stored with LF, SMTP requires CRLF line endings.
  curl --silent --show-error --fail \
    --url "smtp://${SMTP_HOST}:${SMTP_PORT}" \
    --mail-from "$from" \
    --mail-rcpt "$QUOTES_INBOX" \
    --crlf \
    --upload-file "$eml"
  echo "sent $(basename "$eml") from $from to $QUOTES_INBOX"
done
