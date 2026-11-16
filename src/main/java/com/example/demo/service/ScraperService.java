package com.example.demo.service;

import com.example.demo.PojaGenerated;
import com.example.demo.file.bucket.BucketConf;
import com.example.demo.model.CachePruneResult;
import com.example.demo.model.Horse;
import com.example.demo.model.HorseList;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

@PojaGenerated
@Service
@Slf4j
public class ScraperService {

  private static final String ALLOWED_DOMAIN = "https://www.geny.com/chevaux/";
  private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

  /** Injectable HTTP fetcher — real impl uses Jsoup; tests supply a mock. */
  @FunctionalInterface
  public interface HtmlFetcher {
    Document fetch(String url) throws IOException;
  }

  private final BucketConf bucketConf;
  private final ObjectMapper objectMapper;
  private final HtmlFetcher htmlFetcher;

  /** Production constructor — uses real Jsoup. */
  @org.springframework.beans.factory.annotation.Autowired
  public ScraperService(BucketConf bucketConf, ObjectMapper objectMapper) {
    this(bucketConf, objectMapper, url -> Jsoup.connect(url).maxBodySize(0).timeout(60000).get());
  }

  /** Test constructor — accepts a mock fetcher. */
  public ScraperService(BucketConf bucketConf, ObjectMapper objectMapper, HtmlFetcher fetcher) {
    this.bucketConf = bucketConf;
    this.objectMapper = objectMapper;
    this.htmlFetcher = fetcher;
  }

  public static class CacheWrapper {
    public String cacheKey;
    public HorseList horseList;

    public CacheWrapper() {}

    public CacheWrapper(String cacheKey, HorseList horseList) {
      this.cacheKey = cacheKey;
      this.horseList = horseList;
    }
  }

  public HorseList scrapeHorses(String date, String rnd) {
    if (date == null || !DATE_PATTERN.matcher(date).matches()) {
      log.error("Invalid date parameter provided: {}", date);
      throw new IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD.");
    }

    String currentCacheKey = date + "_" + (rnd != null ? rnd : "");
    String bucketName = bucketConf.getBucketName();
    String s3Key = "scrape_" + currentCacheKey + ".json";

    try {
      HeadObjectResponse head =
          bucketConf
              .getS3Client()
              .headObject(HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build());

      Instant lastModified = head.lastModified();
      if (lastModified != null
          && Instant.now().minus(12, ChronoUnit.HOURS).isBefore(lastModified)) {
        String cachedJson =
            bucketConf
                .getS3Client()
                .getObjectAsBytes(GetObjectRequest.builder().bucket(bucketName).key(s3Key).build())
                .asUtf8String();

        CacheWrapper wrapper = objectMapper.readValue(cachedJson, CacheWrapper.class);
        if (currentCacheKey.equals(wrapper.cacheKey)) {
          log.info("Returning S3 cached horses for key: {}", currentCacheKey);
          return wrapper.horseList;
        }
      }
    } catch (NoSuchKeyException e) {
      log.info("No cache found in S3");
    } catch (Exception e) {
      log.warn("Failed to read/check cache from S3", e);
    }

    String apiKey = System.getenv("WEBSCRAPING_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      apiKey = System.getProperty("webscraping.api.key");
    }
    if (apiKey == null || apiKey.isEmpty()) {
      log.error("Missing WEBSCRAPING_API_KEY environment variable.");
      throw new RuntimeException("Server configuration error.");
    }

    String targetUrl = ALLOWED_DOMAIN + date;
    // Use WebScraping.AI: js=true + wait_for ensures DOM is hydrated; server-side timeout=25s,
    // Jsoup client timeout=60s to allow headless rendering of the full 1.8MB+ page
    String webScrapingUrl =
        "https://api.webscraping.ai/html?api_key="
            + apiKey
            + "&proxy=residential&js=true"
            + "&timeout=25000"
            + "&url="
            + java.net.URLEncoder.encode(targetUrl, java.nio.charset.StandardCharsets.UTF_8);

    try {
      Document doc = htmlFetcher.fetch(webScrapingUrl);

      List<Horse> horses = new ArrayList<>();

      // 1. Try standard DOM selector if elements were rendered into the DOM
      Elements horseElements = doc.select("div.font-semibold.text-gray-900");
      for (Element el : horseElements) {
        String name = el.text().trim();
        if (!name.isEmpty()) {
          horses.add(new Horse(name));
        }
      }

      // 2. Fallback: Parse Next.js SSR hydration payload directly from HTML
      if (horses.isEmpty()) {
        java.util.regex.Pattern p =
            java.util.regex.Pattern.compile(
                "\\\\\"cheval\\\\\":\\{\\\\\"id\\\\\":\\d+,\\\\\"nom\\\\\":\\\\\"([^\\\\\"]+)\\\\\"");
        java.util.regex.Matcher m = p.matcher(doc.html());
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (m.find()) {
          String name = m.group(1).trim();
          if (!name.isEmpty() && seen.add(name)) {
            horses.add(new Horse(name));
          }
        }
      }

      log.info("Successfully scraped {} horses from {}", horses.size(), targetUrl);
      HorseList result = new HorseList(horses);

      if (!horses.isEmpty()) {
        try {
          CacheWrapper wrapper = new CacheWrapper(currentCacheKey, result);
          String json = objectMapper.writeValueAsString(wrapper);
          bucketConf
              .getS3Client()
              .putObject(
                  PutObjectRequest.builder().bucket(bucketName).key(s3Key).build(),
                  RequestBody.fromString(json));
        } catch (Exception e) {
          log.warn("Failed to write cache to S3", e);
        }
      }

      return result;

    } catch (IOException e) {
      log.error("Failed to connect or parse the URL: {}", targetUrl, e);
      throw new RuntimeException("Failed to fetch data from the target website.");
    }
  }

