#!/usr/bin/env bash
set -euo pipefail

# Load environment variables if .env exists
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/.env" ]; then
  # export variables safely without overriding existing exports
  set -a
  source "$SCRIPT_DIR/.env"
  set +a
fi

DATE="${1:-$(date +%Y-%m-%d)}"
BASE_URL="${APP_BASE_URL:-https://efxetrrumnay7mxbyjum5pd6ha0sybzf.lambda-url.eu-west-3.on.aws/horses}"
TARGET_URL="${BASE_URL%/}/${DATE}"

echo "==> Testing horse scraper endpoint for date: ${DATE}"
echo "==> Request URL: ${TARGET_URL}"

# Temporary file for HTTP response
RESP_FILE=$(mktemp)
trap 'rm -f "$RESP_FILE"' EXIT

HTTP_STATUS=$(curl -s -w "%{http_code}" -o "$RESP_FILE" "$TARGET_URL")

echo "==> HTTP Status: ${HTTP_STATUS}"

if [ "$HTTP_STATUS" -eq 200 ]; then
  TOTAL_HORSES=$(grep -o '<horse>' "$RESP_FILE" | wc -l || true)
  echo "==> Success! Extracted ${TOTAL_HORSES} horses."
  echo "==> Sample XML output (first 25 lines):"
  head -n 25 "$RESP_FILE"
else
  echo "==> Error: Request failed with status ${HTTP_STATUS}"
  cat "$RESP_FILE"
  echo ""
  exit 1
fi
