package com.welhire.welhire_subscription_service.repository;

import com.welhire.welhire_subscription_service.entity.SubscriptionMasterPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing SubscriptionMasterPlan entities.
 * Provides efficient lookups, filters, and eager loading helpers.
 */
public interface SubscriptionMasterPlanRepository extends JpaRepository<SubscriptionMasterPlan, Long> {

    /**
     * Find plan by its unique external plan code (Zoho plan_code).
     */
    Optional<SubscriptionMasterPlan> findByExternalPlanCode(String externalPlanCode);

    List<SubscriptionMasterPlan> findByCategoryIgnoreCase(String category);

    /**
     * Find first plan by category (e.g., FREEMIUM, BASE, PRO).
     */
    Optional<SubscriptionMasterPlan> findFirstByCategoryIgnoreCase(String category);

    /**
     * Fetch all plans along with their features and prices (avoid N+1 problem).
     */
    @Query("""
                SELECT DISTINCT p
                FROM SubscriptionMasterPlan p
                LEFT JOIN FETCH p.features
                LEFT JOIN FETCH p.prices
            """)
    List<SubscriptionMasterPlan> findAllWithFeaturesAndPrices();

    /**
     * Find all active plans.
     */
    List<SubscriptionMasterPlan> findByIsActiveTrue();

    /**
     * Find all active plans excluding enterprise plans.
     */
    List<SubscriptionMasterPlan> findByIsActiveTrueAndIsEnterpriseFalse();

    /**
     * Find all active & non-enterprise plans that should be shown in UI.
     */
    List<SubscriptionMasterPlan> findByIsActiveTrueAndIsEnterpriseFalseAndShowInUiTrue();

    /**
     * Find all active enterprise plans.
     */
    List<SubscriptionMasterPlan> findByIsActiveTrueAndIsEnterpriseTrue();

    /**
     * Optionally, find active plan by plan code (used for UI lookup).
     */
    Optional<SubscriptionMasterPlan> findByExternalPlanCodeAndIsActiveTrue(String externalPlanCode);

    List<SubscriptionMasterPlan> findByExternalPlanCodeIn(Collection<String> planCodes);
}
