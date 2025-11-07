package com.welhire.welhire_subscription_service.controller;

import com.welhire.welhire_subscription_service.service.CountryService;
import com.welhire.welhire_subscription_service.service.CountryService.CountryView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.List;

/**
 * Public read-only endpoints:
 *  - GET /api/v1/public/getCountry            -> list all enabled countries
 *  - GET /api/v1/public/getCountry/{isoCode}  -> get one by ISO code (2-3 letters)
 */
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class CountryController {

    private final CountryService countryService;

    @GetMapping("/getCountry")
    public ResponseEntity<List<CountryView>> getCountries() {
        return ResponseEntity.ok(countryService.listAllEnabled());
    }

    @GetMapping("/getCountry/{isoCode}")
    public ResponseEntity<CountryView> getCountryByCode(@PathVariable String isoCode) {
        try {
            return ResponseEntity.ok(countryService.getByCode(isoCode));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(NOT_FOUND, ex.getMessage());
        }
    }
}
