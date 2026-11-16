#!/usr/bin/env bash
set -euo pipefail

# Load environment variables if .env exists
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/.env" ]; then
  set -a
  source "$SCRIPT_DIR/.env"
  set +a
fi

BASE_URL="${APP_BASE_URL:-https://efxetrrumnay7mxbyjum5pd6ha0sybzf.lambda-url.eu-west-3.on.aws/horses}"
TARGET_URL="${BASE_URL%/}/maintenance/prune-cache"

DRY_RUN="${1:-true}"
RE_SCRAPE="${2:-false}"

echo "==> Triggering S3 cache prune & maintenance endpoint"
echo "==> URL: ${TARGET_URL}"
echo "==> Parameters: dryRun=${DRY_RUN}, reScrape=${RE_SCRAPE}"

RESPONSE=$(curl -s -X POST "${TARGET_URL}?dryRun=${DRY_RUN}&reScrape=${RE_SCRAPE}" \
  -H "Accept: application/json")

if command -v jq >/dev/null 2>&1; then
  echo "$RESPONSE" | jq .
else
  echo "$RESPONSE"
fi
