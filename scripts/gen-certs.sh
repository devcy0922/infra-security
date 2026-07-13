#!/usr/bin/env bash
# =============================================================
# gen-certs.sh — 개발용 Self-Signed TLS 인증서 생성
# =============================================================
set -euo pipefail

CERT_DIR="$(cd "$(dirname "$0")/../nginx/certs" && pwd)"
mkdir -p "$CERT_DIR"

DOMAIN="${KC_HOSTNAME:-localhost}"
DAYS=825   # Apple 제한: 825일 이내

echo "[gen-certs] 도메인: $DOMAIN"
echo "[gen-certs] 출력 경로: $CERT_DIR"

openssl req -x509 -nodes -newkey rsa:4096 \
  -keyout  "$CERT_DIR/server.key" \
  -out     "$CERT_DIR/server.crt" \
  -days    "$DAYS" \
  -subj    "/C=KR/ST=Seoul/O=InfraSec/CN=$DOMAIN" \
  -addext  "subjectAltName=DNS:$DOMAIN,DNS:localhost,IP:127.0.0.1"

chmod 600 "$CERT_DIR/server.key"
chmod 644 "$CERT_DIR/server.crt"

echo "[gen-certs] 생성 완료"
openssl x509 -in "$CERT_DIR/server.crt" -noout -subject -dates
