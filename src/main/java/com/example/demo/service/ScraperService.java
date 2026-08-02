package com.example.demo.service;

import com.example.demo.model.Horse;
import com.example.demo.model.HorseList;
import com.example.demo.PojaGenerated;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@PojaGenerated
@Service
@Slf4j
public class ScraperService {

    private static final String ALLOWED_DOMAIN = "https://www.geny.com/chevaux/";
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public HorseList scrapeHorses(String date) {
        if (date == null || !DATE_PATTERN.matcher(date).matches()) {
            log.error("Invalid date parameter provided: {}", date);
            throw new IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD.");
        }

        String apiKey = System.getenv("WEBSCRAPING_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("Missing WEBSCRAPING_API_KEY environment variable.");
            throw new RuntimeException("Server configuration error.");
        }

        String targetUrl = ALLOWED_DOMAIN + date;
        // Use WebScraping.AI with JS=true to bypass AWS IP blocks and execute JS rendering
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
            return new HorseList(horses);
            
        } catch (IOException e) {
            log.error("Failed to connect or parse the URL: {}", targetUrl, e);
            throw new RuntimeException("Failed to fetch data from the target website.");
        }
    }
}
