# Load test

`bid-burst.js` is a [k6](https://k6.io/) scenario for the PDR §22 "Load" row: it ramps up to 140
concurrent virtual bidders all piling onto the same hot auction, then checks that p95 bid-decision
latency stays under 2s and the HTTP failure rate stays under 1% even under that single-writer
contention. (140, not PDR §22's aspirational 200 - `RateLimitFilter`'s own `AUTH_LIMIT`,
300/min/IP, caps how many bidder sessions `setup()` can register from one source IP before it
would start tripping the limiter itself; see the `BIDDER_POOL_SIZE` comment in the script.)

Run against a live instance backed by the local dev stack (`docker/docker-compose.yml`):

```
./gradlew bootRun
k6 run load-test/bid-burst.js
```

Override the target with `BASE_URL` if the app isn't on `localhost:8080`.

## Recorded result (2026-08-20, local dev stack)

This scenario had never actually been run before QA-REVIEW.md's production-readiness pass flagged
it - running it for the first time also surfaced and fixed several real bugs in the script itself
(a body field where the API actually requires a header, a `__VU`/`__ITER` reference inside
`setup()` where neither exists, registering a fresh bidder every single iteration instead of once
per session, and an `http_req_failed` threshold that counted a bid legitimately losing its race as
a failure). Per PDR §20/§22's "quote only load-tested numbers, never aspirational ones":

| Metric | Result | Threshold |
|---|---|---|
| Bid decision latency p95 | **13ms** | < 2000ms |
| HTTP failure rate | **0.00%** | < 1% |
| Check pass rate | **100%** (7,827/7,827) | — |
| Sustained throughput | ~54 bids/sec at peak (140 concurrent bidders) | — |

**Caveats - this is a local-stack number, not a production one:**
- Single-broker Kafka/Postgres/Redis via `docker/docker-compose.yml`, run natively on a Windows
  dev machine (not the k6 Docker image - `host.docker.internal` through Docker Desktop's Windows
  network proxy introduced its own timeouts unrelated to the app, so this run used a native k6
  binary instead to isolate the actual application's behavior from that environment's networking
  layer).
- 140 concurrent bidders, not 200, for the `AUTH_LIMIT` reason above.
- Says nothing about multi-broker replication overhead, network latency to a real cluster, or
  behavior under the Kafka replication-factor-3 configuration production is expected to run
  (`bidstream.kafka.topic-replication-factor`).

Treat this as evidence the correctness/latency profile is sound at this scale on this hardware,
not as a production capacity/SLA number - re-run on a staging environment sized like production
before quoting one.
