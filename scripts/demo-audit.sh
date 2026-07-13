#!/usr/bin/env bash
# =============================================================
# demo-audit.sh — ProxySQL 감사 로그 데모
# 실행: ./scripts/demo-audit.sh
# =============================================================
set -euo pipefail

source "$(dirname "$0")/../.env" 2>/dev/null || true

HOST="${PROXYSQL_HOST:-127.0.0.1}"
PORT=6033
USER="${MARIADB_USER:-appuser}"
PASS="${MARIADB_PASSWORD:-}"
DB="${MARIADB_DATABASE:-appdb}"

MYSQL="mysql -h $HOST -P $PORT -u $USER -p$PASS $DB --batch --silent"

echo "========================================"
echo " ProxySQL 감사 로그 데모"
echo " 대상: $HOST:$PORT / DB: $DB"
echo "========================================"

echo ""
echo "[1/4] SELECT — 감사 로그에 기록되지 않아야 함"
$MYSQL -e "SELECT id, username, email FROM users LIMIT 5;"

echo ""
echo "[2/4] INSERT — 감사 로그에 기록됨"
$MYSQL -e "
  INSERT INTO orders (order_no, user_id, status, total_amount)
  VALUES ('DEMO-AUDIT-001', 6, 'pending', 99000.00);
"
echo "  → INSERT 실행 완료"

echo ""
echo "[3/4] UPDATE — 감사 로그에 기록됨"
$MYSQL -e "
  UPDATE orders SET status = 'processing', note = 'audit demo'
  WHERE order_no = 'DEMO-AUDIT-001';
"
echo "  → UPDATE 실행 완료"

echo ""
echo "[4/4] DELETE — 감사 로그에 기록됨"
$MYSQL -e "
  DELETE FROM orders WHERE order_no = 'DEMO-AUDIT-001';
"
echo "  → DELETE 실행 완료"

echo ""
echo "========================================"
echo " 감사 로그 확인 (마지막 10줄)"
echo "========================================"
LOG_FILE="$(dirname "$0")/../proxysql/logs/audit.log"
if [ -f "$LOG_FILE" ]; then
  tail -n 10 "$LOG_FILE" | python3 -m json.tool 2>/dev/null || tail -n 10 "$LOG_FILE"
else
  echo "  로그 파일 없음: $LOG_FILE"
  echo "  docker compose logs proxysql 로 확인하세요."
fi

echo ""
echo "[DONE] SELECT 쿼리는 감사 로그에 기록되지 않고,"
echo "       INSERT / UPDATE / DELETE 만 기록됩니다."
