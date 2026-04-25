# ADR-0005: A dedicated, non-partitioned table for idempotency-key uniqueness

## Status
Accepted

## Context
`bids` is range-partitioned by `created_at` (ADR needed for §8's monthly partitioning), and
Postgres requires every unique constraint on a partitioned table to include the partition key.
That's why `UNIQUE (auction_id, bidder_id, idempotency_key, created_at)` includes `created_at` -
a structural requirement of partitioning, not a semantic one. `created_at` on a bid row is the
edge-stamped `occurredAt`, generated fresh per HTTP request. Two independent client requests that
happen to reuse the same `Idempotency-Key` (a genuine retry, or a buggy client) therefore get two
*different* `created_at` values and never collide on the constraint - it only catches an exact
Kafka redelivery of the identical message (same `occurredAt` because it's literally the same
serialized command), never a second, distinct request reusing a key. In practice
`AuctionItem.placeBid`'s own price-increment invariant happens to reject an exact-amount
resubmission anyway, but that's an accidental side effect of the domain rules, not the designed
idempotency guard - and it surfaces to the client as a confusing `BELOW_MIN_INCREMENT` rather
than a clear "you already used this key."

## Decision
A small, deliberately **non-partitioned** table, `bid_idempotency_keys`, with
`PRIMARY KEY (auction_id, bidder_id, idempotency_key)` - no `created_at` in the key, so it has no
partitioning constraint to satisfy and can enforce true cross-request uniqueness.
`AuctionCommandProcessor.process()` claims a row (`INSERT ... ON CONFLICT DO NOTHING`) inside the
same transaction as everything else, immediately after the existing `processed_events`/`eventId`
replay check and before evaluating `placeBid`. A failed claim produces a normal
`BidRejectReason.DUPLICATE_IDEMPOTENCY_KEY` rejection, recorded on `processed_events` and
published to `bids.rejected` exactly like any other decision.

The claim is unconditional and permanent, mirroring `IdempotencyKeyGuard`'s existing Redis
fast-path semantics: once a key is claimed, it stays claimed regardless of whether the bid itself
is later accepted or rejected on its merits. A client that wants a genuine second attempt (e.g.
after correcting a too-low amount) mints a new key, rather than expecting the same key to be
reusable.

## Consequences
- `Idempotency-Key` reuse now produces one consistent, well-labeled rejection
  (`DUPLICATE_IDEMPOTENCY_KEY`) regardless of timing - not a data-dependent, accidental
  `BELOW_MIN_INCREMENT`/`ALREADY_HIGHEST` that happens to catch it only sometimes.
- `bid_idempotency_keys` has no pruning job, same as `outbox` (ADR-0004) - a reasonable follow-up
  once row volume warrants it, not addressed here.
- The existing edge-side checks (`IdempotencyKeyGuard`'s Redis `SETNX`, and the pre-Kafka
  `bidRepository.existsByIdempotencyKey` SELECT) are unchanged and still valuable as cheap,
  non-atomic load-shedding hints - this table is the correctness backstop for the race window
  they can't close, not a replacement for them.
