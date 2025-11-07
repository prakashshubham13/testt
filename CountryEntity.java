package com.welhire.welhire_subscription_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@Entity
@Table(
    name = "country",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_country_iso_code", columnNames = "iso_code"),
        @UniqueConstraint(name = "uk_country_name", columnNames = "name")
    },
    indexes = {
        @Index(name = "idx_country_name", columnList = "name"),
        @Index(name = "idx_country_iso_code", columnList = "iso_code")
    }
)
public class CountryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "country_id", nullable = false)
    private Long countryId;

    @Column(name = "iso_code", nullable = false, length = 5)
    private String isoCode;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;
}
