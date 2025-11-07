package com.welhire.welhire_subscription_service.service;

import com.welhire.welhire_subscription_service.entity.CountryEntity;
import com.welhire.welhire_subscription_service.repository.CountryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CountryService {

    private final CountryRepository countryRepository;

    // Lightweight read DTO
    public record CountryView(String code, String name, String currency) { }

    @Transactional(readOnly = true)
    public List<CountryView> listAllEnabled() {
        return countryRepository.findAllByIsEnabledTrueOrderByNameAsc()
                .stream()
                .map(c -> new CountryView(c.getIsoCode(), c.getName(), c.getCurrency()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CountryView getByCode(String isoCode) {
        CountryEntity c = countryRepository.findByIsoCodeIgnoreCaseAndIsEnabledTrue(isoCode)
                .orElseThrow(() -> new IllegalArgumentException("Country not found or disabled: " + isoCode));
        return new CountryView(c.getIsoCode(), c.getName(), c.getCurrency());
    }
}
