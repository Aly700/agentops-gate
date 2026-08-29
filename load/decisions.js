// k6 load test for POST /decisions. Usage:
//   BASE_URL=http://<host>:8080 API_KEY=... POLICY_ID=... k6 run load/decisions.js
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const latency = new Trend('decision_latency_ms', true);
const base = __ENV.BASE_URL;
const headers = { 'X-API-Key': __ENV.API_KEY, 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 5, timeUnit: '1s', preAllocatedVUs: 20, maxVUs: 100,
      stages: [
        { target: 20, duration: '20s' },
        { target: 50, duration: '30s' },
        { target: 50, duration: '30s' },
      ],
    },
  },
  thresholds: { http_req_failed: ['rate<0.01'], decision_latency_ms: ['p(99)<1000'] },
};

export default function () {
  const tools = ['browser.read', 'shell.exec', 'fs.write'];
  const tool = tools[Math.floor(Math.random() * tools.length)];
  const body = JSON.stringify({
    policyId: __ENV.POLICY_ID, agentId: `agent-${__VU}`, toolName: tool,
    arguments: { i: __ITER }, riskTier: tool === 'fs.write' ? 'HIGH' : 'LOW',
  });
  const requestHeaders = {
    ...headers,
    'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
  };
  const res = http.post(`${base}/decisions`, body, { headers: requestHeaders });
  latency.add(res.timings.duration);
  check(res, { 'status 200/201': (r) => r.status === 200 || r.status === 201 });
}
