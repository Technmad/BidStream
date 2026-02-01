# BidStream — Real-Time Auction Platform

Event-driven, horizontally-scalable real-time auction platform. See
[`PDR-RealTimeAuctionPlatform.md`](./PDR-RealTimeAuctionPlatform.md) for the full design.

## Stack

Java 21 (LTS) · Spring Boot 3 · Apache Kafka (KRaft) · PostgreSQL 16 · Redis 7 · Docker

## Getting started

```bash
./gradlew build
```

Local infrastructure (Postgres/Redis/Kafka/observability stack) is brought up via
`docker/docker-compose.yml` (added in a later commit).

## Project layout

Hexagonal (ports & adapters) — see PDR §7.3 for the full rationale:

```
domain/       pure business logic, no framework imports
application/  use-case orchestrators
adapter/      in (rest, ws, kafka) / out (persistence, cache, messaging)
config/       Spring wiring
```
