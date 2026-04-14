# Load test

`bid-burst.js` is a [k6](https://k6.io/) scenario for the PDR §22 "Load" row: it ramps up to 200
concurrent virtual bidders all piling onto the same hot auction, then checks that p95 bid-decision
latency stays under 2s and the HTTP failure rate stays under 1% even under that single-writer
contention.

Run against a live instance backed by the local dev stack (`docker/docker-compose.yml`):

```
./gradlew bootRun
k6 run load-test/bid-burst.js
```

Override the target with `BASE_URL` if the app isn't on `localhost:8080`.
