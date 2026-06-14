#!/usr/bin/env bash
#
# Demonstrates the async bulk flow against the running stack:
#   submit a batch -> get an id back immediately -> poll until DONE.
#
# Assumes the stack is up:  docker compose up -d --build
#
set -euo pipefail

BASE_URL="http://localhost:8080"

echo "==> Submitting a bulk batch of 4 payments ..."
resp=$(curl -fsS -X POST "${BASE_URL}/api/v1/payments/bulk" \
  -H "Store-Id: store-demo" \
  -H "Content-Type: application/json" \
  -d '{
    "payments": [
      {"coffeeType":"LATTE","price":3.50,"currency":"EUR","loyaltyCardId":"c1"},
      {"coffeeType":"ESPRESSO","price":2.00,"currency":"EUR","loyaltyCardId":"c2"},
      {"coffeeType":"MOCHA","price":3.80,"currency":"EUR","loyaltyCardId":"c3"},
      {"coffeeType":"CORTADO","price":2.50,"currency":"EUR","loyaltyCardId":"c4"}
    ]
  }')
echo "    response: ${resp}"

batch_id=$(printf '%s' "$resp" | sed -n 's/.*"batchId":"\([^"]*\)".*/\1/p')
echo "    batchId: ${batch_id}"

echo
echo "==> Polling status until DONE ..."
for i in $(seq 1 40); do
  status_body=$(curl -fsS "${BASE_URL}/api/v1/payments/batches/${batch_id}")
  status=$(printf '%s' "$status_body" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p' | head -1)
  printf "    [%02d] status=%s\n" "$i" "$status"
  if [ "$status" = "DONE" ]; then
    echo
    echo "==> Final batch state:"
    printf '%s\n' "$status_body"
    exit 0
  fi
  sleep 1
done

echo "batch did not reach DONE in time" >&2
exit 1
