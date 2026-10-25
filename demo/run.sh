#!/usr/bin/env bash
# clickhouse-repl demo: copy masked data from a "DR" replica of "prod" into a QA target.
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

step "Starting Keeper, prod, DR, qa1, Postgres and the dev IdP"
"${COMPOSE[@]}" up -d keeper prod dr qa1 postgres devidp
for node in keeper prod dr qa1; do
  until query "$node" "SELECT 1" >/dev/null 2>&1; do sleep 1; done
done

step "DBA steps: DR users and limits (deploy/dr-ddl.sql) on DR (and on prod, as a honeypot); the qa1 marker and writer"
for node in dr prod; do
  sed -e "s/<reader-secret>/$(cat demo/.secrets/dr-reader-password)/" \
      -e "s/<guard-secret>/$(cat demo/.secrets/dr-guard-password)/" \
      -e "s#10.20.0.0/16#172.28.0.0/24#" deploy/dr-ddl.sql | sql "$node"
done
sed -e "s/<target>/qa1/" -e "s/<secret>/$(cat demo/.secrets/qa1-writer-password)/" \
    -e "s#10.20.0.0/16#172.28.0.0/24#" deploy/target-ddl.sql | sql qa1

step "Sample tables on prod and DR (ReplicatedMergeTree); data inserted on prod only and replicated to DR"
for node in prod dr; do sql "$node" < deploy/demo/sample-schema.sql; done
sql prod < deploy/demo/sample-data.sql
for t in trading.trades trading.positions crm.customers refdata.instruments; do query dr "SYSTEM SYNC REPLICA $t"; done
echo "rows on DR: $(query dr "SELECT count() FROM trading.trades") trades"

step "Starting clickhouse-repl (api + worker + scheduler)"
"${COMPOSE[@]}" --profile app up -d --build app
until curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; do sleep 2; done
echo "app is up: http://127.0.0.1:8080"

export CHREPL_API=http://127.0.0.1:8080
CHREPL_TOKEN=$(curl -fsS "http://127.0.0.1:9000/dev/token?user=alice")
export CHREPL_TOKEN

step "Waiting for the Source Guard to see DR as GREEN"
until curl -fsS -H "Authorization: Bearer $CHREPL_TOKEN" $CHREPL_API/api/v1/guard | grep -q '"level":"GREEN"'; do sleep 2; done
curl -fsS -H "Authorization: Bearer $CHREPL_TOKEN" $CHREPL_API/api/v1/guard; echo

step "alice (QA) estimates a copy of the trading-eod dataset for COB 2026-10-01 to qa1"
$CLI dry-run --dataset trading-eod --cob 2026-10-01 --target qa1

step "... and submits it"
$CLI submit --dataset trading-eod --cob 2026-10-01 --target qa1 --wait

step "Production data (queried on prod by the demo, never by the tool)"
query prod "SELECT trade_id, customer_id, account_number, counterparty_name, notional, trader_comment, attributes['client_email'] AS client_email
            FROM trading.trades WHERE trade_date = '2026-10-01' ORDER BY trade_id LIMIT 4 FORMAT PrettyCompactMonoBlock"

step "The same rows on qa1: masked consistently, pass columns unchanged"
query qa1 "SELECT trade_id, customer_id, account_number, counterparty_name, notional, trader_comment, attributes['client_email'] AS client_email
           FROM trading.trades WHERE trade_date = '2026-10-01' ORDER BY trade_id LIMIT 4 FORMAT PrettyCompactMonoBlock"
query qa1 "SELECT customer_id, first_name, last_name, email, phone, date_of_birth FROM crm.customers ORDER BY customer_id LIMIT 3 FORMAT PrettyCompactMonoBlock"

step "Consistency: masked customer ids still join across tables"
query qa1 "SELECT count() AS trades, countIf(customer_id IN (SELECT customer_id FROM crm.customers)) AS with_customer
           FROM trading.trades WHERE trade_date = '2026-10-01' FORMAT PrettyCompactMonoBlock"

step "The tool never touched prod"
query prod "SYSTEM FLUSH LOGS"
echo "queries on prod by repl_* users: $(query prod "SELECT count() FROM system.query_log WHERE user LIKE 'repl\\_%'")"

cat <<EOF

Portal: http://localhost:8080 (sign in as alice, bob, olga or root on the dev sign-in page).
Stop:   demo/run.sh down
EOF
