# Contributing to BidStream

Thanks for looking at BidStream. This project favors correctness and clear failure semantics over
speed of iteration — read the PDR before making architectural changes, since most of the
non-obvious design choices are justified there rather than in code comments.

## Before you start

1. Read [`PDR-RealTimeAuctionPlatform.md`](PDR-RealTimeAuctionPlatform.md) — the authoritative
   design doc (concurrency model, failure modes, real-time contract).
2. Skim [`docs/adr/`](docs/adr/) — five ADRs record *why* certain choices were made (event-driven
   vs event-sourced, single-writer-per-partition, transactional outbox, etc.). If a change conflicts
   with an existing ADR, open a discussion before writing code — don't silently work around it.
3. Check [`docs/RUNBOOK.md`](docs/RUNBOOK.md) for known limitations so you don't rediscover them.

## Local setup

```bash
./gradlew build
docker compose -f docker/docker-compose.yml up -d   # Postgres, Redis, Kafka (KRaft), Prometheus, Grafana
./gradlew bootRun
```

API docs (Swagger UI) are served at `http://localhost:8080/swagger-ui.html` once the app is
running; the raw OpenAPI document is at `/v3/api-docs`.

## Architecture rules

BidStream is hexagonal (ports & adapters):

```
domain/       pure business logic — no Spring, no framework imports, no I/O
application/  use-case orchestrators — depend on domain + ports only
adapter/in/   inbound adapters (rest, ws, kafka consumers, scheduler)
adapter/out/  outbound adapters (persistence, cache, messaging) — implement domain ports
config/       Spring wiring only
```

- `domain/` must never import Spring, JPA, Kafka, or any adapter package. If a domain class needs
  something from outside, define a port (interface) in `domain/port` and implement it in `adapter/out`.
- New endpoints go in `adapter/in/rest`, and should use the same `@RestController` + DTO-record
  pattern as the existing controllers (see `AuctionController`, `BidController`). Add OpenAPI
  annotations (`@Tag`, `@Operation`, and `@SecurityRequirements` on endpoints that don't require a
  token) so Swagger UI at `/swagger-ui.html` stays accurate — that's the API reference, there is no
  separate hand-written API doc to keep in sync.
- Anything that touches ordering, idempotency, or the outbox should reference the relevant PDR
  section number in a comment, the way `JwtKeyConfig` and `BidController` already do — this is the
  convention for explaining *why*, not *what*.

## Testing

- `./gradlew test` runs unit tests and integration tests (`*IT` suffix) together.
- Per [ADR-0003](docs/adr/0003-integration-tests-against-local-stack-not-testcontainers.md),
  integration tests run against the **real local Docker stack** (`docker compose up -d`), not
  Testcontainers — bring the stack up before running `*IT` tests locally, and expect CI to do the
  same (see `.github/workflows/ci.yml`).
- Unit tests for pure domain logic (e.g. `MoneyTest`, `AutoBidResolverTest`) should not require any
  running infrastructure.
- New behavior around bidding, auto-bid resolution, or auction lifecycle needs a test that exercises
  concurrent/racing writes where relevant — this codebase treats race conditions as first-class bugs.

## Pull requests

- Keep PRs scoped to one concern; note in the description which ADR (if any) the change touches.
- CI (`.github/workflows/ci.yml`) must pass: JDK 21 build against the Docker Compose stack.
- If you change the API surface, update the endpoint's OpenAPI annotations in the same PR so
  Swagger UI doesn't drift from the code.
