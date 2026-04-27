# Runbook

## Local development

```bash
docker compose -f docker/docker-compose.yml up -d   # Postgres, Redis, Kafka, Kafka UI, Prometheus, Grafana
./gradlew bootRun
```

Flyway migrations (`src/main/resources/db/migration`) run automatically on startup.

## Running the tests

Integration tests (`*IT`) run against the real local stack above (see
[ADR-0003](adr/0003-integration-tests-against-local-stack-not-testcontainers.md)) - bring it up
first, then:

```bash
./gradlew test
```

`ConcurrentBiddingIT` (1000 simultaneous bidders) is the slowest test in the suite; expect the
full suite to take 1-3 minutes normally. If it takes 20-30+ minutes, the shared dev stack is
likely under unrelated load (e.g. another process hammering the same Kafka/Postgres) - it is not
itself a sign of a regression unless the assertions actually fail.

Load testing (k6, separate from the JUnit suite):

```bash
./gradlew bootRun &
k6 run load-test/bid-burst.js
```

## Monitoring

| What | Where |
|---|---|
| App health | `GET /actuator/health` (liveness/readiness sub-paths for k8s probes) |
| Metrics | `GET /actuator/prometheus` - scraped by the local Prometheus (`docker/docker-compose.yml`) |
| Dashboards | Grafana at `localhost:3000` (admin/admin) |
| Kafka topics/consumer lag | Kafka UI at `localhost:8090` |

Key custom metrics (PDR §18, added in `AuctionCommandProcessor`):

- `bidstream_bids_total{outcome="accepted|rejected"[,reason=...]}` - accept/reject rate.
- `bidstream_processor_replays_total` - how often the `processed_events` dedup gate fires
  (redelivered commands). A sustained non-zero rate usually means consumers are crash-looping or
  offset commits are failing, not a bug in the dedup logic itself.
- `bidstream_bid_decision_latency_seconds` - time from a command's `occurredAt` to its durable
  decision. Watch the p99 under load; a rising tail usually means the single writer for a hot
  auction's partition is falling behind.

## Common operational scenarios

### A command ended up on `auction.commands.DLQ`

Inspect it via Kafka UI or a raw consumer against `auction.commands.DLQ`. Every DLQ'd message
retried `FixedBackOff(1000L, 3)` (3 attempts, 1s apart) and still failed - almost always because
the referenced `auctionId` doesn't exist (a bad client, a data-migration edge case) or because of
a genuine processing bug. `DlqRoutingIT` is the reference test for this path. There is currently
no automated replay-after-fix tool for the DLQ; replaying is a manual re-publish to
`auction.commands` once the underlying issue is understood.

### The close-trigger or partition-maintenance scheduler seems stuck

Both use a short-TTL Redis lock (`lock:close-scheduler`, `lock:partition-maintenance-scheduler`)
purely so multiple app replicas don't duplicate work - never for correctness (duplicate CLOSEs and
duplicate `CREATE TABLE IF NOT EXISTS` calls are harmless no-ops). Check
`TTL lock:close-scheduler` / `TTL lock:partition-maintenance-scheduler` in Redis: if it's
permanently absent (not even briefly held), no replica is running the scheduler at all, which is
the actual thing worth investigating - not the lock itself.

### `no partition of relation "bids" found for row`

`PartitionMaintenanceScheduler` pre-creates the current and next month's `bids` partition daily
(cron `0 0 2 * * *` by default, `bidstream.partition-scheduler.cron`). If this has never run
successfully for 2+ months (the scheduler down, the leader lock never acquired by anyone, Redis
unavailable), a new bid can hit this error. Immediate fix: manually run the equivalent
`CREATE TABLE ... PARTITION OF bids FOR VALUES FROM (...) TO (...)` for the missing month, then
find out why the scheduler didn't.

### Rate limiting is rejecting legitimate traffic

`RateLimitFilter` enforces `AUTH_LIMIT` (300/min/IP) and `BID_LIMIT` (20/10s/user) via
`RedisRateLimiter`'s sliding-window Lua script. Both were tuned against this project's own test
suite traffic volume, not against any specific production load figure - revisit both constants
before relying on them at real scale.

## Known limitations (see also `k8s/README.md`)

- §9.6 write-behind batching (per-partition, 50-record/50ms flush) isn't implemented - every
  command still commits its own transaction and offset individually. Deliberately deferred: it's
  currently the *safer* state, since no un-flushed cross-message state exists for the working
  set's eviction to race against. Don't add batching without also adding eviction-pinning to
  `AuctionWorkingSet` in the same change.

## JWT signing key provisioning

`JwtKeyConfig` loads a fixed RSA key pair from `BIDSTREAM_JWT_PRIVATE_KEY_PEM`/
`BIDSTREAM_JWT_PUBLIC_KEY_PEM` (see `k8s/secret.example.yaml`) when they're set, and falls back to
generating an ephemeral per-instance key pair otherwise. Generate the pair once per environment and
put it in the Secret before scaling past one replica - an ephemeral key can't be verified by a
different pod, and a restart invalidates every outstanding session.
