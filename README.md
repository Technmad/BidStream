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

## Project layout

Hexagonal (ports & adapters) — see PDR §7.3 for the full rationale:

```
domain/       pure business logic, no framework imports
application/  use-case orchestrators
adapter/      in (rest, ws, kafka) / out (persistence, cache, messaging)
config/       Spring wiring
```
