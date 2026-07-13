/**
 * k6 부하 테스트 — Keycloak HA 동시 세션 + 장애 복구
 *
 * 실행:
 *   KC_URL=https://localhost:8443 k6 run load-test/keycloak-ha.js
 *
 * 환경변수:
 *   KC_URL      Keycloak 기본 URL
 *   KC_REALM    Realm 이름 (기본: infrasec)
 *   KC_CLIENT   Client ID (기본: test-client)
 *   KC_USER     테스트 유저 (기본: staff.user01)
 *   KC_PASS     테스트 패스워드 (기본: Demo1234!)
 */

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ── 커스텀 메트릭 ──────────────────────────────────────────
const loginSuccessRate    = new Rate('login_success');
const tokenValidRate      = new Rate('token_valid');
const loginDuration       = new Trend('login_duration_ms', true);
const migrationCounter    = new Counter('lazy_migration_triggered');

// ── 환경 설정 ─────────────────────────────────────────────
const BASE_URL  = __ENV.KC_URL    || 'https://localhost:8443';
const REALM     = __ENV.KC_REALM  || 'infrasec';
const CLIENT_ID = __ENV.KC_CLIENT || 'test-client';
const USERNAME  = __ENV.KC_USER   || 'staff.user01';
const PASSWORD  = __ENV.KC_PASS   || 'Demo1234!';

const TOKEN_URL = `${BASE_URL}/auth/realms/${REALM}/protocol/openid-connect/token`;
const INTROSPECT_URL = `${BASE_URL}/auth/realms/${REALM}/protocol/openid-connect/token/introspect`;

// ── 부하 프로파일 ──────────────────────────────────────────
export const options = {
  stages: [
    { duration: '30s', target: 50  },   // 워밍업
    { duration: '60s', target: 200 },   // 증가
    { duration: '120s', target: 500 },  // 최대 부하 유지
    { duration: '30s', target: 0   },   // 종료
  ],

  thresholds: {
    http_req_duration:     ['p(95)<300', 'p(99)<500'],
    http_req_failed:       ['rate<0.02'],         // 에러율 2% 미만
    login_success:         ['rate>0.98'],          // 로그인 성공률 98% 이상
    login_duration_ms:     ['p(99)<450'],
  },

  // TLS self-signed 허용 (개발 환경)
  insecureSkipTLSVerify: true,
};

// ── 메인 시나리오 ──────────────────────────────────────────
export default function () {
  group('1. 로그인 (Password Grant)', () => {
    const start = Date.now();

    const res = http.post(TOKEN_URL, {
      client_id:  CLIENT_ID,
      username:   USERNAME,
      password:   PASSWORD,
      grant_type: 'password',
    }, {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      tags: { name: 'login' },
    });

    loginDuration.add(Date.now() - start);

    const isSuccess = check(res, {
      'status 200': (r) => r.status === 200,
      'access_token 존재': (r) => !!r.json('access_token'),
      'token_type bearer': (r) => r.json('token_type') === 'Bearer',
      'expires_in <= 300': (r) => r.json('expires_in') <= 300,  // Access Token 5분 검증
    });

    loginSuccessRate.add(isSuccess);

    if (!isSuccess) return;

    const accessToken  = res.json('access_token');
    const refreshToken = res.json('refresh_token');

    // Lazy Migration 발생 여부 (응답 헤더 또는 응답 시간으로 간접 감지)
    if (res.timings.duration > 150) {
      migrationCounter.add(1);
    }

    sleep(1);

    group('2. 토큰 검증 (Introspect)', () => {
      const introRes = http.post(INTROSPECT_URL, {
        client_id: CLIENT_ID,
        token:     accessToken,
      }, {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        tags: { name: 'introspect' },
      });

      tokenValidRate.add(check(introRes, {
        'introspect 200':   (r) => r.status === 200,
        'token active':     (r) => r.json('active') === true,
        'sub 존재':         (r) => !!r.json('sub'),
      }));
    });

    sleep(2);

    group('3. Refresh Token 갱신 (RTR 검증)', () => {
      const refreshRes = http.post(TOKEN_URL, {
        client_id:     CLIENT_ID,
        grant_type:    'refresh_token',
        refresh_token: refreshToken,
      }, {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        tags: { name: 'refresh' },
      });

      check(refreshRes, {
        'refresh 200': (r) => r.status === 200,
        '새 access_token 발급': (r) => !!r.json('access_token'),
        '새 refresh_token 발급 (RTR)': (r) => !!r.json('refresh_token'),
      });
    });

    sleep(1);
  });
}

// ── 장애 복구 시나리오 (별도 실행) ──────────────────────────
// k6 run --env SCENARIO=failover load-test/keycloak-ha.js
export function failoverScenario() {
  if (__ENV.SCENARIO !== 'failover') return;

  // 세션 획득
  const loginRes = http.post(TOKEN_URL, {
    client_id: CLIENT_ID, username: USERNAME,
    password: PASSWORD, grant_type: 'password',
  });

  if (loginRes.status !== 200) return;

  const token = loginRes.json('access_token');

  // 노드 1 종료 후 10초 대기 동안 토큰 검증 반복
  for (let i = 0; i < 5; i++) {
    const introRes = http.post(INTROSPECT_URL, {
      client_id: CLIENT_ID, token: token,
    });

    check(introRes, {
      'failover 후 세션 유지': (r) => r.status === 200 && r.json('active') === true,
    });

    sleep(2);
  }
}
