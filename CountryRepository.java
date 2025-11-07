package com.welhire.welhire_subscription_service.repository;

import com.welhire.welhire_subscription_service.entity.CountryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CountryRepository extends JpaRepository<CountryEntity, Long> {

    List<CountryEntity> findAllByIsEnabledTrueOrderByNameAsc();

    Optional<CountryEntity> findByIsoCodeIgnoreCaseAndIsEnabledTrue(String isoCode);
}
