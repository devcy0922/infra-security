.PHONY: help up down restart logs ps build seed status clean

ifneq (,$(wildcard .env))
include .env
export
endif

# =============================================================
# infra-security Makefile
# =============================================================

help: ## 사용 가능한 명령어 목록
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

up: ## 전체 스택 기동
	@echo "→ 스택 기동 중..."
	docker compose up -d
	@echo "→ 상태 확인..."
	docker compose ps

down: ## 전체 스택 종료
	docker compose down

restart: ## 전체 재시작
	docker compose restart

logs: ## 전체 로그 (실시간)
	docker compose logs -f --tail=100

ps: ## 컨테이너 상태
	docker compose ps

# ── 초기화 ──────────────────────────────────────────────

certs: ## TLS 인증서 생성 (self-signed)
	@chmod +x scripts/gen-certs.sh
	@scripts/gen-certs.sh

seed: ## 시드 데이터 적재
	@test -n "$${DEMO_PASSWORD_HASH}" || (echo "DEMO_PASSWORD_HASH를 설정하세요." >&2; exit 1)
	@echo "→ Writer DB에 시드 데이터 적재..."
	sed "s|__DEMO_PASSWORD_HASH__|$${DEMO_PASSWORD_HASH}|g" mariadb/seed/02-seed.sql | \
	docker exec -i infrasec-mariadb-writer \
	  mariadb -u root -p$${MARIADB_ROOT_PASSWORD} appdb
	@echo "→ 완료"

# ── 데모 ─────────────────────────────────────────────────

demo-audit: ## ProxySQL 감사 로그 데모 실행
	@chmod +x scripts/demo-audit.sh
	@scripts/demo-audit.sh

demo-ha: ## Keycloak HA 장애 복구 시연 (노드 1 종료 → 세션 유지 확인)
	@echo "→ keycloak-1 강제 종료..."
	docker compose stop keycloak-1
	@echo "→ 10초 대기..."
	sleep 10
	@echo "→ keycloak-2 헬스체크..."
	curl -sf http://localhost:8080/health/ready && echo "  [OK] keycloak-2 정상" || echo "  [FAIL]"
	@echo "→ keycloak-1 복구..."
	docker compose start keycloak-1

migration-status: ## Lazy Migration 진행 현황 확인
	@chmod +x scripts/migration-status.sh
	@scripts/migration-status.sh

# ── 관리 ─────────────────────────────────────────────────

audit-log: ## ProxySQL 감사 로그 실시간 확인
	tail -f proxysql/logs/audit.log 2>/dev/null | python3 -m json.tool || \
	  docker compose logs -f proxysql

kc-admin-token: ## Keycloak admin-cli 토큰 발급 (테스트용)
	@curl -sf -X POST \
	  "http://localhost:8080/auth/realms/master/protocol/openid-connect/token" \
	  -d "client_id=admin-cli&username=$${KC_ADMIN}&password=$${KC_ADMIN_PASSWORD}&grant_type=password" \
	  | python3 -m json.tool

proxysql-stats: ## ProxySQL Admin 쿼리 통계
	mysql -h 127.0.0.1 -P 6032 -u$${PROXYSQL_ADMIN_USER} -p$${PROXYSQL_ADMIN_PASS} \
	  -e "SELECT username, schemaname, digest_text, count_star, sum_time FROM stats_mysql_query_digest ORDER BY count_star DESC LIMIT 20;"

clean: ## 볼륨 포함 전체 삭제 (데이터 초기화)
	@echo "⚠️  모든 데이터가 삭제됩니다. 계속하려면 엔터를 누르세요 (Ctrl+C 취소)"
	@read confirm
	docker compose down -v
	rm -f nginx/certs/server.crt nginx/certs/server.key
