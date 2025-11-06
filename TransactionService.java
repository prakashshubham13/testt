package com.welhire.welhire_subscription_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.welhire.welhire_subscription_service.entity.HostedCheckout;
import com.welhire.welhire_subscription_service.entity.SubscriptionMasterPlan;
import com.welhire.welhire_subscription_service.repository.HostedCheckoutRepository;
import com.welhire.welhire_subscription_service.repository.SubscriptionMasterPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final HostedCheckoutRepository hostedCheckoutRepository;
    private final SubscriptionMasterPlanRepository planRepository;
    private final ObjectMapper objectMapper;

    /**
     * Returns SUCCESS transactions for a tenant+billing, newest first, with intervalUnit.
     */
    @Transactional(readOnly = true)
    public List<TransactionView> getTransactions(String tenantId, String billingId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(billingId)) {
            return List.of();
        }

        // Expect a Spring Data method like:
        // List<HostedCheckout> findAllByTenantIdAndBillingIdOrderByCreatedAtDesc(String tenantId, String billingId);
        List<HostedCheckout> rows =
                hostedCheckoutRepository.findAllByTenantIdAndBillingIdOrderByCreatedAtDesc(tenantId.trim(), billingId.trim());

        return rows.stream()
                .filter(this::isSuccessful) // status filter (SUCCESS/COMPLETED)
                .map(this::toView)
                .collect(Collectors.toList());
    }

    private boolean isSuccessful(HostedCheckout hc) {
        // Accept rows that are locally marked completed
        if ("COMPLETED".equalsIgnoreCase(nullSafe(hc.getStatus()))) return true;

        // Or provider_status that implies success
        String ps = nullSafe(hc.getProviderStatus()).toLowerCase(Locale.ROOT);
        return switch (ps) {
            case "success", "paid", "completed", "used" -> true;
            default -> false;
        };
    }

    private TransactionView toView(HostedCheckout hc) {
        // Defaults
        String planCode = nullSafe(hc.getPlanCode());
        String currency = nullSafe(hc.getCurrency());
        String providerStatus = nullSafe(hc.getProviderStatus());
        String expiringTime = hc.getExpiringTime() != null ? hc.getExpiringTime().toString() : null;

        // Derive category & intervalUnit from plan
        String planCategory = null;
        String intervalUnit = null;
        if (StringUtils.hasText(planCode)) {
            Optional<SubscriptionMasterPlan> planOpt = planRepository.findByExternalPlanCode(planCode);
            if (planOpt.isPresent()) {
                SubscriptionMasterPlan p = planOpt.get();
                planCategory = nullSafe(p.getCategory());
                intervalUnit = normalizeIntervalUnit(p.getIntervalUnit());
            }
        }

        // Parse latest provider payload (Zoho payment JSON) to extract financials
        BigDecimal amount = null;
        String invoiceId = null;
        String email = null;
        String paymentMethod = null;
        String purchaseDate = null;

        try {
            Map<String, Object> root = parseJsonSafely(hc.getResponsePayloadJson());
            if (root != null) {
                Map<String, Object> payment = asMap(root.get("payment"));
                if (!payment.isEmpty()) {
                    // amount & currency
                    amount = asBigDecimal(payment.get("amount"));
                    if (!StringUtils.hasText(currency)) {
                        currency = asText(payment.get("currency_code"));
                    }
                    // purchase date
                    purchaseDate = asText(payment.get("date"));
                    // email & payment mode
                    email = asText(payment.get("email"));
                    paymentMethod = firstNonBlank(
                            asText(nested(payment, "autotransaction", "payment_gateway")),
                            asText(payment.get("payment_mode"))
                    );
                    // invoice id – pick the first invoice if present
                    Object invoices = payment.get("invoices");
                    if (invoices instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (first instanceof Map<?,?> m) {
                            invoiceId = asText(((Map<?,?>) m).get("invoice_id"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed extracting payment fields for orderId={}: {}", hc.getOrderId(), e.getMessage());
        }

        // Fallbacks if some fields are still missing
        if (!StringUtils.hasText(purchaseDate) && hc.getCreatedAt() != null) {
            purchaseDate = hc.getCreatedAt().toString();
        }

        // Normalize status to SUCCESS for response (you asked for only success rows)
        String status = "SUCCESS";

        return new TransactionView(
                planCode,
                planCategory,
                intervalUnit,
                purchaseDate,
                invoiceId,
                amount,
                currency,
                status,
                expiringTime,
                email,
                paymentMethod
        );
    }

    // ---------- helpers ----------

    private String normalizeIntervalUnit(String raw) {
        if (raw == null) return "NONE";
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("year") || v.startsWith("yr") || v.startsWith("ann")) return "YEAR";
        if (v.startsWith("mon")) return "MONTH";
        if (v.startsWith("week")) return "WEEK";
        if (v.startsWith("day")) return "DAY";
        if (v.startsWith("none")) return "NONE";
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> parseJsonSafely(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?,?> m) ? (Map<String, Object>) m : Collections.emptyMap();
        }

    private String asText(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> m, String k1, String k2) {
        Object inner = m.get(k1);
        if (!(inner instanceof Map<?,?> mm)) return null;
        return ((Map<String, Object>) mm).get(k2);
    }

    private String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) return a;
        if (StringUtils.hasText(b)) return b;
        return null;
    }

    private String nullSafe(String s) {
        return (s == null) ? "" : s;
    }

    // Response DTO
    public static record TransactionView(
            String planCode,
            String planCategory,
            String intervalUnit,
            String purchaseDate,
            String invoiceId,
            BigDecimal amount,
            String currency,
            String status,
            String expiringTime,
            String email,
            String paymentMethod
    ) {}
}
