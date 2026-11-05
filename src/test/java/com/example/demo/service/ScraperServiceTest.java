package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.file.bucket.BucketConf;
import com.example.demo.model.Horse;
import com.example.demo.model.HorseList;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class ScraperServiceTest {

  private BucketConf bucketConf;
  private S3Client s3Client;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    bucketConf = mock(BucketConf.class);
    s3Client = mock(S3Client.class);
    when(bucketConf.getBucketName()).thenReturn("mocked-s3-bucket");
    when(bucketConf.getS3Client()).thenReturn(s3Client);
    objectMapper = new ObjectMapper();
  }

  /** Builds a minimal HTML page containing N horse elements. */
  private static Document buildFakeHorsePage(List<String> names) {
    StringBuilder html = new StringBuilder("<html><body>");
    for (String name : names) {
      html.append("<div class=\"font-semibold text-gray-900\">").append(name).append("</div>");
    }
    html.append("</body></html>");
    return Jsoup.parse(html.toString());
  }

  @Test
  void scrape_horses_with_mocked_s3_and_mocked_fetcher() throws Exception {
    // Arrange: S3 cache miss
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().message("Key not found").build());
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());

    // Build a fake page with 150 horses
    List<String> fakeNames =
        java.util.stream.IntStream.rangeClosed(1, 150)
            .mapToObj(i -> "Horse_" + i)
            .collect(java.util.stream.Collectors.toList());
    Document fakeDoc = buildFakeHorsePage(fakeNames);

    // Provide a dummy key so the service doesn't throw before calling the fetcher
    System.setProperty("webscraping.api.key", "test-key");
    ScraperService scraperService = new ScraperService(bucketConf, objectMapper, url -> fakeDoc);

    // Act
    String targetDate = "2026-11-05";
    HorseList horseList = scraperService.scrapeHorses(targetDate, null);

    // Assert result
    assertNotNull(horseList);
    List<Horse> horses = horseList.getHorses();
    assertNotNull(horses);
    assertFalse(horses.isEmpty(), "Horses list should not be empty");
    assertTrue(horses.size() > 100, "Should have parsed multiple horses from the page");
    assertEquals("Horse_1", horses.get(0).getName());

    // Assert S3 cache check
    ArgumentCaptor<HeadObjectRequest> headCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
    verify(s3Client).headObject(headCaptor.capture());
    assertEquals("mocked-s3-bucket", headCaptor.getValue().bucket());
    assertEquals("scrape_2026-11-05_.json", headCaptor.getValue().key());

    // Assert S3 cache write
    ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
    assertEquals("mocked-s3-bucket", putCaptor.getValue().bucket());
    assertEquals("scrape_2026-11-05_.json", putCaptor.getValue().key());
  }

  @Test
  void scrape_horses_returns_from_s3_cache_when_available() throws Exception {
    String targetDate = "2026-11-05";
    String cacheKey = targetDate + "_";

    HeadObjectResponse headResponse =
        HeadObjectResponse.builder().lastModified(Instant.now()).build();
    when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

    HorseList cachedHorses =
        new HorseList(List.of(new Horse("Cached Horse 1"), new Horse("Cached Horse 2")));
    ScraperService.CacheWrapper wrapper = new ScraperService.CacheWrapper(cacheKey, cachedHorses);
    String cachedJson = objectMapper.writeValueAsString(wrapper);

    ResponseBytes<GetObjectResponse> responseBytes =
        ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), cachedJson.getBytes());
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

    ScraperService scraperService = new ScraperService(bucketConf, objectMapper);

    HorseList result = scraperService.scrapeHorses(targetDate, null);

    assertNotNull(result);
    assertEquals(2, result.getHorses().size());
    assertEquals("Cached Horse 1", result.getHorses().get(0).getName());
    assertEquals("Cached Horse 2", result.getHorses().get(1).getName());

    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void scrape_horses_throws_for_invalid_date() {
    ScraperService scraperService = new ScraperService(bucketConf, objectMapper);
    assertThrows(
        IllegalArgumentException.class, () -> scraperService.scrapeHorses("invalid-date", null));
    assertThrows(IllegalArgumentException.class, () -> scraperService.scrapeHorses(null, null));
  }
}
