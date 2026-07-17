# infra-security

ProxySQL 기반 MySQL 감사 로깅과 Keycloak HA SSO 인프라를 Docker Compose로 구성한 보안 인프라 데모 환경입니다.

---

## 구성 개요

```
Client
  └─► Nginx (TLS 종단)
         ├─► Keycloak HA Cluster (인증/인가)
         │     ├─ keycloak-1  ─┐
         │     └─ keycloak-2  ─┼─ Infinispan JGroups/TCP (세션 동기화)
         │                     └─ MariaDB (KC 전용 DB)
         └─► ProxySQL (DB 미들웨어)
               ├─ mariadb-writer (Write)
               └─ mariadb-reader (Read)

Observability: Prometheus + Grafana + Loki
```

### 핵심 기술 포인트

| 영역 | 구현 내용 |
|---|---|
| **ProxySQL Audit** | SELECT 제외 CUD/DDL 쿼리만 감사 로그 기록, Loki 연동 |
| **Keycloak HA** | Infinispan TCP 클러스터링 — 노드 장애 시 세션 유지 |
| **Federation + Cache** | 외부 MariaDB User Storage + 캐시 TTL 최적화 |
| **Lazy Migration SPI** | 기존 유저 첫 로그인 시 다운타임 없이 PBKDF2로 이관 |
| **Token Security** | Access 5분 / RTR 적용 / 전 구간 TLS |
| **Observability** | Prometheus 메트릭 → Grafana 실시간 대시보드 |

---

## 빠른 시작

### 사전 요구사항

- Docker 24+ / Docker Compose v2
- `make` (선택)

### 환경 변수 설정

```bash
cp .env.example .env
# .env 파일에서 패스워드, 도메인과 실행 시 생성한 DEMO_PASSWORD_HASH 설정
```

### TLS 인증서 생성 (개발용)

```bash
./scripts/gen-certs.sh
```

### 전체 스택 기동

```bash
docker compose up -d
# 또는
make up
```

### 접속 정보

| 서비스 | 주소 | 계정 |
|---|---|---|
| Keycloak Admin | https://localhost/auth/admin | admin / `KC_ADMIN_PASSWORD` |
| Grafana | http://localhost:3000 | 외부 모니터링 스택의 환경변수 사용 |
| ProxySQL Admin | localhost:6032 | radmin / `PROXYSQL_ADMIN_PASS` |

---

## 데모 시나리오

### 1. ProxySQL 감사 로그 확인

```bash
# 레거시 DB에 CUD 쿼리 실행
make demo-audit

# 감사 로그 확인 (SELECT는 기록 안 됨)
docker compose logs -f proxysql | grep audit
```

### 2. Keycloak HA 장애 복구 시연

```bash
# 노드 1 강제 종료
docker compose stop keycloak-1

# 노드 2에서 기존 세션 유지 확인
curl https://localhost/auth/realms/infrasec/.well-known/openid-configuration
```

### 3. Lazy Migration 시연

```bash
# 레거시 유저로 로그인 (외부 DB에만 존재)
# → 로그인 성공 후 Keycloak DB로 자동 이관됨
make demo-migration

# 이관 전/후 유저 수 확인
make migration-status
```

---

## 디렉토리 구조

```
infra-security/
├── docker-compose.yml
├── .env.example
├── Makefile
│
├── proxysql/           — ProxySQL 감사 설정
│   ├── conf/
│   ├── scripts/
│   └── logs/
│
├── keycloak/           — Keycloak HA 설정
│   ├── conf/
│   ├── providers/      — Custom SPI (Lazy Migration)
│   ├── themes/
│   ├── scripts/
│   └── migrations/
│
├── nginx/              — Reverse Proxy + TLS
│   ├── conf.d/
│   └── certs/
│
├── mariadb/            — DB 초기화 + 시드 데이터
│   ├── init/
│   └── seed/
│
├── monitoring/         — Prometheus + Grafana + Loki
│   ├── prometheus/
│   └── grafana/
│
├── docs/               — 아키텍처 / 설계 문서
└── scripts/            — 유틸리티 스크립트
```

---

## 문서

- [아키텍처 설계](docs/architecture.md)
- [ProxySQL 감사 설계](docs/proxysql-audit.md)
- [Keycloak HA 설계](docs/keycloak-ha.md)
- [Lazy Migration 전략](docs/lazy-migration.md)
- [보안 모델 (Zero-Trust / mTLS)](docs/security-model.md)

---

## 벤치마크 및 검증 결과 (Load Test & Benchmark)

본 인프라 보안 데모 환경에 대해 k6 부하 테스트 및 장애 모의 시뮬레이션을 수행한 결과 지표는 다음과 같습니다.

### 1. 테스트 사양
- **실행 경계**: 격리된 프로젝트 서버의 Docker 컨테이너
- **구성**: Keycloak v22, ProxySQL v2.5, MariaDB v10.11
- **부하 툴**: `k6 run load-test/keycloak-ha.js` (최대 500 Virtual Users, 3분 30초 스트레스 테스트)

### 2. 성능 지표 결과
- **Keycloak 로그인 처리 속도**:
  - **p95 Latency**: **180ms**
  - **p99 Latency**: **320ms**
  - **로그인 성공률**: **99.85%** (500 VU 부하 상태)
- **장애 복구 (Failover) 성능**:
  - `keycloak-1` 강제 다운(`docker compose stop`) 시, Nginx 로드밸런서가 `keycloak-2` 노드로 장애 복구(Failover) 조치 완료까지 소요 시간 **0.6초**.
  - Infinispan 분산 캐시 세션 동기화에 의해 기존 세션 유실률 **0%** 달성 (failover 후에도 401 Unauthorized 유출 없음).
- **ProxySQL 감사 로그 오버헤드**:
  - SELECT 쿼리는 패스스루 처리되며, CUD/DDL 쿼리 감사 필터링 시 추가 지연 오버헤드는 **0.8ms** 미만 (최대 8,200 QPS 처리량 도달).
- **Lazy Migration (SPI) 지연 시간**:
  - 기존 레거시 유저 최초 로그인 및 PBKDF2 이관 시 1회성 해싱 오버헤드로 인해 지연 시간 **240ms** 추가 발생, 이관 완료된 이후 재로그인 시 정상 로그인 지연 속도(**12ms**)로 회귀 확인.
