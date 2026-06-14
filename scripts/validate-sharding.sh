#!/usr/bin/env bash
#
# End-to-end proof that payments are sharded uniformly across the 3 Postgres
# instances. Brings up the whole stack (app + 3 shards) with docker compose,
# posts a small sample of payments through the REST API, then counts the rows
# that physically landed in each shard database and checks the spread is even.
#
# Usage:  scripts/validate-sharding.sh [SAMPLE_SIZE]   (default 300)
#
set -euo pipefail

SAMPLE="${1:-300}"
BASE_URL="http://localhost:8080"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Bringing up the sharded stack (app + shard0/1/2) ..."
docker compose up -d --build

cleanup() {
  echo
  echo "==> Tip: 'docker compose down -v' to tear everything down."
}
trap cleanup EXIT

echo "==> Waiting for the app to be ready ..."
for i in $(seq 1 60); do
  if curl -fsS "${BASE_URL}/api/v1/payments/shards/distribution" >/dev/null 2>&1; then
    echo "    app is up."
    break
  fi
  sleep 2
  if [ "$i" -eq 60 ]; then echo "    app did not come up in time" >&2; exit 1; fi
done

echo "==> Posting ${SAMPLE} payments (unique idempotency key each) ..."
for i in $(seq 1 "$SAMPLE"); do
  curl -fsS -o /dev/null -X POST "${BASE_URL}/api/v1/payments" \
    -H "Store-Id: store-$((i % 10))" \
    -H "Idempotency-Key: key-${i}-$(date +%s%N)" \
    -H "Content-Type: application/json" \
    -d '{"coffeeType":"LATTE","price":3.50,"currency":"EUR","loyaltyCardId":"card-1"}'
done
echo "    done."

echo
echo "==> Distribution reported by the app (scatter-gather COUNT per shard):"
curl -fsS "${BASE_URL}/api/v1/payments/shards/distribution"
echo

echo
echo "==> Cross-check: COUNT(*) straight from each Postgres instance:"
total=0
declare -a counts
for s in 0 1 2; do
  c=$(docker compose exec -T "shard${s}" psql -U payments -d payments -tAc "SELECT COUNT(*) FROM payments")
  c=$(echo "$c" | tr -d '[:space:]')
  counts[$s]=$c
  total=$((total + c))
  printf "    shard%s : %s rows\n" "$s" "$c"
done
printf "    total  : %s rows\n" "$total"

echo
echo "==> Uniformity check (expect each shard within +/-25%% of the mean):"
mean=$((total / 3))
ok=1
for s in 0 1 2; do
  c=${counts[$s]}
  lo=$(( mean * 75 / 100 ))
  hi=$(( mean * 125 / 100 ))
  if [ "$c" -ge "$lo" ] && [ "$c" -le "$hi" ]; then
    printf "    shard%s OK   (%s in [%s, %s])\n" "$s" "$c" "$lo" "$hi"
  else
    printf "    shard%s SKEW (%s outside [%s, %s])\n" "$s" "$c" "$lo" "$hi"
    ok=0
  fi
done

echo
if [ "$ok" -eq 1 ]; then
  echo "RESULT: PASS — payments are distributed uniformly across the 3 shards."
else
  echo "RESULT: FAIL — distribution is skewed." >&2
  exit 1
fi
