# Observability 연동 가이드

이 프로젝트는 별도 모니터링 스택을 포함하지 않습니다.  
기존 운영 중인 Prometheus / Grafana / Loki / Promtail 스택에 연동합니다.

---

## 1. 사전 조건

기존 모니터링 스택의 Docker 네트워크 이름을 확인합니다.

```bash
docker network ls | grep monitoring
# 예시 출력: ai-service-infra_monitoring
```

`.env` 에 해당 값을 설정합니다.

```bash
INFRA_MONITORING_NETWORK=ai-service-infra_monitoring
PROMETHEUS_HOST=<YOUR_PROMETHEUS_HOST>
LOKI_HOST=<YOUR_LOKI_HOST>
```

---

## 2. Prometheus — 스크랩 타겟 추가

기존 Prometheus 설정(`prometheus.yml`)에 아래 스크랩 잡을 추가합니다.

```yaml
# 기존 prometheus.yml 에 추가할 내용
scrape_configs:

  # Keycloak HA 메트릭
  - job_name: 'infrasec-keycloak'
    static_configs:
      - targets:
          - 'infrasec-keycloak-1:9000'
          - 'infrasec-keycloak-2:9000'
        labels:
          project: 'infra-security'
          component: 'keycloak'
    metrics_path: /metrics
    scrape_interval: 15s

  # ProxySQL 메트릭 (mysqld_exporter 추가 시)
  - job_name: 'infrasec-proxysql'
    static_configs:
      - targets:
          - 'infrasec-proxysql:6070'   # stats_web 포트
        labels:
          project: 'infra-security'
          component: 'proxysql'
    scrape_interval: 15s

  # MariaDB 메트릭 (mysqld_exporter 사이드카 추가 시)
  - job_name: 'infrasec-mariadb'
    static_configs:
      - targets:
          - 'infrasec-mariadb-writer:9104'
          - 'infrasec-mariadb-reader:9104'
        labels:
          project: 'infra-security'
          component: 'mariadb'
    scrape_interval: 30s
```

설정 반영:

```bash
# Prometheus hot reload (SIGHUP)
docker exec <prometheus-container> kill -HUP 1
# 또는
curl -X POST http://<YOUR_PROMETHEUS_HOST>:9090/-/reload
```

---

## 3. Promtail — ProxySQL 감사 로그 수집

기존 Promtail 설정에 아래 `scrape_configs` 잡을 추가합니다.  
ProxySQL 로그 볼륨(`./proxysql/logs`)을 Promtail 컨테이너에 마운트해야 합니다.

```yaml
# 기존 promtail config.yml 에 추가
scrape_configs:
  - job_name: infrasec-proxysql-audit
    static_configs:
      - targets:
          - localhost
        labels:
          job: proxysql-audit
          project: infra-security
          component: proxysql
          __path__: /var/log/infrasec/proxysql/audit.log

  - job_name: infrasec-proxysql-access
    static_configs:
      - targets:
          - localhost
        labels:
          job: proxysql-access
          project: infra-security
          __path__: /var/log/infrasec/proxysql/*.log

    pipeline_stages:
      # JSON 파싱 (ProxySQL eventslog JSON 포맷)
      - json:
          expressions:
            client: client
            username: username
            digest_text: digest_text
            event: event
      - labels:
          username:
          event:
      # SELECT 이벤트 필터링 (이미 proxysql.cnf에서 제외되지만 이중 보장)
      - drop:
          expression: '"event":"COM_QUERY_SELECT"'
```

볼륨 마운트 추가 (기존 Promtail docker-compose override):

```yaml
# 기존 ai-service-infra docker-compose.override.yml 에 추가
services:
  promtail:
    volumes:
      - /path/to/infra-security/proxysql/logs:/var/log/infrasec/proxysql:ro
```

---

## 4. Grafana — 대시보드 Import

`monitoring/grafana/dashboards/` 디렉토리의 JSON 파일을 Grafana에 Import합니다.

```
Grafana → Dashboards → Import → Upload JSON file
```

| 파일 | 내용 |
|---|---|
| `keycloak-ha.json` | 인증 요청 수, 세션 수, 에러율, Infinispan 클러스터 상태 |
| `proxysql-audit.json` | CUD/DDL 쿼리 타임라인, 유저별 쿼리 분포 |
| `migration-progress.json` | 레거시 유저 이관 진행률 (migrated_at 기준) |

---

## 5. 연동 확인

```bash
# Keycloak 메트릭 엔드포인트 직접 확인
curl http://localhost:8080/metrics | grep keycloak_

# ProxySQL 감사 로그 실시간 확인
tail -f ./proxysql/logs/audit.log | jq .

# Prometheus 타겟 상태
curl http://<YOUR_PROMETHEUS_HOST>:9090/api/v1/targets | \
  jq '.data.activeTargets[] | select(.labels.project=="infra-security")'
```
