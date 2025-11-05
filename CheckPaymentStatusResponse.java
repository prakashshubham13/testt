package com.welhire.welhire_subscription_service.dto;

import lombok.*;
import java.io.Serializable;

/**
 * Response for /checkPaymentStatus
 * Includes normalized payment status, hosted page info,
 * and enriched details about the currently bought plan and subscription.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckPaymentStatusResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    // --- Core payment/hosted page info ---
    private String orderId;
    private String gateway;

    private PaymentStatus status;       // normalized (SUCCESS/FAILED/EXPIRED/PENDING/UNKNOWN)
    private String providerStatusRaw;   // raw provider status for debugging
    private String hostedUrl;           // hosted page URL (if still relevant)
    private String expiringTime;        // provider string (e.g., "2025-11-05T12:30:00Z" or "+0530" style)
    private String message;

    // --- New: currently bought plan (features sorted; prices included) ---
    // Uses the same DTO as plan listing to keep one source of truth.
    private PlanDtos.PlanView plan;

    // --- New: subscription details (present if subscription is already provisioned) ---
    private Long   subscriptionId;
    private String subscriptionStatus;  // ACTIVE, CANCELLED, EXPIRED, etc.
    private String startDate;           // ISO-8601 string
    private String endDate;             // ISO-8601 string
    private String purchaseDate;        // ISO-8601 string
    private Boolean paidPlan;           // true for paid, false for freemium
    private String zohoSubscriptionId;  // provider subscription id, if linked
}
