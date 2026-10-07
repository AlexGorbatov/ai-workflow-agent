#!/usr/bin/env bash
# The whole system on one machine, no API keys: infrastructure from compose.yaml, the mock CRM and rates,
# agent-app with the demo profile (timers in seconds and minutes) and a canned model that knows the sample
# emails. Sends the samples and prints where to look. Ctrl-C stops the apps; `docker compose down` the rest.
#
#   scripts/demo.sh                 # demo model, no key needed
#   LM_STUDIO=1 scripts/demo.sh     # a real local model through LM Studio (default qwen3-coder-30b-a3b-it-heretic-i1,
#                                   # another one: LMSTUDIO_MODEL=<id>)
#
# Needs Docker (with compose v2) and JDK 25. Logs go to target/demo/.
set -euo pipefail
cd "$(dirname "$0")/.."

# Port overrides from .env (compose reads it itself; the apps and these scripts do not).
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-workflow}"
# The agent signs approval tokens with it, the mock CRM verifies them. A fixed value is fine for a local demo only.
export APPROVAL_TOKEN_SECRET="${APPROVAL_TOKEN_SECRET:-demo-only-approval-secret-not-for-production}"
export DEMO=1

LOGS=target/demo
MAILPIT="http://localhost:${MAILPIT_UI_PORT:-8025}"
export MAILPIT_API_URL="$MAILPIT"
PIDS=()

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
  trap - EXIT INT TERM
  if ((${#PIDS[@]})); then
    say "Stopping agent-app and the mocks, up to 30 s (the infrastructure keeps running: docker compose down)"
    for pid in "${PIDS[@]}"; do
      pkill -TERM -P "$pid" 2>/dev/null || true
      kill -TERM "$pid" 2>/dev/null || true
    done
    wait 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

wait_for() { # url, name, seconds, pid
  local deadline=$((SECONDS + $3))
  until curl -fsS -o /dev/null "$1" 2>/dev/null; do
    if [[ -n "${4:-}" ]] && ! kill -0 "$4" 2>/dev/null; then
      die "$2 exited; see $LOGS/$2.log"
    fi
    ((SECONDS < deadline)) || die "$2 is not up after $3 s; see $LOGS/$2.log"
    sleep 2
  done
  echo "$2 is up"
}

# The module's executable jar from the build (the newest, if an older version is still in target/).
newest() { ls -t "$1"/target/"$1"-*-exec.jar | head -1; }

# Waits until Mailpit holds a mail to $1 whose subject contains $2 (the agent's answer to an earlier sample).
wait_for_mail() {
  local deadline=$((SECONDS + 180))
  until curl -fsS -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:$1 subject:\"$2\"" 2>/dev/null \
      | grep -q '"messages_count":[1-9]'; do
    ((SECONDS < deadline)) || { echo "no mail to $1 about \"$2\" yet; sending the reply anyway"; return; }
    sleep 2
  done
}

say "Checking prerequisites"
command -v docker >/dev/null || die "Docker is not installed"
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is needed (docker compose ...)"
command -v java >/dev/null || die "JDK 25 is needed (java not found)"
java_major="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
[[ "${java_major:-0}" -ge 25 ]] || die "JDK 25 is needed, found ${java_major:-unknown}"
for port in 8080 8091 8092; do
  if curl -s -o /dev/null "http://localhost:$port"; then
    die "port $port is taken; stop whatever runs there (an earlier demo?) and try again"
  fi
done
mkdir -p "$LOGS"

say "Starting PostgreSQL, Keycloak, Mailpit and Grafana (docker compose up)"
docker compose up -d --wait

say "Building (first run downloads dependencies; takes a few minutes)"
./mvnw -B -q -DskipTests install

say "Starting the mock CRM (MCP) and the mock carrier rates"
java -jar "$(newest mock-crm-mcp)" >"$LOGS/mock-crm-mcp.log" 2>&1 &
PIDS+=($!)
crm=$!
java -jar "$(newest mock-rates)" >"$LOGS/mock-rates.log" 2>&1 &
PIDS+=($!)
rates=$!

say "Starting agent-app (profile demo${LM_STUDIO:+, lmstudio})"
./mvnw -B -q -pl agent-app spring-boot:test-run >"$LOGS/agent-app.log" 2>&1 &
PIDS+=($!)
app=$!

wait_for http://localhost:8091/actuator/health mock-crm-mcp 120 "$crm"
wait_for http://localhost:8092/actuator/health mock-rates 120 "$rates"
wait_for http://localhost:8080/actuator/health agent-app 300 "$app"

say "Sending the sample emails to the quotes inbox"
first=()
for eml in samples/emails/*.eml; do
  case "$(basename "$eml")" in
    05-* | 10-*) ;; # replies: sent once the agent has written the mail they answer
    *) first+=("$eml") ;;
  esac
done
scripts/send-mail.sh "${first[@]}"

echo "waiting for the clarification to 04 (missing fields), then replying with 05"
wait_for_mail marco.rossi@adriaticfoods.test "Pallets Milan"
scripts/send-mail.sh samples/emails/05-clarification-reply.eml
echo "waiting for the quote to 01 (gold customer), then accepting it with 10"
wait_for_mail anna.kowalska@polmarket.test "Berlin"
sleep 3 # the quote mail leaves a moment before the instance settles in FOLLOW_UP
scripts/send-mail.sh samples/emails/10-customer-accepts.eml

cat <<EOF

$(printf '\033[1m')The demo is running.$(printf '\033[0m')

  Operator UI   http://localhost:8080/ui/      log in as max / max (operator + approver) or olena / olena
                instances, timelines, the approval inbox (02, 03, 06, 07, 11, 15 wait for a person)
  Mailpit       $MAILPIT          the customer side: requests, clarifications, quotes, reminders
  Grafana       http://localhost:3000          dashboard "AI Workflow Agent"; traces in Explore → Tempo

Demo timers: an unanswered clarification closes after 2 min, an open approval escalates after 2 min,
a sent quote gets one reminder after 60 s. Logs: $LOGS/. Ctrl-C to stop.
EOF

wait "$app"
