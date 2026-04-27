# BidStream — Real-Time Auction Platform

[![CI](https://github.com/Technmad/BidStream/actions/workflows/ci.yml/badge.svg)](.github/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen)
![License](https://img.shields.io/badge/license-Unlicensed-lightgrey)

Event-driven, horizontally-scalable real-time auction platform: users bid on live auctions with
prices streamed to every connected client over WebSocket, proxy/auto-bidding on their behalf, and
an anti-snipe rule that extends an auction's close whenever a bid lands in its final seconds. See
[`PDR-RealTimeAuctionPlatform.md`](./PDR-RealTimeAuctionPlatform.md) for the full design.

## Architecture at a glance

```
                 ┌────────────┐   POST /bids (202, edge-ack < 20ms p99)
  REST clients ─▶│  REST API  │──────────────┐
                 └────────────┘              ▼
                                        ┌───────────┐   single writer per     ┌────────────┐
  WS clients   ◀──── price/outcome ─────│   Kafka    │──  partition, ordered ▶│  Postgres  │
  (/topic/...)      pushes (async)      │  commands  │      commands          │ (source of │
                                        └───────────┘                        │   truth)   │
                                              │                               └────────────┘
                                              ▼
                                        ┌───────────┐
                                        │   Redis    │  rebuildable projections:
                                        │ (cache)    │  price, leaderboard, rate-limit, idempotency
                                        └───────────┘
```

A bid is durable in Kafka before the client ever gets a response; the single writer per auction
partition — not client-side timing — decides every outcome (PDR §11.3). See
[`docs/README.md`](docs/README.md) for the full documentation index (ADRs, runbook, API).

## Stack

Java 21 (LTS) · Spring Boot 3 · Apache Kafka (KRaft) · PostgreSQL 16 · Redis 7 · Docker

## Getting started

```bash
./gradlew build

# bring up Postgres, Redis, Kafka (KRaft), Kafka UI, Prometheus, Grafana
docker compose -f docker/docker-compose.yml up -d

./gradlew bootRun
```

| Service | URL |
|---|---|
| API | http://localhost:8080/api/v1 |
| API docs (Swagger UI) | http://localhost:8080/swagger-ui.html |
| Postgres | `localhost:5433` (db/user/pass: `bidstream`) |
| Redis | `localhost:6379` |
| Kafka | `localhost:9092` |
| Kafka UI | http://localhost:8090 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |

## Real-time contract (client-side notes)

Every `/topic/auctions/{id}` message (see `PriceUpdateMessage`) carries a `serverNow` timestamp
(PDR §15.5). The client is expected to:

1. Record `t0` (send time of any request) and `t1` (receive time of the reply/message carrying
   `serverNow`); estimate `offset = serverNow − (t0 + t1) / 2`, smoothing over several samples.
2. Run local countdowns against `endTime − (Date.now() + offset)`, never against the raw
   `endTime` compared to the client's own unadjusted clock.
3. Re-estimate the offset on every tick, since `serverNow` arrives with every `PRICE_UPDATE`.

The client clock is cosmetic only — no bid is ever accepted or rejected based on it. The single
writer's ordering of commands on the auction's partition is the only thing that decides outcomes
(PDR §11.3), so a badly-skewed client can only be wrong on screen, never in the result it gets.

## Project layout

Hexagonal (ports & adapters) — see PDR §7.3 for the full rationale:

```
domain/       pure business logic, no framework imports
application/  use-case orchestrators
adapter/      in (rest, ws, kafka) / out (persistence, cache, messaging)
config/       Spring wiring
```

## Testing

`./gradlew test` runs unit tests and integration tests (`*IT`) alike; the integration tests run
against the real local Docker stack rather than Testcontainers — see
[`docs/RUNBOOK.md`](docs/RUNBOOK.md) for how to run them and
[ADR-0003](docs/adr/0003-integration-tests-against-local-stack-not-testcontainers.md) for why.
A k6 load-test scenario lives in [`load-test/`](load-test/).

## Operations

[`docs/RUNBOOK.md`](docs/RUNBOOK.md) covers monitoring, common incidents (DLQ messages, stuck
schedulers, missing bid partitions, rate-limit tuning), and known limitations. Kubernetes
manifests are in [`k8s/`](k8s/). Key architectural decisions are recorded as ADRs in
[`docs/adr/`](docs/adr/).
