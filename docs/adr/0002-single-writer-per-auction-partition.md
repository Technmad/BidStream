# ADR-0002: Single writer per auction via Kafka partition key

## Status
Accepted

## Context
Concurrent bids on the same auction (PDR §22's 1000-simultaneous-bidder must-have) need a total
order and exactly one accepted outcome per decision point, without resorting to row-level DB
locking under high contention, and without a distributed lock service on the hot path.

## Decision
Every `BidCommand`/`CloseCommand` for an auction is published keyed by `auctionId` to
`auction.commands`. Kafka guarantees all messages with the same key land on the same partition,
in publish order. Each partition is consumed by exactly one consumer thread at a time (standard
consumer-group semantics), so - as long as a partition isn't oversubscribed relative to consumer
count - every command for a given auction is handled by one thread, one at a time.
`AuctionCommandProcessor` therefore needs no locking of its own for a single auction; the
optimistic-lock `version` check on the `auctions` row is a defense-in-depth backstop, not the
primary correctness mechanism (verified directly in `ConcurrentBiddingIT`: after 1000 concurrent
submissions, `version` equals the accepted-bid count exactly).

## Consequences
- CLOSE is published on the exact same partition/key as bids for that auction, so "did this bid
  beat the close" is answered purely by log position (PDR §11.3) - no separate coordination
  needed between the bidding and closing paths.
- Throughput for one specific hot auction is capped by a single consumer thread's speed - this is
  an accepted trade-off; horizontal scale comes from more auctions (more distinct keys), not from
  parallelizing one auction's own command stream.
- The number of `auction.commands` partitions is an upper bound on effective processor
  parallelism; it must be sized for peak concurrent-hot-auctions, not total auction count.
