# BidStream — Real-Time Auction Platform

Event-driven, horizontally-scalable real-time auction platform. See
[`PDR-RealTimeAuctionPlatform.md`](./PDR-RealTimeAuctionPlatform.md) for the full design.

## Stack

Java 21 (LTS) · Spring Boot 3 · Apache Kafka (KRaft) · PostgreSQL 16 · Redis 7 · Docker

## Getting started

```bash
./gradlew build

# bring up Postgres, Redis, Kafka (KRaft), Kafka UI, Prometheus, Grafana
docker compose -f docker/docker-compose.yml up -d
```

| Service | URL |
|---|---|
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
