# ADR-0001: Event-driven, not event-sourced

## Status
Accepted

## Context
The PDR's ordering and idempotency requirements (§9, §19) could be met either by treating Kafka
as the durable log of record (event sourcing - state is derived by replaying events) or by
treating Kafka purely as an ordered, durable *command* channel feeding a system where PostgreSQL
remains the source of truth.

## Decision
PostgreSQL is the system of record for committed state, history, and settlement. Kafka's
`auction.commands` topic carries BID and CLOSE **commands**, ordered per-partition by
`auctionId`, consumed by a single writer per auction. Redis is a fully rebuildable projection,
never authoritative. A bid is *accepted-for-processing* once durably in Kafka, and *committed*
once its row is flushed to Postgres (PDR §3).

## Consequences
- Replaying `auction.commands` from the beginning does **not** reconstruct auction state by
  itself - only Postgres can answer "what happened." The `processed_events` ledger exists
  specifically to make replay of the same command idempotent, not to make replay a rebuild
  mechanism.
- Losing Kafka's retained history has no effect on correctness of already-committed auctions;
  losing Postgres does.
- Standard relational tooling (Flyway migrations, `psql`, ordinary backups) is all that's needed
  to inspect or restore state - no event-store replay tooling required.
