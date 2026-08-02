package com.example.demo.endpoint.rest.controller;

import com.example.demo.model.HorseList;
import com.example.demo.service.ScraperService;
import com.example.demo.PojaGenerated;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@PojaGenerated
@RestController
@RequestMapping("/horses")
@RequiredArgsConstructor
public class ScraperController {

    private final ScraperService scraperService;

    @GetMapping(value = "/{date}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<HorseList> getHorsesByDate(@PathVariable String date) {
        try {
            HorseList horses = scraperService.scrapeHorses(date);
            return ResponseEntity.ok(horses);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
