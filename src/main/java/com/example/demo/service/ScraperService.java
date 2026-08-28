package com.example.demo.service;

import com.example.demo.model.Horse;
import com.example.demo.model.HorseList;
import com.example.demo.PojaGenerated;
import lombok.extern.slf4j.Slf4j;
import com.example.demo.file.bucket.BucketConf;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@PojaGenerated
@Service
@Slf4j
public class ScraperService {

    private static final String ALLOWED_DOMAIN = "https://www.geny.com/chevaux/";
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final BucketConf bucketConf;
    private final ObjectMapper objectMapper;

    public ScraperService(BucketConf bucketConf, ObjectMapper objectMapper) {
        this.bucketConf = bucketConf;
        this.objectMapper = objectMapper;
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
            HeadObjectResponse head = bucketConf.getS3Client().headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build());
            
            Instant lastModified = head.lastModified();
            if (lastModified != null && Instant.now().minus(12, ChronoUnit.HOURS).isBefore(lastModified)) {
                String cachedJson = bucketConf.getS3Client().getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .build()).asUtf8String();
                
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
            log.error("Missing WEBSCRAPING_API_KEY environment variable.");
            throw new RuntimeException("Server configuration error.");
        }

        String targetUrl = ALLOWED_DOMAIN + date;
        // Use WebScraping.AI with JS=true (required for rendering, costs 5 credits per call)
        String webScrapingUrl = "https://api.webscraping.ai/html?api_key=" + apiKey + 
                                "&js=true&url=" + java.net.URLEncoder.encode(targetUrl, java.nio.charset.StandardCharsets.UTF_8);
        
        try {
            // Use Jsoup to fetch and parse the HTML from WebScraping.AI
            Document doc = Jsoup.connect(webScrapingUrl)
                    .timeout(30000) // Increase timeout to 30s since JS rendering takes longer
                    .get();

            // Extract the horses using the CSS selector
            Elements horseElements = doc.select("div.font-semibold.text-gray-900");
            
            List<Horse> horses = new ArrayList<>();
            for (Element el : horseElements) {
                String name = el.text().trim();
                if (!name.isEmpty()) {
                    horses.add(new Horse(name));
                }
            }
            
            log.info("Successfully scraped {} horses from {}", horses.size(), targetUrl);
            HorseList result = new HorseList(horses);
            
            try {
                CacheWrapper wrapper = new CacheWrapper(currentCacheKey, result);
                String json = objectMapper.writeValueAsString(wrapper);
                bucketConf.getS3Client().putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .build(), RequestBody.fromString(json));
            } catch (Exception e) {
                log.warn("Failed to write cache to S3", e);
            }
            
            return result;
            
        } catch (IOException e) {
            log.error("Failed to connect or parse the URL: {}", targetUrl, e);
            throw new RuntimeException("Failed to fetch data from the target website.");
        }
    }
}
