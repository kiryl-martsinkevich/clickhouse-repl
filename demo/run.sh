#!/usr/bin/env bash
# clickhouse-repl demo: copy masked data from the DR replicas of "prod" into a replicated QA target.
#   prod1-3 + dr1-3: one shard, 6 replicas, Keeper on each machine (DR Keepers cannot lead)
#   qa1-1..3: the target, one shard, 3 replicas, its own Keepers
#   demo/run.sh        build, start, seed, copy and show the result
#   demo/run.sh down   stop and remove everything
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f demo/docker-compose.yml)
CLI=modules/cli/build/install/clickhouse-repl/bin/clickhouse-repl

if [[ "${1:-}" == "down" ]]; then
  "${COMPOSE[@]}" --profile app down -v
  exit 0
fi

step() { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
PROD=(prod1 prod2 prod3); DR=(dr1 dr2 dr3); QA1=(qa1-1 qa1-2 qa1-3)
sql() { "${COMPOSE[@]}" exec -T "$1" clickhouse-client --multiquery; }
query() { "${COMPOSE[@]}" exec -T "$1" clickhouse-client --query "$2"; }

step "Building the app, the CLI and the dev identity provider"
./gradlew -q :app:bootJar :cli:installDist :devidp:installDist

step "Generating demo secrets (demo/.secrets, git-ignored)"
mkdir -p demo/.secrets demo/config/dictionaries
cp docs/examples/dictionaries/*.txt demo/config/dictionaries/
for f in dr-reader-password dr-guard-password qa1-writer-password; do
  [[ -s demo/.secrets/$f ]] || openssl rand -hex 16 > "demo/.secrets/$f"
done
[[ -s demo/.secrets/masking-key-v1 ]] || openssl rand -hex 32 > demo/.secrets/masking-key-v1
chmod 0644 demo/.secrets/*   # readable by the app's non-root user inside the container

step "Starting 3 prod + 3 DR replicas, 3 qa1 replicas (Keepers on every node), Postgres and the dev IdP"
"${COMPOSE[@]}" up -d "${PROD[@]}" "${DR[@]}" "${QA1[@]}" postgres devidp
for node in "${PROD[@]}" "${DR[@]}" "${QA1[@]}"; do
  # Up, and its Keeper ensemble has a leader.
  until query "$node" "SELECT count() FROM system.zookeeper WHERE path = '/'" >/dev/null 2>&1; do sleep 1; done
done

step "DBA steps: DR users and limits (deploy/dr-ddl.sql) on every DR replica (and on prod, as a honeypot); the qa1 marker and writer on every qa1 replica"
for node in "${DR[@]}" "${PROD[@]}"; do
  sed -e "s/<reader-secret>/$(cat demo/.secrets/dr-reader-password)/" \
      -e "s/<guard-secret>/$(cat demo/.secrets/dr-guard-password)/" \
      -e "s#10.20.0.0/16#172.28.0.0/24#" deploy/dr-ddl.sql | sql "$node"
done
for node in "${QA1[@]}"; do
  sed -e "s/<target>/qa1/" -e "s/<secret>/$(cat demo/.secrets/qa1-writer-password)/" \
      -e "s#10.20.0.0/16#172.28.0.0/24#" deploy/target-ddl.sql | sql "$node"
done

step "Sample tables on all 6 replicas (ReplicatedMergeTree); data inserted on prod1 only and replicated to DR"
for node in "${PROD[@]}" "${DR[@]}"; do sql "$node" < deploy/demo/sample-schema.sql; done
sql prod1 < deploy/demo/sample-data.sql
for node in "${DR[@]}"; do
  for t in trading.trades trading.positions crm.customers refdata.instruments; do query "$node" "SYSTEM SYNC REPLICA $t"; done
  echo "rows on $node: $(query "$node" "SELECT count() FROM trading.trades") trades"
done

step "Starting clickhouse-repl (api + worker + scheduler)"
"${COMPOSE[@]}" --profile app up -d --build app
until curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; do sleep 2; done
echo "app is up: http://127.0.0.1:8080"

export CHREPL_API=http://127.0.0.1:8080
CHREPL_TOKEN=$(curl -fsS "http://127.0.0.1:9000/dev/token?user=alice")
export CHREPL_TOKEN

step "Waiting for the Source Guard to see the DR replicas and DR Keepers as GREEN"
until curl -fsS -H "Authorization: Bearer $CHREPL_TOKEN" $CHREPL_API/api/v1/guard | grep -q '"level":"GREEN"'; do sleep 2; done
curl -fsS -H "Authorization: Bearer $CHREPL_TOKEN" $CHREPL_API/api/v1/guard; echo

step "alice (QA) estimates a copy of the trading-eod dataset for COB 2026-10-01 to qa1"
$CLI dry-run --dataset trading-eod --cob 2026-10-01 --target qa1

step "... and submits it through the REST API (the CLI and the portal use the same call)"
JOB=$(curl -fsS -X POST "$CHREPL_API/api/v1/jobs" \
  -H "Authorization: Bearer $CHREPL_TOKEN" -H "Content-Type: application/json" -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{"dataset": "trading-eod", "cob": {"dates": ["2026-10-01"]}, "target": "qa1", "mode": "replace"}')
echo "$JOB"
JOB_ID=$(sed -E 's/.*"id":"([^"]+)".*/\1/' <<<"$JOB")
until curl -fsS -H "Authorization: Bearer $CHREPL_TOKEN" "$CHREPL_API/api/v1/jobs/$JOB_ID" | grep -qE '"state":"(SUCCEEDED|FAILED|CANCELLED|BLOCKED)"'; do sleep 2; done
$CLI status "$JOB_ID"

step "Production data (queried on prod by the demo, never by the tool)"
query prod1 "SELECT trade_id, customer_id, account_number, counterparty_name, notional, trader_comment, attributes['client_email'] AS client_email
            FROM trading.trades WHERE trade_date = '2026-10-01' ORDER BY trade_id LIMIT 4 FORMAT PrettyCompactMonoBlock"

step "The same rows on qa1: masked consistently, pass columns unchanged"
query qa1-1 "SELECT trade_id, customer_id, account_number, counterparty_name, notional, trader_comment, attributes['client_email'] AS client_email
           FROM trading.trades WHERE trade_date = '2026-10-01' ORDER BY trade_id LIMIT 4 FORMAT PrettyCompactMonoBlock"
query qa1-1 "SELECT customer_id, first_name, last_name, email, phone, date_of_birth FROM crm.customers ORDER BY customer_id LIMIT 3 FORMAT PrettyCompactMonoBlock"

step "Consistency: masked customer ids still join across tables"
query qa1-1 "SELECT count() AS trades, countIf(customer_id IN (SELECT customer_id FROM crm.customers)) AS with_customer
           FROM trading.trades WHERE trade_date = '2026-10-01' FORMAT PrettyCompactMonoBlock"

step "Every qa1 replica has the copy (written once, replicated by qa1's own Keepers)"
for node in "${QA1[@]}"; do
  query "$node" "SYSTEM SYNC REPLICA trading.trades"
  echo "$node: $(query "$node" "SELECT count() FROM trading.trades WHERE trade_date = '2026-10-01'") trades, engine $(query "$node" "SELECT engine FROM system.tables WHERE database = 'trading' AND name = 'trades'")"
done

step "Which DR replicas served the reads"
for node in "${DR[@]}"; do
  query "$node" "SYSTEM FLUSH LOGS"
  echo "$node: $(query "$node" "SELECT count() FROM system.query_log WHERE user = 'repl_reader' AND type = 'QueryFinish' AND query_kind = 'Select'") reads"
done

step "The tool never touched prod"
total=0
for node in "${PROD[@]}"; do
  query "$node" "SYSTEM FLUSH LOGS"
  total=$((total + $(query "$node" "SELECT count() FROM system.query_log WHERE user LIKE 'repl\\_%'")))
done
echo "queries on prod1-3 by repl_* users: $total"

cat <<EOF

Portal: http://localhost:8080 (sign in as alice, bob, olga or root on the dev sign-in page).
Stop:   demo/run.sh down
EOF
