// k6 load: creates orders at a fixed arrival rate. Saga completion is measured from the
// database afterwards (bench/run.sh), so this script only times the API call.
import http from 'k6/http';
import { check } from 'k6';

const API = __ENV.API || 'http://127.0.0.1:18085';
const KEY = __ENV.KEY;
const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION = __ENV.DURATION || '30s';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: Math.max(200, RATE * 4),
    },
  },
  summaryTrendStats: ['p(50)', 'p(99)', 'max'],
};

const body = JSON.stringify({
  items: [
    { sku: 'mug', quantity: 2, unit_amount: 1250 },
    { sku: 'tea', quantity: 1, unit_amount: 800 },
  ],
  currency: 'EUR',
  shipping_address: {
    name: 'Alice Example', line1: '1 Main Street', city: 'Springfield',
    postal_code: '12345', country: 'NL',
  },
  payment_method: 'pm_card_ok',
});

export default function () {
  const res = http.post(`${API}/v1/orders`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${KEY}`,
      'Idempotency-Key': `bench-${__VU}-${__ITER}-${Date.now()}`,
    },
  });
  check(res, { created: (r) => r.status === 201 });
}
