package com.welhire.welhire_subscription_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.welhire.welhire_subscription_service.entity.*;
import com.welhire.welhire_subscription_service.enums.SubscriptionStatus;
import com.welhire.welhire_subscription_service.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Creates a PAID subscription after a successful checkout (COMPLETED).
 * Idempotent via activationOrderId on BillingEntitySubscription.
 * Also enriches BillingEntity from hosted page payload and marks profile complete when sufficient.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionProvisioningService {

    private final BillingEntityRepository billingRepo;
    private final SubscriptionMasterPlanRepository planRepo;
    private final PlanPriceRepository planPriceRepo;
    private final BillingEntitySubscriptionRepository subRepo;
    private final FeatureUsageRepository featureUsageRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Idempotently activate a PAID subscription from a successful checkout.
     * - Replaces only ACTIVE subscription of the SAME planCode (single-active-per-plan).
     * - Allows co-existence of different planCodes.
     * - Uses plan.intervalUnit for endDate.
     * - Initializes FeatureUsage from plan features.
     * - Enriches BillingEntity from HostedCheckout.requestPayloadJson and stored pricebookId.
     */
    @Transactional
    public void activateFromSuccessfulCheckout(HostedCheckout hc) {
        if (hc == null) {
            log.warn("activateFromSuccessfulCheckout: HostedCheckout is null");
            return;
        }
        if (!"COMPLETED".equalsIgnoreCase(nz(hc.getStatus()))) {
            log.debug("activateFromSuccessfulCheckout: orderId={} status={} → skip (not COMPLETED)",
                    hc.getOrderId(), hc.getStatus());
            return;
        }

        final String orderId = hc.getOrderId();
        final String tenantId = hc.getTenantId();
        final String billingId = hc.getBillingId();
        final String planCode = hc.getPlanCode();
        final String currency = hc.getCurrency();

        // Idempotency (webhook retries)
        if (StringUtils.hasText(orderId) && subRepo.findByActivationOrderId(orderId).isPresent()) {
            log.info("Provisioning already done for orderId={}", orderId);
            // still attempt BE enrichment (harmless) if something was missing earlier
            tryEnrichBillingEntity(hc);
            return;
        }

        // Resolve references
        BillingEntity be = billingRepo.findByBillingIdAndTenantId(billingId, tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "BillingEntity not found for tenant=" + tenantId + ", billingId=" + billingId));

        SubscriptionMasterPlan plan = planRepo.findByExternalPlanCode(planCode)
                .orElseThrow(() -> new IllegalStateException("Plan not found: " + planCode));

        // Get amount from PlanPrice for this plan/currency
        BigDecimal amount = planPriceRepo.findByPlanIdAndCurrency(plan.getId(), currency)
                .map(PlanPrice::getAmount)
                .orElseThrow(() -> new IllegalStateException(
                        "Plan price not found for plan=" + planCode + ", currency=" + currency));

        // Enrich BillingEntity (best-effort; harmless if already present)
        be = tryEnrichBillingEntity(hc);

        // Close SAME-PLAN ACTIVE sub (single-active-per-planCode)
        subRepo.findActiveForSamePlan(be, plan).ifPresent(existing -> {
            existing.setStatus(SubscriptionStatus.CANCELLED);
            existing.setEndDate(OffsetDateTime.now());
            subRepo.save(existing);
            log.info("Closed ACTIVE subscription id={} (same planCode={}) for tenant={} billingId={}",
                    existing.getSubscriptionId(), planCode, tenantId, billingId);
        });

        // Dates
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start = now;
        OffsetDateTime end = computeEndDateFromInterval(start, plan.getIntervalUnit());
        OffsetDateTime trialEnd = null;
        if (plan.getTrialPeriodDays() != null && plan.getTrialPeriodDays() > 0) {
            trialEnd = start.plusDays(plan.getTrialPeriodDays());
        }

        // Seats (if you later add quantity-by-checkout, set here)
        Integer licenseLimit = plan.getLicenseLimit();

        // Create ACTIVE paid subscription
        BillingEntitySubscription sub = BillingEntitySubscription.builder()
                .billingEntity(be)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .isZohoLinked(Boolean.TRUE)               // since checkout succeeded via Zoho
                .isPaidPlan(Boolean.TRUE)
                .startDate(start)
                .purchaseDate(now)
                .endDate(end)
                .trialEndDate(trialEnd)
                .autoRenew(Boolean.FALSE)                 // adjust if you model auto-renew
                .currency(currency)
                .amount(amount)
                .zohoSubscriptionId(hc.getZohoSubscriptionId())  // may be null; webhook sets onto hc earlier
                // .licenseLimit(licenseLimit)            // if your entity supports it
                .activationOrderId(orderId)
                .build();

        BillingEntitySubscription saved = subRepo.saveAndFlush(sub);
        log.info("Provisioned ACTIVE subscription id={} plan={} intervalUnit={} amount={} {} for tenant={} billingId={}",
                saved.getSubscriptionId(), planCode, normalizeIntervalUnit(plan.getIntervalUnit()),
                amount, currency, tenantId, billingId);

        // Initialize FeatureUsage from plan features
        initializeFeatureUsageFromPlan(saved, plan);
    }

    /* --------------------------- BillingEntity enrichment --------------------------- */

    /**
     * Best-effort enrichment of BillingEntity using:
     * - HostedCheckout.pricebookId
     * - HostedCheckout.requestPayloadJson → customer, billing_address, GST, PoS
     * Sets hasCompleteBillingProfile=true when enough fields are present.
     */
    @Transactional
    protected BillingEntity tryEnrichBillingEntity(HostedCheckout hc) {
        if (hc == null) return null;

        final String tenantId = hc.getTenantId();
        final String billingId = hc.getBillingId();
        BillingEntity be = billingRepo.findByBillingIdAndTenantId(billingId, tenantId).orElse(null);
        if (be == null) return null;

        boolean changed = false;

        // pricebook from HostedCheckout row
        if (!StringUtils.hasText(be.getPricebookId()) && StringUtils.hasText(hc.getPricebookId())) {
            be.setPricebookId(hc.getPricebookId());
            changed = true;
        }

        Map<String, Object> root = safeParseJson(hc.getRequestPayloadJson());
        Map<String, Object> customer = asMap(root.get("customer"));
        if (customer != null && !customer.isEmpty()) {
            // top-level
            String displayName = asText(customer.get("display_name"));
            String firstName = asText(customer.get("first_name"));
            String lastName = asText(customer.get("last_name"));
            String email = asText(customer.get("email"));
            String phone = nz(asText(customer.get("mobile")), asText(customer.get("phone")));
            String company = asText(customer.get("company_name"));

            // derive name preference: company > display_name > (first last)
            String bestName = firstNonEmpty(company, displayName,
                    joinNonEmpty(" ", firstName, lastName));

            if (!StringUtils.hasText(be.getName()) && StringUtils.hasText(bestName)) {
                be.setName(bestName);
                changed = true;
            }
            if (!StringUtils.hasText(be.getFname()) && StringUtils.hasText(firstName)) {
                be.setFname(firstName); changed = true;
            }
            if (!StringUtils.hasText(be.getLname()) && StringUtils.hasText(lastName)) {
                be.setLname(lastName); changed = true;
            }
            if (!StringUtils.hasText(be.getEmail()) && StringUtils.hasText(email)) {
                be.setEmail(email); changed = true;
            }
            if (!StringUtils.hasText(be.getMobile()) && StringUtils.hasText(phone)) {
                be.setMobile(phone); changed = true;
            }

            // addresses
            Map<String, Object> bill = asMap(customer.get("billing_address"));
            if (bill != null && !bill.isEmpty()) {
                String street = asText(bill.get("street"));
                String city = asText(bill.get("city"));
                String zip = asText(bill.get("zip"));
                String country = asText(bill.get("country"));
                String state = asText(bill.get("state"));
                String stateCode = asText(bill.get("state_code"));

                String derived = joinNonEmpty(", ", street, city, zip, country);
                if (!StringUtils.hasText(be.getBillingAddress()) && StringUtils.hasText(derived)) {
                    be.setBillingAddress(derived); changed = true;
                }
                if (!StringUtils.hasText(be.getStateName()) && StringUtils.hasText(state)) {
                    be.setStateName(state); changed = true;
                }
                if (!StringUtils.hasText(be.getStateCode()) && StringUtils.hasText(stateCode)) {
                    be.setStateCode(stateCode); changed = true;
                }
            }
        }

        // GST & place of supply from ROOT
        if (root != null) {
            String gstNo = asText(root.get("gst_no"));
            String gstTreatment = asText(root.get("gst_treatment"));
            String pos = asText(root.get("place_of_supply")); // e.g., "MH"

            if (!StringUtils.hasText(be.getGstNumber()) && StringUtils.hasText(gstNo)) {
                be.setGstNumber(gstNo); changed = true;
            }
            if (!StringUtils.hasText(be.getGstStateCode()) && StringUtils.hasText(pos)) {
                be.setGstStateCode(pos); changed = true;
            }
            // If stateCode missing but PoS present, use pos as fallback
            if (!StringUtils.hasText(be.getStateCode()) && StringUtils.hasText(pos)) {
                be.setStateCode(pos); changed = true;
            }
        }

        // Heuristic: mark complete if we have core fields
        if (!Boolean.TRUE.equals(be.getHasCompleteBillingProfile())) {
            boolean complete = StringUtils.hasText(be.getName())
                    && (StringUtils.hasText(be.getEmail()) || StringUtils.hasText(be.getMobile()))
                    && StringUtils.hasText(be.getBillingAddress())
                    && StringUtils.hasText(be.getStateCode());
            if (complete) {
                be.setHasCompleteBillingProfile(Boolean.TRUE);
                changed = true;
            }
        }

        if (changed) {
            be = billingRepo.saveAndFlush(be);
            log.info("BillingEntity enriched for tenant={} billingId={}: name={}, email={}, mobile={}, addr={}, stateCode={}, gst={}",
                    tenantId, billingId, be.getName(), be.getEmail(), be.getMobile(),
                    be.getBillingAddress(), be.getStateCode(), be.getGstNumber());
        }
        return be;
    }

    /* --------------------------- Feature usage init --------------------------- */

    private void initializeFeatureUsageFromPlan(BillingEntitySubscription sub, SubscriptionMasterPlan plan) {
        if (plan.getFeatures() == null || plan.getFeatures().isEmpty()) {
            log.warn("Plan {} has no configured features; skipping FeatureUsage init", plan.getExternalPlanCode());
            return;
        }
        plan.getFeatures().forEach(feat -> {
            FeatureUsage fu = FeatureUsage.builder()
                    .subscription(sub)
                    .featureKey(feat.getKey())
                    .limitCount(feat.getLimit())
                    .usedCount(0)
                    .isExhausted(false)
                    .build();
            featureUsageRepo.save(fu);
        });
        featureUsageRepo.flush();
        log.info("Initialized {} feature usage rows for subscription id={}",
                plan.getFeatures().size(), sub.getSubscriptionId());
    }

    /* --------------------------- Term computation helpers --------------------------- */

    /**
     * MONTH → +1M, YEAR → +1Y; WEEK/DAY supported; null/unknown → null (open-ended).
     */
    private OffsetDateTime computeEndDateFromInterval(OffsetDateTime start, String intervalRaw) {
        if (intervalRaw == null) return null;
        String iu = intervalRaw.trim().toLowerCase(Locale.ROOT);
        if (iu.startsWith("mon")) return start.plusMonths(1);
        if (iu.startsWith("yr") || iu.startsWith("year") || iu.startsWith("ann")) return start.plusYears(1);
        if (iu.startsWith("week")) return start.plusWeeks(1);
        if (iu.startsWith("day")) return start.plusDays(1);
        return null; // NONE/unrecognized → open-ended until cancelled
    }

    private String normalizeIntervalUnit(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("mon")) return "MONTH";
        if (v.startsWith("yr") || v.startsWith("year") || v.startsWith("ann")) return "YEAR";
        if (v.startsWith("week")) return "WEEK";
        if (v.startsWith("day")) return "DAY";
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /* --------------------------- Tiny utils --------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeParseJson(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse JSON payload for enrichment: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private String asText(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private String nz(String v) { return (v == null) ? "" : v; }

    private String nz(String a, String b) { return StringUtils.hasText(a) ? a : (StringUtils.hasText(b) ? b : null); }

    private String joinNonEmpty(String sep, String... parts) {
        return String.join(sep, Arrays.stream(parts).filter(StringUtils::hasText).toList());
    }

    private String firstNonEmpty(String... parts) {
        for (String p : parts) if (StringUtils.hasText(p)) return p;
        return null;
    }
}
