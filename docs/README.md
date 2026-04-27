# Documentation index

Start with the root [`README.md`](../README.md) for a quickstart. Everything else lives here,
grouped by what question it answers.

## Design & architecture

| Doc | Answers |
|---|---|
| [`PDR-RealTimeAuctionPlatform.md`](../PDR-RealTimeAuctionPlatform.md) | The full design: concurrency model, single-writer-per-partition ordering, real-time clock sync contract, failure modes. Start here for *why* the system is built this way. |
| [`adr/0001-event-driven-not-event-sourced.md`](adr/0001-event-driven-not-event-sourced.md) | Why Kafka is a durable ingestion log, not the system of record. |
| [`adr/0002-single-writer-per-auction-partition.md`](adr/0002-single-writer-per-auction-partition.md) | Why each auction's commands are ordered by a single partition writer. |
| [`adr/0003-integration-tests-against-local-stack-not-testcontainers.md`](adr/0003-integration-tests-against-local-stack-not-testcontainers.md) | Why `*IT` tests run against `docker compose`, not Testcontainers. |
| [`adr/0004-transactional-outbox-for-event-publishing.md`](adr/0004-transactional-outbox-for-event-publishing.md) | How DB writes and Kafka publishes stay consistent. |
| [`adr/0005-dedicated-idempotency-key-table.md`](adr/0005-dedicated-idempotency-key-table.md) | Why idempotency has its own table alongside the Redis fast-path. |

## Operating the system

| Doc | Answers |
|---|---|
| [`RUNBOOK.md`](RUNBOOK.md) | Local dev commands, monitoring, common incidents (DLQ messages, stuck schedulers, missing bid partitions), known limitations. |
| [`QA-REVIEW.md`](QA-REVIEW.md) | Findings from the last QA pass and their resolution status. |
| [`../k8s/README.md`](../k8s/README.md) | Deploying to Kubernetes (manifests, HPA, secrets). |
| [`../load-test/README.md`](../load-test/README.md) | Running the k6 bid-burst load test. |

## API reference

Live Swagger UI at `/swagger-ui.html` (raw OpenAPI document at `/v3/api-docs`) once the app is
running — see the root README's quickstart. There is no separate hand-written API doc; the
`@Operation`/`@Tag` annotations on each controller in `adapter/in/rest` are the source of truth
and are expected to stay accurate (see [`CONTRIBUTING.md`](../CONTRIBUTING.md)).

## Contributing

[`CONTRIBUTING.md`](../CONTRIBUTING.md) — local setup, architecture rules (hexagonal layering),
test conventions, PR expectations.