  public CachePruneResult pruneAndOptionallyRescrape(boolean dryRun, boolean reScrape) {
    String bucketName = bucketConf.getBucketName();
    List<String> emptyKeys = new ArrayList<>();
    List<String> duplicateKeys = new ArrayList<>();
    List<String> reScrapedDates = new ArrayList<>();
    int deletedCount = 0;
    int totalScanned = 0;

    Pattern cacheKeyPattern = Pattern.compile("^scrape_(\\d{4}-\\d{2}-\\d{2})_(.*)\\.json$");
    Map<String, List<S3Object>> objectsByDate = new HashMap<>();

    try {
      ListObjectsV2Request listReq =
          ListObjectsV2Request.builder().bucket(bucketName).prefix("scrape_").build();
      ListObjectsV2Response listRes = bucketConf.getS3Client().listObjectsV2(listReq);

      List<S3Object> contents = listRes.contents();
      totalScanned = contents != null ? contents.size() : 0;

      if (contents != null) {
        for (S3Object obj : contents) {
          String key = obj.key();
          Matcher matcher = cacheKeyPattern.matcher(key);
          if (matcher.matches()) {
            String date = matcher.group(1);
            objectsByDate.computeIfAbsent(date, d -> new ArrayList<>()).add(obj);
          }

          boolean isEmpty = false;
          if (obj.size() != null && obj.size() < 100) {
            isEmpty = true;
          } else {
            try {
              String body =
                  bucketConf
                      .getS3Client()
                      .getObjectAsBytes(
                          GetObjectRequest.builder().bucket(bucketName).key(key).build())
                      .asUtf8String();
              CacheWrapper wrapper = objectMapper.readValue(body, CacheWrapper.class);
              if (wrapper.horseList == null
                  || wrapper.horseList.getHorses() == null
                  || wrapper.horseList.getHorses().isEmpty()) {
                isEmpty = true;
              }
            } catch (Exception e) {
              log.warn("Failed reading or parsing cache object {}: assuming invalid/empty", key, e);
              isEmpty = true;
            }
          }

          if (isEmpty) {
            emptyKeys.add(key);
          }
        }

        // Group-level duplicates: keep only the newest non-empty file per date
        for (Map.Entry<String, List<S3Object>> entry : objectsByDate.entrySet()) {
          List<S3Object> dateObjects = entry.getValue();
          if (dateObjects.size() > 1) {
            dateObjects.sort((a, b) -> b.lastModified().compareTo(a.lastModified()));
            // Keep the first (newest), flag remaining as duplicates
            for (int i = 1; i < dateObjects.size(); i++) {
              String dupKey = dateObjects.get(i).key();
              if (!emptyKeys.contains(dupKey)) {
                duplicateKeys.add(dupKey);
              }
            }
          }
        }

        // Combine keys to delete
        List<String> keysToDelete = new ArrayList<>(emptyKeys);
        for (String dupKey : duplicateKeys) {
          if (!keysToDelete.contains(dupKey)) {
            keysToDelete.add(dupKey);
          }
        }

        if (!dryRun) {
          for (String key : keysToDelete) {
            try {
              bucketConf
                  .getS3Client()
                  .deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
              deletedCount++;
              log.info("Deleted S3 cache key: {}", key);
            } catch (Exception e) {
              log.error("Failed to delete S3 key: {}", key, e);
            }
          }

          if (reScrape) {
            java.util.Set<String> datesToRescrape = new java.util.LinkedHashSet<>();
            for (String key : emptyKeys) {
              Matcher m = cacheKeyPattern.matcher(key);
              if (m.matches()) {
                datesToRescrape.add(m.group(1));
              }
            }

            for (String d : datesToRescrape) {
              try {
                log.info("Triggering fresh scrape for date: {}", d);
                HorseList hl = scrapeHorses(d, null);
                if (hl != null && hl.getHorses() != null && !hl.getHorses().isEmpty()) {
                  reScrapedDates.add(d);
                }
              } catch (Exception e) {
                log.error("Failed to re-scrape for date: {}", d, e);
              }
            }
          }
        }
      }
    } catch (Exception e) {
      log.error("Failed during S3 cache prune", e);
      throw new RuntimeException("Error executing cache prune: " + e.getMessage(), e);
    }

    return CachePruneResult.builder()
        .dryRun(dryRun)
        .reScraped(reScrape)
        .totalObjectsScanned(totalScanned)
        .emptyCachesDetected(emptyKeys.size())
        .duplicatesDetected(duplicateKeys.size())
        .deletedCount(deletedCount)
        .emptyKeys(emptyKeys)
        .duplicateKeys(duplicateKeys)
        .reScrapedDates(reScrapedDates)
        .build();
  }
}
