# ADR-0003: Integration tests run against the local dev stack, not Testcontainers

## Status
Accepted

## Context
Testcontainers (1.20.x/1.21.x) was the original plan for spinning up ephemeral Postgres/Kafka/
Redis per test run. In this environment (Docker Desktop 4.79), every Testcontainers-driven
container failed to start. Direct `curl` calls against the Docker Engine API confirmed the root
cause: Docker Desktop 4.79 returns a stub/empty HTTP 400 for any Docker API request below API
version 1.40, which breaks Testcontainers' own internal connectivity/version check before it ever
gets to creating a container. This reproduced across multiple Testcontainers versions and is a
Docker Desktop behavior, not something fixable from application or test code.

## Decision
All integration tests (`*IT`) run against the already-running `docker/docker-compose.yml` stack
instead of ephemeral per-test containers. `@SpringBootTest` connects to the fixed local
Postgres/Kafka/Redis endpoints (`localhost:5433`/`9092`/`6379`) exactly as a developer's own
`bootRun` would.

## Consequences
- Integration tests require `docker compose -f docker/docker-compose.yml up -d` to have been run
  first; they do not start their own infrastructure. This is a real trade-off versus
  Testcontainers' isolation - it's documented here rather than left to be rediscovered.
- Tests that create durable side effects (rows, topics, Redis keys, or - as in
  `PartitionMaintenanceJdbcRepositoryIT` - whole partition tables) must clean up after themselves
  in `@AfterEach`/test bodies, since the database is shared across runs rather than thrown away.
- If Docker Desktop's Testcontainers compatibility is fixed in a future version, reintroducing
  ephemeral containers is a legitimate follow-up - nothing here is architecturally opposed to it,
  it was purely an environment constraint at the time this was built.
