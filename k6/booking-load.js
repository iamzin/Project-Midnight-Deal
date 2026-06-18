import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    baseline: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s',
      duration: '1m', preAllocatedVUs: 100, maxVUs: 300 },
    burst: { executor: 'ramping-arrival-rate', startRate: 50, timeUnit: '1s',
      startTime: '1m', preAllocatedVUs: 500, maxVUs: 2000,
      stages: [ { target: 1000, duration: '30s' }, { target: 1000, duration: '2m' }, { target: 50, duration: '30s' } ] },
  },
};

const BASE = __ENV.BASE || 'http://localhost:8080';

// 부하 시작 전 상품을 임의 오픈한다. 오픈하지 않으면 모든 booking이 NOT_OPEN(409)으로 막힌다.
export function setup() {
  const r = http.post(`${BASE}/admin/products/1/open`);
  check(r, { 'product opened': (res) => res.status === 200 });
}

export default function () {
  const uid = Math.floor(Math.random() * 1000000) + 1000;
  const body = JSON.stringify({ productId: 1, userId: uid, totalAmount: 100000,
    payments: [ { method: 'CREDIT_CARD', amount: 90000 }, { method: 'Y_POINT', amount: 10000 } ] });
  const res = http.post(`${BASE}/api/bookings`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `load-${uid}-${__ITER}` } });
  check(res, { 'no 5xx': (r) => r.status < 500 }); // 4xx(매진/중복)는 정상
}
