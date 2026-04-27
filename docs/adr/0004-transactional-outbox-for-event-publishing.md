# ADR-0004: Transactional outbox for publishing accepted-bid/close events

## Status
Accepted

## Context
Once a bid or close is committed to Postgres, downstream consumers (the notifier, read-model
projections, external integrations per PDR §10.1) need to learn about it via Kafka. Writing to
Postgres and publishing to Kafka are two separate systems with no shared transaction - a naive
"commit DB, then publish" sequence can commit the DB write and then crash or fail before the
Kafka publish, silently losing the event with no way to detect it happened.

## Decision
Every durable outcome writes a row to an `outbox` table in the **same transaction** as the
domain write (the bid row, the settlement row, etc.). A separate `OutboxRelay` poller reads
unpublished outbox rows and publishes them to Kafka at-least-once, marking them published only
after a successful send. If the relay crashes between publish and marking, it may publish the
same row again on restart - downstream consumers must therefore be idempotent on `eventId`
(mirroring the same dedup discipline `processed_events` gives the command side).

## Consequences
- No event is ever silently dropped: it either never got created (the DB transaction rolled back,
  so nothing downstream should learn about it anyway) or it's guaranteed to eventually reach
  Kafka.
- At-least-once delivery pushes the idempotency burden onto every consumer of `bids.accepted`,
  `bids.rejected`, and `auctions.events` - each carries a stable `eventId` for exactly this
  reason.
- The outbox table grows unboundedly without a cleanup job; unlike `processed_events` (pruned by
  `PartitionMaintenanceScheduler`, PDR §8.3), outbox pruning was out of scope for this build and
  is a reasonable follow-up once row volume warrants it.

**Update:** `PartitionMaintenanceScheduler` now also prunes `outbox` rows on the same nightly
tick (`bidstream.outbox.retention-days`, default 7), via `OutboxJdbcRepository.pruneOlderThan`.
Only rows with `published_at` already set are eligible — an unpublished row is still work the
relay owes Kafka and is never deleted regardless of age.
