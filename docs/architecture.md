# 아키텍처 설계

## 전체 구성 개요

```mermaid
graph TB
    subgraph Client["클라이언트 계층"]
        Browser["브라우저 / 앱"]
        API["API 클라이언트"]
    end

    subgraph Edge["엣지 계층 (Nginx)"]
        NX["Nginx\nTLS 종단 · 8443"]
    end

    subgraph Auth["인증 계층 — Keycloak HA"]
        KC1["keycloak-1\n:8080"]
        KC2["keycloak-2\n:8080"]
        IS[("Infinispan\nJGroups/TCP\n세션 동기화")]
        KC1 <-->|"분산 캐시 동기화"| IS
        KC2 <-->|"분산 캐시 동기화"| IS
    end

    subgraph SPI["Custom SPI (Lazy Migration)"]
        CSPI["CustomUserStorageProvider\nbcrypt 검증 → PBKDF2 이관"]
    end

    subgraph DB["데이터 계층"]
        PS["ProxySQL\n:6033"]
        MW["mariadb-writer\n:3306\n(레거시 앱 DB)"]
        MR["mariadb-reader\n:3307"]
        KDB["mariadb-kc\n(Keycloak 전용 DB)"]
        AL[/"감사 로그\nCUD/DDL only"/]
    end

    subgraph Obs["관찰 가능성 (외부 스택)"]
        Prom["Prometheus"]
        Graf["Grafana"]
        Loki["Loki"]
    end

    Browser -->|"HTTPS"| NX
    API -->|"HTTPS"| NX
    NX -->|"least_conn LB"| KC1
    NX -->|"least_conn LB"| KC2

    KC1 -->|"Federation 조회"| CSPI
    KC2 -->|"Federation 조회"| CSPI
    CSPI -->|"레거시 유저 읽기"| PS
    KC1 -->|"세션/토큰 저장"| KDB
    KC2 -->|"세션/토큰 저장"| KDB

    PS -->|"Write (HG 10)"| MW
    PS -->|"Read  (HG 20)"| MR
    PS -->|"CUD/DDL 필터"| AL

    KC1 -.->|"/metrics"| Prom
    KC2 -.->|"/metrics"| Prom
    AL  -.->|"Promtail"| Loki
    Prom --> Graf
    Loki --> Graf
```

---

## 네트워크 격리 구조

```mermaid
graph LR
    subgraph frontend["네트워크: frontend (브리지)"]
        NX2["nginx"]
        KC1b["keycloak-1"]
        KC2b["keycloak-2"]
        PS2["proxysql"]
    end

    subgraph backend["네트워크: backend (internal)"]
        PS3["proxysql"]
        MW2["mariadb-writer"]
        MR2["mariadb-reader"]
    end

    subgraph kc_cluster["네트워크: kc-cluster (internal)"]
        KC1c["keycloak-1"]
        KC2c["keycloak-2"]
        KDB2["mariadb-kc"]
    end

    subgraph infra_mon["네트워크: infra-monitoring (external)"]
        KC1d["keycloak-1"]
        KC2d["keycloak-2"]
        PS4["proxysql"]
    end

    Internet((인터넷)) --> NX2
```

> **설계 원칙**: `backend`, `kc-cluster` 네트워크는 `internal: true` → 외부 직접 접근 불가.  
> Keycloak과 ProxySQL은 `infra-monitoring` 외부 네트워크에도 참여해 Prometheus 스크랩을 허용한다.

---

## 요청 흐름 — 로그인 시퀀스 (Lazy Migration 포함)

```mermaid
sequenceDiagram
    autonumber
    actor User as 사용자
    participant Nginx
    participant KC as Keycloak (Active Node)
    participant SPI as CustomUserStorageProvider
    participant LegacyDB as MariaDB Writer (레거시)
    participant KCDB as MariaDB KC (KC 전용)

    User->>Nginx: POST /auth/realms/infrasec/login (HTTPS)
    Nginx->>KC: 리버스 프록시 (least_conn)

    KC->>KCDB: 내부 DB에서 유저 조회
    KCDB-->>KC: 없음 (미이관 유저)

    KC->>SPI: isValid(username, rawPassword)
    SPI->>LegacyDB: SELECT WHERE username=? AND migrated_at IS NULL
    LegacyDB-->>SPI: 유저 레코드 + bcrypt 해시 반환

    SPI->>SPI: BCrypt.checkpw(rawPassword, hash)

    alt 인증 성공
        SPI->>KCDB: addUser() + updateCredential(PBKDF2)
        SPI->>LegacyDB: UPDATE SET migrated_at = NOW()
        SPI-->>KC: true
        KC-->>Nginx: 토큰 발급 (Access 5분, Refresh 30분 + RTR)
        Nginx-->>User: 200 OK + JWT
    else 인증 실패
        SPI-->>KC: false
        KC-->>User: 401 Unauthorized
    end
```

---

## ProxySQL 쿼리 라우팅 흐름

```mermaid
flowchart TD
    Q["앱 쿼리 수신\n:6033"] --> R{"query_rules\n패턴 매칭"}

    R -->|"^SELECT"| HG20["HG 20\nmariadb-reader\nlog=0 감사 제외"]
    R -->|"^INSERT"| HG10A["HG 10\nmariadb-writer\nlog=1 감사 기록"]
    R -->|"^UPDATE"| HG10B["HG 10\nmariadb-writer\nlog=1 감사 기록"]
    R -->|"^DELETE"| HG10C["HG 10\nmariadb-writer\nlog=1 감사 기록"]
    R -->|"^CREATE/ALTER/DROP"| HG10D["HG 10\nmariadb-writer\nlog=1 감사 기록"]
    R -->|"^GRANT/REVOKE"| HG10E["HG 10\nmariadb-writer\nlog=1 감사 기록"]
    R -->|"기타"| HG10F["HG 10\nmariadb-writer\nlog=0"]

    HG10A & HG10B & HG10C & HG10D & HG10E --> AuditLog[/"audit.log\nJSON 포맷"/]
    AuditLog --> Loki2["Loki (Promtail)"]
    Loki2 --> Grafana2["Grafana\nProxySQL Audit 대시보드"]
```

---

## Infinispan 세션 동기화 구조

```mermaid
graph LR
    subgraph Node1["keycloak-1 (JVM)"]
        L1["Local Cache\n(realms, users)"]
        D1["Distributed Cache\n(sessions, clientSessions)"]
    end

    subgraph Node2["keycloak-2 (JVM)"]
        L2["Local Cache\n(realms, users)"]
        D2["Distributed Cache\n(sessions, clientSessions)"]
    end

    D1 <-->|"JGroups TCP :7800\n2 owners 복제"| D2

    Note1["owners=2\n→ 한 노드 장애 시\n세션 데이터 유실 없음"]
```

> **핵심**: `owners=2` 설정으로 양 노드에 세션 복제본 유지.  
> keycloak-1 장애 시 keycloak-2가 즉시 서비스 계속 가능.
