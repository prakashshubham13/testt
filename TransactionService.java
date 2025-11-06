package com.welhire.welhire_subscription_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.welhire.welhire_subscription_service.entity.HostedCheckout;
import com.welhire.welhire_subscription_service.entity.SubscriptionMasterPlan;
import com.welhire.welhire_subscription_service.repository.HostedCheckoutRepository;
import com.welhire.welhire_subscription_service.repository.SubscriptionMasterPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final HostedCheckoutRepository hostedCheckoutRepository;
    private final SubscriptionMasterPlanRepository planRepository;
    private final ObjectMapper objectMapper;

    // UI/result DTO
    public record TransactionView(
            String planCode,
            String planCategory,
            String intervalUnit,
            String purchaseDate,   // ISO string (from payment.date or createdAt fallback)
            String invoiceId,
            BigDecimal amount,
            String currency,
            String status,         // SUCCESS | FAILED
            String expiringTime,   // ISO string (if present)
            String email,
            String paymentMethod
    ) {}

    // Generic page wrapper
    public record PageResponse<T>(
            int page,
            int size,
            long totalElements,
            int totalPages,
            List<T> items
    ) {}

    /**
     * Return SUCCESS and FAILED transactions for a tenant+billing, newest first, paginated.
     * page: 0-based; size: default 10 if invalid.
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionView> getTransactions(
            String tenantId,
            String billingId,
            Integer page,
            Integer size
    ) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(billingId)) {
            return new PageResponse<>(0, 0, 0, 0, List.of());
        }

        int p = (page == null || page < 0) ? 0 : page;
        int s = (size == null || size <= 0 || size > 200) ? 10 : size;

        Pageable pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));

        // We only care about COMPLETED and FAILED at DB level (normalize later)
        List<String> dbStatuses = List.of("COMPLETED", "FAILED");
        Page<HostedCheckout> pageRows =
                hostedCheckoutRepository.findByTenantIdAndBillingIdAndStatusIn(
                        tenantId.trim(), billingId.trim(), dbStatuses, pageable);

        // Map rows → views
        List<TransactionView> items = new ArrayList<>(pageRows.getNumberOfElements());
        for (HostedCheckout hc : pageRows.getContent()) {
            items.add(toView(hc));
        }

        return new PageResponse<>(
                pageRows.getNumber(),
                pageRows.getSize(),
                pageRows.getTotalElements(),
                pageRows.getTotalPages(),
                items
        );
    }

    private TransactionView toView(HostedCheckout hc) {
        String planCode = nz(hc.getPlanCode());
        String currency = nz(hc.getCurrency());
        String expiringTime = hc.getExpiringTime() != null ? hc.getExpiringTime().toString() : null;

        // Normalize local status → SUCCESS/FAILED (ignore others here because we queried on those two)
        String normalized = "COMPLETED".equalsIgnoreCase(nz(hc.getStatus())) ? "SUCCESS" : "FAILED";

        // Plan metadata
        String planCategory = null;
        String intervalUnit = null;
        if (StringUtils.hasText(planCode)) {
            planRepository.findByExternalPlanCode(planCode).ifPresent(p -> {
                // lambda requires effectively final locals; use array hack or small holder
            });
            Optional<SubscriptionMasterPlan> opt = planRepository.findByExternalPlanCode(planCode);
            if (opt.isPresent()) {
                SubscriptionMasterPlan p = opt.get();
                planCategory = nz(p.getCategory());
                intervalUnit = normalizeIntervalUnit(p.getIntervalUnit());
            }
        }

        // Extract payment payload (use last valid JSON block from merged response)
        PaymentExtract pe = extractFromResponsePayload(hc.getResponsePayloadJson());

        // Fill currency from payment payload if DB currency is blank
        if (!StringUtils.hasText(currency) && StringUtils.hasText(pe.currency)) {
            currency = pe.currency;
        }

        // Purchase date — prefer payment.date; else createdAt
        String purchaseDate = StringUtils.hasText(pe.date)
                ? pe.date
                : (hc.getCreatedAt() != null ? hc.getCreatedAt().toString() : null);

        return new TransactionView(
                planCode,
                planCategory,
                intervalUnit,
                purchaseDate,
                pe.invoiceId,
                pe.amount,
                currency,
                normalized,
                expiringTime,
                pe.email,
                pe.paymentMethod
        );
    }

    /* ==========================  Payment JSON extraction  ========================== */

    private static final String MERGE_DELIM = "\n\n---\n\n";

    private PaymentExtract extractFromResponsePayload(String mergedJson) {
        PaymentExtract out = new PaymentExtract();

        if (!StringUtils.hasText(mergedJson)) {
            return out;
        }

        String json = lastParsableBlock(mergedJson);
        Map<String, Object> root = null;
        try {
            root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Could not parse response_payload_json: {}", shorten(json));
            return out;
        }

        // Prefer Zoho "payment" shape
        if (root.get("payment") instanceof Map) {
            Map<String, Object> p = castMap(root.get("payment"));

            out.date = asText(p.get("date"));
            out.email = asText(p.get("email"));

            // payment method
            String method = asText(p.get("payment_mode"));
            if (!StringUtils.hasText(method)) {
                method = asText(nested(p, "autotransaction", "payment_gateway"));
            }
            out.paymentMethod = method;

            // currency
            out.currency = asText(p.get("currency_code"));

            // amount
            out.amount = asBigDecimal(p.get("amount"));
            if (out.amount == null) {
                Map<String, Object> inv0 = firstInvoice(p);
                if (inv0 != null) {
                    out.amount = asBigDecimal(inv0.get("invoice_amount"));
                }
            }

            // invoice id
            out.invoiceId = firstInvoiceField(p, "invoice_id");
            return out;
        }

        // Fallbacks (hostedpage or any other flat formats if ever present)
        out.email = asText(nested(root, "data", "customer_email"));
        out.paymentMethod = asText(nested(root, "data", "payment_mode"));
        out.currency = asText(nested(root, "data", "currency"));
        out.amount = asBigDecimal(nested(root, "data", "amount"));
        out.invoiceId = asText(nested(root, "data", "invoice_id"));
        out.date = asText(nested(root, "data", "date"));
        return out;
    }

    private String lastParsableBlock(String merged) {
        if (merged.contains(MERGE_DELIM)) {
            String[] parts = merged.split(MERGE_DELIM);
            for (int i = parts.length - 1; i >= 0; i--) {
                String block = parts[i].trim();
                if (block.isEmpty()) continue;
                if (looksLikeJson(block)) return block;
            }
        }
        return merged.trim();
    }

    private boolean looksLikeJson(String s) {
        String t = s.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    /* ==========================  Tiny helpers  ========================== */

    private String normalizeIntervalUnit(String raw) {
        if (!StringUtils.hasText(raw)) return "NONE";
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("year") || v.startsWith("yr") || v.startsWith("ann")) return "YEAR";
        if (v.startsWith("mon")) return "MONTH";
        if (v.startsWith("week")) return "WEEK";
        if (v.startsWith("day")) return "DAY";
        if (v.startsWith("none")) return "NONE";
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private String shorten(String s) {
        if (s == null) return "";
        return s.length() > 600 ? s.substring(0, 600) + "...(truncated)" : s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (o instanceof Map<?,?> m) ? (Map<String, Object>) m : Map.of();
    }

    private String asText(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Number n) return new BigDecimal(n.toString());
            String t = String.valueOf(o).trim();
            return t.isEmpty() ? null : new BigDecimal(t);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> m, String key, String nestedKey) {
        if (m == null) return null;
        Object inner = m.get(key);
        if (!(inner instanceof Map<?,?> im)) return null;
        return ((Map<String, Object>) im).get(nestedKey);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstInvoice(Map<String, Object> payment) {
        Object invs = payment.get("invoices");
        if (invs instanceof List<?> l && !l.isEmpty()) {
            Object first = l.get(0);
            if (first instanceof Map<?,?> m) return (Map<String, Object>) m;
        }
        return null;
    }

    private String firstInvoiceField(Map<String, Object> payment, String k) {
        Map<String, Object> inv0 = firstInvoice(payment);
        return inv0 == null ? null : asText(inv0.get(k));
    }

    // internal holder
    private static final class PaymentExtract {
        String date;
        String email;
        String paymentMethod;
        String currency;
        BigDecimal amount;
        String invoiceId;
    }
}
