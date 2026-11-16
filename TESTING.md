# Horse Scraper Endpoint Testing

## Overview
This service scrapes horse race data for a specified date from Geny (`https://www.geny.com/chevaux/<YYYY-MM-DD>`) via WebScraping.AI and returns the result in XML format.

- **Deployed URL**: `https://efxetrrumnay7mxbyjum5pd6ha0sybzf.lambda-url.eu-west-3.on.aws/horses/{date}`
- **Format**: `YYYY-MM-DD` (e.g. `2026-11-16`)

---

## Configuration

1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Set your WebScraping.AI API key inside `.env`:
   ```bash
   WEBSCRAPING_AI_API_KEY=your_api_key_here
   APP_BASE_URL=https://efxetrrumnay7mxbyjum5pd6ha0sybzf.lambda-url.eu-west-3.on.aws/horses
   ```
   *(Note: `.env` is ignored by git and will never be committed).*

---

## How to Test

### Option 1: Automated Script (Recommended)
Run the test script with an optional date parameter (defaults to today):
```bash
# Test with today's date
./test-scraper.sh

# Test with a specific date
./test-scraper.sh 2026-11-16
```

### Option 2: Direct cURL
```bash
curl -i "https://efxetrrumnay7mxbyjum5pd6ha0sybzf.lambda-url.eu-west-3.on.aws/horses/2026-11-16"
```

Expected output:
- HTTP `200 OK`
- XML list containing `<horse>` elements:
  ```xml
  <horses>
      <horse>
          <name>HORSE_NAME</name>
      </horse>
      ...
  </horses>
  ```

---

## S3 Cache Pruning & Maintenance

To prune empty or duplicate S3 cache entries without needing local AWS IAM credentials:

### 1. Dry Run (Preview Only)
```bash
./prune-cache.sh true false
```
Returns a JSON summary of empty cache files and duplicates found in S3 without deleting anything.

### 2. Prune Only (Delete Empty & Duplicates)
```bash
./prune-cache.sh false false
```

### 3. Prune and Re-Scrape (Replace Empty Files with Fresh Scrapes)
```bash
./prune-cache.sh false true
```
This deletes empty caches and automatically triggers a fresh scrape for all affected dates using WebScraping.AI, writing the valid results back to S3.

