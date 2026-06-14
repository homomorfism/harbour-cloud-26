# Sharding + async payment processing

The `payments` table is sharded across **3 independent Postgres instances**.
Nothing is replicated between them — each payment lives in exactly one shard.
Bulk payment requests are processed **asynchronously** by a background worker.

## How a shard is chosen

```
shardIndex = hash(paymentId) % shardCount
```

- `hash` is MD5 of the id (see `ShardRouter`) — `String.hashCode()` is not evenly
  distributed for short structured strings, MD5 is.
- The `paymentId` is derived deterministically from `(storeId, idempotencyKey)`
  (`UUID.nameUUIDFromBytes`, see `PaymentService`). This is what makes sharding
  and idempotency compose: a retried request produces the **same** id → the same
  shard → a primary-key clash there, caught without consulting any other shard.

### Query paths

| Operation | Shards touched | Why |
|-----------|----------------|-----|
| register / lookup by id | 1 | the id alone determines the shard |
| list by store | all (scatter-gather) | a store's payments are spread across shards |

## Configuration (static)

The shard count is just the number of entries in config — there is no resharding.

- Local run: `application.properties` → `localhost:15432 / 15433 / 15434`
- Inside compose: `application-docker.properties` (profile `docker`) → `shard0 / shard1 / shard2`

To change the shard count, add/remove a `payments.sharding.shards[i].*` block and a
matching `shardN` service in `compose.yaml`.

## Running

```bash
docker compose up -d --build      # app on :8080 + 3 Postgres shards
```

Endpoints:

- `POST /api/v1/payments` — **sync** single payment (headers `Store-Id`, optional `Idempotency-Key`)
- `POST /api/v1/payments/bulk` — **async** batch submit → `202` + `batchId`
- `GET  /api/v1/payments/batches/{batchId}` — batch status (poll until `DONE`)
- `GET  /api/v1/payments/{id}` — single-shard lookup
- `GET  /api/v1/payments?storeId=...` — scatter-gather
- `GET  /api/v1/payments/shards/distribution` — rows per shard

## Async bulk flow

```
POST /bulk ──▶ store batch (PENDING) + payments (PENDING) ──▶ 202 { batchId }
                                  │
        background worker (PaymentProcessor, @Scheduled every 1s)
                                  │
   claim payment (PENDING→PROCESSING) ─▶ remote system entry ─▶ payment DONE
                                  │
        reconcile: all payments DONE  ─▶ batch DONE
```

- **Batches** (`payment_batches`) are sharded by `batchId`; **payments** by
  `paymentId`. So a batch's payments are spread across all shards while the batch
  row itself lives on one shard — the worker and status query scatter-gather over
  payments. (Verified live: a 4-payment batch landed 2 / 2 / 0 across shards with
  the batch row on a third shard.)
- The "remote system" is a `RemoteSystemClient` (a simulated, idempotent stand-in
  in `SimulatedRemoteSystemClient`; swap for a real HTTP client in production).
- **Exactly-once-ish processing:** each payment is claimed with an atomic
  `UPDATE ... WHERE status='PENDING'`, and the remote client de-duplicates on
  `paymentId`, so worker retries never create a duplicate remote entry.
- Knobs: `payments.worker.interval-ms`, `payments.remote.latency-ms`.

Try it (stack must be up): `scripts/demo-async.sh`

## Validating uniform distribution

```bash
scripts/validate-sharding.sh 300
```

Brings up the stack, posts N payments, then compares the per-shard counts both
via the app and by querying each Postgres directly. Example run (300 payments):

```
shard0 : 115 rows
shard1 :  89 rows
shard2 :  96 rows
RESULT: PASS — payments are distributed uniformly across the 3 shards.
```

The pure routing logic is also unit-tested for uniformity in `ShardRouterTest`
(no database required).
