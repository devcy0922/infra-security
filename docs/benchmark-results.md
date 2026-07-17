# 벤치마크 결과

> 측정 환경: 격리된 프로젝트 서버
> 도구: [k6](https://k6.io) v0.51  
> 날짜: 2024-09-20

---

## 1. Keycloak HA — 동시 세션 처리

### 테스트 시나리오

```
Virtual Users: 500 (단계적 증가 — ramp-up 60s)
Duration:      5분
시나리오:      로그인 → 토큰 검증 → 갱신 → 로그아웃
```

### 결과 요약

| 지표 | 단일 노드 | HA 2노드 |
|---|---|---|
| 요청 처리량 (RPS) | 312 | 587 |
| P50 응답시간 | 87ms | 91ms |
| P95 응답시간 | 198ms | 203ms |
| P99 응답시간 | 341ms | **276ms** |
| 에러율 | 0.12% | 0.04% |
| 최대 동시 세션 | 500 | 500 |

> **HA 효과**: P99 응답시간 341ms → 276ms (19% 개선), 처리량 88% 향상

### 노드 장애 복구 테스트

```
테스트: 300 VU 세션 유지 중 keycloak-1 강제 종료
관찰: keycloak-2가 세션을 이어받아 서비스 지속 여부
```

| 항목 | 측정값 |
|---|---|
| 장애 감지 시간 (Nginx fail_timeout) | 10초 |
| 세션 유실 비율 | **0%** (Infinispan 분산 세션) |
| 장애 중 에러율 | 2.1% (10초 장애 감지 구간) |
| keycloak-1 재기동 후 세션 복구 | 자동 (재로그인 불필요) |

```
k6 출력 (요약):
  ✓ login_success........: 99.96% ✓ 89412 ✗ 34
  ✓ token_valid..........: 100%   ✓ 87341
  ✓ session_after_failover: 100%   ✓ 2847   ← 노드 장애 후 세션 유지
  http_req_duration......: avg=94ms  p(95)=203ms  p(99)=276ms
```

---

## 2. ProxySQL — 쿼리 라우팅 + 감사 로깅

### 테스트 시나리오

```
혼합 쿼리 비율: SELECT 70% / INSERT 15% / UPDATE 10% / DELETE 5%
동시 연결: 100
Duration: 3분
```

### 결과 요약

| 지표 | ProxySQL 없음 | ProxySQL (감사 off) | ProxySQL (CUD 감사 on) |
|---|---|---|---|
| 평균 쿼리 레이턴시 | 기준 | +0.4ms | +0.7ms |
| Writer 부하 (QPS) | 100% | 30% | 30% |
| Reader 부하 (QPS) | 0% | 70% | 70% |
| 감사 로그 생성 속도 | - | - | ~900 events/min |
| 누락된 감사 이벤트 | - | - | **0건** |

> **Read/Write Split 효과**: Writer 부하 70% 감소 (SELECT → Reader 라우팅)

### 감사 로그 무결성 검증

```bash
# CUD 쿼리 3,000건 실행 후 감사 로그 카운트 비교
mysql_cud_count=3000
audit_log_count=$(grep -c '"log":1' proxysql/logs/audit.log)

echo "CUD 실행: $mysql_cud_count / 감사 기록: $audit_log_count"
# 출력: CUD 실행: 3000 / 감사 기록: 3000  ← 100% 일치

# SELECT 감사 기록 없음 확인
select_in_audit=$(grep -c 'SELECT' proxysql/logs/audit.log)
echo "SELECT 감사 기록: $select_in_audit건"
# 출력: SELECT 감사 기록: 0건
```

---

## 3. Lazy Migration — 이관 처리 성능

### 테스트 시나리오

```
레거시 유저 1,000명 동시 첫 로그인 (Migration 발생)
```

| 지표 | 측정값 |
|---|---|
| 이관 1건 소요 시간 (bcrypt 검증 + KC 저장) | avg 87ms |
| 동시 이관 처리량 | 약 115건/초 |
| 이관 실패율 (네트워크 정상 환경) | 0% |
| 레거시 DB `migrated_at` 갱신 누락 | 0건 |
| 일반 로그인 대비 추가 레이턴시 | +72ms (bcrypt 검증 비용) |

> **bcrypt rounds=12**: CPU 집약적이나 브루트포스 방어에 적합.  
> 이관 완료 후 PBKDF2 전환으로 로그인 성능 정상화됨.

---

## 4. 부하 테스트 스크립트

→ [`load-test/keycloak-ha.js`](../load-test/keycloak-ha.js)

```bash
# 실행
KC_URL=https://localhost:8443 k6 run load-test/keycloak-ha.js

# 결과 Grafana 연동 (InfluxDB 출력)
k6 run --out influxdb=http://<YOUR_HOST>:8086/k6 load-test/keycloak-ha.js
```
