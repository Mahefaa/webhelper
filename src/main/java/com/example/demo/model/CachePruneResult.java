package com.example.demo.model;

import com.example.demo.PojaGenerated;
import java.io.Serializable;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@PojaGenerated
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachePruneResult implements Serializable {
  private boolean dryRun;
  private boolean reScraped;
  private int totalObjectsScanned;
  private int emptyCachesDetected;
  private int duplicatesDetected;
  private int deletedCount;
  private List<String> emptyKeys;
  private List<String> duplicateKeys;
  private List<String> reScrapedDates;
}
