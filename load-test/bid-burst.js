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
        { duration: '30s', target: 50 },
        { duration: '1m', target: 200 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    bid_decision_latency_ms: ['p(95)<2000'],
  },
};

function registerAndLogin(prefix) {
  const username = `${prefix}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2, 8)}`;
  http.post(`${BASE_URL}/api/v1/auth/register`, JSON.stringify({
    username, email: `${username}@example.com`, password: 'password123',
  }), { headers: { 'Content-Type': 'application/json' } });

  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    username, password: 'password123',
  }), { headers: { 'Content-Type': 'application/json' } });
  return loginRes.json('accessToken');
}

// Set once by the setup() VU and shared read-only by every VU/iteration - every bidder piles
// onto the SAME hot auction, exactly the single-writer-partition contention PDR §22 asks for.
export function setup() {
  const sellerToken = registerAndLogin('load-seller');
  const start = new Date(Date.now() - 60_000).toISOString();
  const end = new Date(Date.now() + 3600_000).toISOString();
  const res = http.post(`${BASE_URL}/api/v1/auctions`, JSON.stringify({
    title: 'Load Test Lot', startingPrice: 100.00, minIncrement: 1.00, startTime: start, endTime: end,
  }), { headers: { Authorization: `Bearer ${sellerToken}`, 'Content-Type': 'application/json' } });
  return { auctionId: res.json('id') };
}

export default function (data) {
  const bidderToken = registerAndLogin('load-bidder');
  const amount = (100 + Math.random() * 100000).toFixed(2);

  const t0 = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/auctions/${data.auctionId}/bids`, JSON.stringify({
    amount, currency: 'USD', idempotencyKey: `${__VU}-${__ITER}-${Date.now()}`,
  }), { headers: { Authorization: `Bearer ${bidderToken}`, 'Content-Type': 'application/json' } });
  decisionLatency.add(Date.now() - t0);

  check(res, {
    'decision status is 200 or 409 (accepted or lost the race)': (r) => r.status === 200 || r.status === 409,
  });

  sleep(0.1);
}
