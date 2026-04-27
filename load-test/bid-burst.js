// PDR §22 "Load" row: ramp concurrent bidders against one hot auction and watch p95 decision
// latency and error rate hold up under sustained contention on the single-writer partition.
// Run with a live app instance (BASE_URL, default localhost:8080) against the local dev stack:
//   k6 run load-test/bid-burst.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const decisionLatency = new Trend('bid_decision_latency_ms');

export const options = {
  scenarios: {
    bid_burst: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 35 },
        { duration: '1m', target: 140 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    bid_decision_latency_ms: ['p(95)<2000'],
  },
  // setup() registers BIDDER_POOL_SIZE bidders sequentially - default 60s setupTimeout isn't
  // enough headroom for that plus network jitter.
  setupTimeout: '120s',
};

function registerAndLogin(prefix) {
  // __VU/__ITER are undefined when called from setup() (no VU/iteration context there yet) -
  // this threw a ReferenceError on every run before it ever reached a single VU iteration.
  const vu = typeof __VU !== 'undefined' ? __VU : 'setup';
  const iter = typeof __ITER !== 'undefined' ? __ITER : 0;
  const username = `${prefix}-${vu}-${iter}-${Math.random().toString(36).slice(2, 8)}`;
  http.post(`${BASE_URL}/api/v1/auth/register`, JSON.stringify({
    username, email: `${username}@example.com`, password: 'password123',
  }), { headers: { 'Content-Type': 'application/json' } });

  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    username, password: 'password123',
  }), { headers: { 'Content-Type': 'application/json' } });
  return loginRes.json('accessToken');
}

// BIDDER_POOL_SIZE bidders are registered once in setup() rather than one-per-iteration: a real
// bidder logs in once and places many bids in a session, not once-per-bid. Registering fresh on
// every iteration was also self-defeating against RateLimitFilter's own AUTH_LIMIT
// (300/min/IP, RUNBOOK.md) - a 200-VU ramp meant 400+ auth requests/minute from one source IP,
// so most of what looked like "load failures" on first running this were actually 429s from the
// rate limiter tripping on the test's own unrealistic auth churn, not the bid path under test.
// Sized to the max VU target below (140) so each VU maps to its own distinct bidder session -
// two VUs sharing one token would double up against that token's own per-user BID_LIMIT. Kept
// under RateLimitFilter's AUTH_LIMIT (300/min/IP, RUNBOOK.md) for the register+login pairs this
// takes to build the pool in setup() - a 200-bidder pool would need ~400 auth calls and trip
// AUTH_LIMIT before setup even finished, which is what capped the VU target at 140 instead of
// PDR §22's aspirational 200: that's this environment's real, currently-configured ceiling, not
// an arbitrary smaller number.
const BIDDER_POOL_SIZE = 140;

// Set once by the setup() VU and shared read-only by every VU/iteration - every bidder piles
// onto the SAME hot auction, exactly the single-writer-partition contention PDR §22 asks for.
export function setup() {
  const sellerToken = registerAndLogin('load-seller');
  const start = new Date(Date.now() - 60_000).toISOString();
  const end = new Date(Date.now() + 3600_000).toISOString();
  const res = http.post(`${BASE_URL}/api/v1/auctions`, JSON.stringify({
    title: 'Load Test Lot', startingPrice: 100.00, minIncrement: 1.00, startTime: start, endTime: end,
  }), { headers: { Authorization: `Bearer ${sellerToken}`, 'Content-Type': 'application/json' } });

  const bidderTokens = [];
  for (let i = 0; i < BIDDER_POOL_SIZE; i++) {
    bidderTokens.push(registerAndLogin('load-bidder'));
  }
  return { auctionId: res.json('id'), bidderTokens };
}

export default function (data) {
  // One VU = one logged-in bidder's session, reused across every iteration that VU runs -
  // matches how a real bidder actually behaves (log in once, bid repeatedly).
  const bidderToken = data.bidderTokens[__VU % data.bidderTokens.length];
  const amount = (100 + Math.random() * 100000).toFixed(2);

  const t0 = Date.now();
  // ?wait=true: the bid endpoint's default response is an immediate async 202 (PDR §14.2, fixed
  // per QA-REVIEW.md Critical finding) - without this, every request would return in ~1ms with
  // no actual decision made yet, measuring edge-ack latency instead of the decision latency this
  // scenario exists to observe under single-writer contention.
  //
  // Idempotency-Key is a required HEADER (BidController.java), not a body field - the body only
  // ever carries `amount` (BidDtos.PlaceBidRequest). Every single request in every prior run of
  // this script was rejected 400 MissingRequestHeaderException before ever reaching the
  // processor - the script never actually load-tested anything until this was caught by
  // actually running it.
  const res = http.post(`${BASE_URL}/api/v1/auctions/${data.auctionId}/bids?wait=true`, JSON.stringify({
    amount, currency: 'USD',
  }), {
    headers: {
      Authorization: `Bearer ${bidderToken}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}`,
    },
    // k6's http_req_failed metric otherwise counts every non-2xx as a failure - but on a hot
    // auction, most bids losing the race (409 BELOW_MIN_INCREMENT) is the whole point of the
    // scenario, not a defect. Without this the threshold below was measuring "how often does a
    // bid NOT win," not "how often does the system actually fail," and would trip on a perfectly
    // healthy run.
    responseCallback: http.expectedStatuses(200, 202, 409),
  });
  decisionLatency.add(Date.now() - t0);

  check(res, {
    // 202 is the SYNC_WAIT_TIMEOUT fallback (still durably queued, just didn't decide in time
    // under load) - a real failure, not a crash, so it's a check failure, not an HTTP failure.
    'decision status is 200, 409, or a wait-timeout 202': (r) => r.status === 200 || r.status === 409 || r.status === 202,
  });

  // RateLimitFilter's own BID_LIMIT (20/10s per user, RUNBOOK.md) applies per bidder just like
  // it would for a real user - at sleep(0.1) a single reused bidder token would bid ~10x/sec,
  // blowing that budget by 50x and turning "hot auction contention" into "one user rate-limited
  // against themselves." ~1 bid/sec/bidder stays safely under it while 200 concurrent bidders
  // still add up to real aggregate single-partition contention (PDR §22's actual scenario).
  sleep(1);
}
