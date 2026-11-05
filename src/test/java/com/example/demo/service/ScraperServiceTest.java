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
  private ScraperService scraperService;

  @BeforeEach
  void setUp() {
    bucketConf = mock(BucketConf.class);
    s3Client = mock(S3Client.class);
    when(bucketConf.getBucketName()).thenReturn("mocked-s3-bucket");
    when(bucketConf.getS3Client()).thenReturn(s3Client);
    objectMapper = new ObjectMapper();
    scraperService = new ScraperService(bucketConf, objectMapper);
  }

  @Test
  void scrape_horses_live_with_mocked_s3() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().message("Key not found").build());
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());

    String targetDate = "2026-11-05";
    HorseList horseList = scraperService.scrapeHorses(targetDate, null);

    assertNotNull(horseList);
    List<Horse> horses = horseList.getHorses();
    assertNotNull(horses);
    assertFalse(horses.isEmpty(), "Horses list should not be empty");
    assertTrue(horses.size() > 100, "Should have parsed multiple horses from the page");

    // Verify S3 headObject was checked
    ArgumentCaptor<HeadObjectRequest> headCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
    verify(s3Client).headObject(headCaptor.capture());
    assertEquals("mocked-s3-bucket", headCaptor.getValue().bucket());
    assertEquals("scrape_2026-11-05_.json", headCaptor.getValue().key());

    // Verify S3 putObject was called to cache the result
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

    HorseList result = scraperService.scrapeHorses(targetDate, null);

    assertNotNull(result);
    assertEquals(2, result.getHorses().size());
    assertEquals("Cached Horse 1", result.getHorses().get(0).getName());
    assertEquals("Cached Horse 2", result.getHorses().get(1).getName());

    // Verify S3 putObject was never called since data was already cached
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void scrape_horses_throws_for_invalid_date() {
    assertThrows(
        IllegalArgumentException.class, () -> scraperService.scrapeHorses("invalid-date", null));
    assertThrows(IllegalArgumentException.class, () -> scraperService.scrapeHorses(null, null));
  }
}
