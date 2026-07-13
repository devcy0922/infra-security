#!/usr/bin/env bash
# =============================================================
# migration-status.sh — Lazy Migration 진행 현황 확인
# 실행: ./scripts/migration-status.sh
# =============================================================
set -euo pipefail

source "$(dirname "$0")/../.env" 2>/dev/null || true

HOST="${PROXYSQL_HOST:-127.0.0.1}"
PORT=6033
USER="${MARIADB_USER:-appuser}"
PASS="${MARIADB_PASSWORD:-}"
DB="${MARIADB_DATABASE:-appdb}"

MYSQL="mysql -h $HOST -P $PORT -u $USER -p$PASS $DB"

echo "========================================"
echo " Lazy Migration 진행 현황"
echo "========================================"

$MYSQL --table -e "
SELECT
  COUNT(*)                                       AS 전체_유저수,
  SUM(CASE WHEN migrated_at IS NOT NULL THEN 1 ELSE 0 END) AS KC_이관_완료,
  SUM(CASE WHEN migrated_at IS NULL     THEN 1 ELSE 0 END) AS 레거시_잔존,
  ROUND(
    SUM(CASE WHEN migrated_at IS NOT NULL THEN 1 ELSE 0 END)
    / COUNT(*) * 100, 1
  )                                              AS 이관율_pct
FROM users
WHERE is_active = 1;
"

echo ""
echo "--- 최근 이관된 유저 (최대 10명) ---"
$MYSQL --table -e "
SELECT username, email, department, migrated_at
FROM users
WHERE migrated_at IS NOT NULL
ORDER BY migrated_at DESC
LIMIT 10;
"

echo ""
echo "--- 미이관 잔존 유저 (최대 10명) ---"
$MYSQL --table -e "
SELECT username, email, department, last_login
FROM users
WHERE migrated_at IS NULL AND is_active = 1
ORDER BY last_login DESC
LIMIT 10;
"
