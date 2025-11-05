package com.welhire.welhire_subscription_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.welhire.welhire_subscription_service.dto.*;
import com.welhire.welhire_subscription_service.entity.BillingEntity;
import com.welhire.welhire_subscription_service.entity.BillingEntitySubscription;
import com.welhire.welhire_subscription_service.entity.HostedCheckout;
import com.welhire.welhire_subscription_service.entity.PlanPrice;
import com.welhire.welhire_subscription_service.entity.Pricebook;
import com.welhire.welhire_subscription_service.entity.SubscriptionMasterPlan;
import com.welhire.welhire_subscription_service.enums.SubscriptionStatus;
import com.welhire.welhire_subscription_service.gateway.PaymentGateway;
import com.welhire.welhire_subscription_service.gateway.PaymentGatewayRegistry;
import com.welhire.welhire_subscription_service.repository.BillingEntityRepository;
import com.welhire.welhire_subscription_service.repository.BillingEntitySubscriptionRepository;
import com.welhire.welhire_subscription_service.repository.HostedCheckoutRepository;
import com.welhire.welhire_subscription_service.repository.PlanPriceRepository;
import com.welhire.welhire_subscription_service.repository.PricebookRepository;
import com.welhire.welhire_subscription_service.repository.SubscriptionMasterPlanRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Checkout flow:
 * - getBillingDetails()
 * - createHostedPage()
 * - checkPaymentStatus()
 * - processPaymentWebhook() <-- provider → our service (source of truth)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    // --- Billing details dependencies ---
    private final BillingEntityRepository billingRepo;

    // --- Hosted page flow dependencies ---
    private final SubscriptionMasterPlanRepository planRepo;
    private final PlanPriceRepository priceRepo;
    private final PricebookRepository pricebookRepo;
    private final HostedCheckoutRepository hostedRepo;
    private final PaymentGatewayRegistry gatewayRegistry;

    // Plan-specific ACTIVE subscription guard (same-plan check at checkout time)
    private final BillingEntitySubscriptionRepository billingSubRepo;

    // Provisioning service (creates subscription on SUCCESS; same-plan-only replacement)
    private final SubscriptionProvisioningService subscriptionProvisioningService;

    // NEW: to map plan entity → PlanDtos.PlanView (sorted features, prices, etc.)
    private final PlanService planService;

    private final ObjectMapper objectMapper;

    // Optional HMAC secret for Zoho webhook validation
    @Value("${zoho.org.in.webhook.zohoSecret}")
    private String zohoWebhookSecret;

    // ---------------------------------------------------------------------
    // /getBillingDetails
    // ---------------------------------------------------------------------
    @Transactional(readOnly = true)
    public GetBillingDetailsResponse getBillingDetails(GetBillingDetailsRequest req) {
        final String tenantId = req.getTenantId().trim();
        final String billingId = req.getBillingId().trim();

        BillingEntity be = billingRepo.findByBillingIdAndTenantId(billingId, tenantId).orElse(null);

        if (be == null) {
            return GetBillingDetailsResponse.builder()
                    .state(BillingState.NOT_FOUND)
                    .exists(false)
                    .info(null)
                    .message("No billing profile found for tenantId and billingId.")
                    .build();
        }

        final boolean complete = Boolean.TRUE.equals(be.getHasCompleteBillingProfile());
        final BillingProfileInfo info = toInfo(be);

        if (!complete) {
            return GetBillingDetailsResponse.builder()
                    .state(BillingState.INCOMPLETE_PROFILE)
                    .exists(true)
                    .info(info)
                    .message("Billing profile is incomplete. Please provide remaining details.")
                    .build();
        }

        return GetBillingDetailsResponse.builder()
                .state(BillingState.COMPLETE)
                .exists(true)
                .info(info)
                .message("Billing profile is complete.")
                .build();
    }

    private BillingProfileInfo toInfo(BillingEntity be) {
        return BillingProfileInfo.builder()
                .billingEntityId(be.getBillingEntityId())
                .tenantId(be.getTenantId())
                .billingId(be.getBillingId())
                .orgId(be.getOrgId())
                .name(be.getName())
                .billingAddress(be.getBillingAddress())
                .gstin(be.getGstin())
                .stateName(be.getStateName())
                .stateCode(be.getStateCode())
                .adminName(be.getAdminName())
                .adminEmail(be.getAdminEmail())
                .zohoCustomerId(be.getZohoCustomerId())
                .isZohoLinked(be.getIsZohoLinked())
                .hasCompleteBillingProfile(be.getHasCompleteBillingProfile())
                .gstNumber(be.getGstNumber())
                .gstStateCode(be.getGstStateCode())
                .email(be.getEmail())
                .mobile(be.getMobile())
                .fname(be.getFname())
                .lname(be.getLname())
                .city(be.getCity())
                .country(be.getCountry())
                .pricebookId(be.getPricebookId())
                .build();
    }

    // ---------------------------------------------------------------------
    // /getHostedPage (gateway-agnostic; Zoho adapter today)
    // ---------------------------------------------------------------------
    @Transactional
    public GetHostedPageResponse createHostedPage(GetHostedPageRequest req) {
        // 1) Normalize & validate
        final String tenantId = trim(req.getTenantId());
        final String billingId = trim(req.getBillingId());
        final String planCode = trim(req.getPlanCode());
        final String currency = trim(req.getCurrency()).toUpperCase(Locale.ROOT);
        final String gateway = StringUtils.hasText(req.getGateway()) ? req.getGateway().trim() : "ZOHOBILLING";
        final String redirect = trim(req.getRedirectUrl());

        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(billingId) ||
                !StringUtils.hasText(planCode) || !StringUtils.hasText(currency)) {
            throw new IllegalArgumentException("tenantId, billingId, planCode and currency are required");
        }

        // 1.a) Fetch BillingEntity (ensures tenant+billing exists)
        BillingEntity be = billingRepo.findByBillingIdAndTenantId(billingId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "BillingEntity not found for tenantId=" + tenantId + ", billingId=" + billingId));

        // 2) Resolve pricebook (currency -> pricebookId)
        Pricebook pb = pricebookRepo.findByCurrencyIgnoreCase(currency)
                .orElseThrow(() -> new IllegalStateException("No pricebook configured for currency=" + currency));
        final String pricebookId = pb.getPricebookId();

        // 3) Ensure plan exists, is ACTIVE, has price for this currency
        SubscriptionMasterPlan plan = planRepo.findByExternalPlanCode(planCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown planCode: " + planCode));

        if (!Boolean.TRUE.equals(plan.getIsActive())) {
            throw new IllegalStateException("Plan " + planCode + " is not active");
        }

        // 3.a) Guard — no ACTIVE sub for this tenant/billing **for this SAME plan**
        boolean hasActiveForThisPlan = billingSubRepo
                .existsByBillingEntity_TenantIdAndBillingEntity_BillingIdAndPlan_IdAndStatus(
                        tenantId, billingId, plan.getId(), SubscriptionStatus.ACTIVE);

        if (hasActiveForThisPlan) {
            throw new IllegalStateException(
                    "An ACTIVE subscription already exists for this tenant/billing on plan " + planCode
                            + ". Hosted checkout is not allowed.");
        }

        PlanPrice planPrice = priceRepo.findByPlanIdAndCurrency(plan.getId(), currency)
                .orElseThrow(() -> new IllegalStateException(
                        "No plan price for planCode=" + planCode + " currency=" + currency));

        // 4) Build provider request payload (generic map). IMPORTANT: do NOT include orgKey here.
        Map<String, Object> payload = new HashMap<>();
        payload.put("pricebook_id", pricebookId);
        payload.put("plan", Map.of("plan_code", planCode));
        if (StringUtils.hasText(redirect)) {
            payload.put("redirect_url", redirect);
        }

        // 4.a) --- India GST must be at ROOT for Hosted Pages ---
        final String gstNo = StringUtils.hasText(req.getGstNo()) ? req.getGstNo().trim() : null;
        final String posFromReq = StringUtils.hasText(req.getGstStateCode())
                ? req.getGstStateCode().trim().toUpperCase(Locale.ROOT)
                : null;
        final String posFallback = StringUtils.hasText(req.getBillStateCode())
                ? req.getBillStateCode().trim().toUpperCase(Locale.ROOT)
                : null;

        if (StringUtils.hasText(gstNo)) {
            payload.put("gst_treatment", "business_gst");
            payload.put("gst_no", gstNo);
        } else {
            payload.put("gst_treatment", "consumer");
        }

        String placeOfSupply = StringUtils.hasText(posFromReq) ? posFromReq : posFallback;
        if (StringUtils.hasText(placeOfSupply)) {
            payload.put("place_of_supply", placeOfSupply); // e.g., "MH"
        }

        // 4.b) Customer block
        boolean linked = Boolean.TRUE.equals(req.getIsZohoLinked());
        if (linked && StringUtils.hasText(req.getZohoCustomerId())) {
            // Linked flow
            payload.put("customer_id", req.getZohoCustomerId().trim());
        } else {
            // New-customer flow
            Map<String, Object> cust = new HashMap<>();
            putIfText(cust, "display_name", req.getDisplayName());
            putIfText(cust, "first_name", req.getFirstName());
            putIfText(cust, "last_name", req.getLastName());
            putIfText(cust, "email", req.getEmail());
            putIfText(cust, "phone", req.getPhone());
            putIfText(cust, "mobile", req.getMobile());
            putIfText(cust, "company_name", req.getCompanyName());
            putIfText(cust, "website", req.getWebsite());

            // Billing address
            Map<String, Object> bill = new HashMap<>();
            putIfText(bill, "attention", req.getBillAttention());
            putIfText(bill, "street", req.getBillStreet());
            putIfText(bill, "city", req.getBillCity());
            putIfText(bill, "state", req.getBillState());
            putIfText(bill, "state_code", req.getBillStateCode());
            putIfText(bill, "zip", req.getBillZip());
            putIfText(bill, "country", req.getBillCountry());
            putIfText(bill, "fax", req.getBillFax());

            // Shipping address
            Map<String, Object> ship = new HashMap<>();
            putIfText(ship, "attention", req.getShipAttention());
            putIfText(ship, "street", req.getShipStreet());
            putIfText(ship, "city", req.getShipCity());
            putIfText(ship, "state", req.getShipState());
            putIfText(ship, "state_code", req.getShipStateCode());
            putIfText(ship, "zip", req.getShipZip());
            putIfText(ship, "country", req.getShipCountry());
            putIfText(ship, "fax", req.getShipFax());

            if (!bill.isEmpty())
                cust.put("billing_address", bill);
            if (!ship.isEmpty())
                cust.put("shipping_address", ship);

            // Keep pricebook on the customer for future orders (optional)
            cust.put("pricebook_id", pricebookId);

            payload.put("customer", cust);
        }

        // 5) Persist HostedCheckout row (status=CREATED) for webhook correlation
        String orderId = UUID.randomUUID().toString();
        HostedCheckout hc = HostedCheckout.builder()
                .orderId(orderId)
                .gateway(gateway)
                .tenantId(tenantId)
                .billingId(billingId)
                .planCode(planCode)
                .currency(currency)
                .pricebookId(pricebookId)
                .status("CREATED")
                .redirectUrl(redirect)
                .requestPayloadJson(writeJsonSafe(payload))
                .build();
        hostedRepo.saveAndFlush(hc);

        // 6) Call selected gateway (Zoho adapter today)
        PaymentGateway gw = gatewayRegistry.resolve(gateway);
        PaymentGateway.HostedPageResult res = gw.createHostedPage(payload);

        // 7) Update HostedCheckout with provider details; mark PENDING
        hc.setProviderHostedpageId(res.hostedpageId());
        hc.setProviderDecryptedHostedpageId(res.decryptedHostedpageId());
        hc.setProviderStatus(res.status());
        hc.setHostedUrl(res.url());
        hc.setStatus("PENDING");
        hc.setResponsePayloadJson(res.rawResponseJson());
        if (res.expiringTime() != null) {
            hc.setExpiringTime(parseZohoTimeSafe(res.expiringTime())); // tolerant parser
        }
        hostedRepo.save(hc);

        // 8) Return to client
        return GetHostedPageResponse.builder()
                .orderId(orderId)
                .gateway(gateway)
                .hostedpageId(res.hostedpageId())
                .url(res.url())
                .status(res.status())
                .expiringTime(res.expiringTime())
                .build();
    }

    @Transactional
    public GetHostedPageResponse createHostedPageModified(GetHostedPageRequest req) {
        // 1) Normalize & validate
        final String tenantId = trim(req.getTenantId());
        final String billingId = trim(req.getBillingId());
        final String planCode = trim(req.getPlanCode());
        final String currency = trim(req.getCurrency()).toUpperCase(Locale.ROOT);
        final String gateway = StringUtils.hasText(req.getGateway()) ? req.getGateway().trim() : "ZOHOBILLING";
        final String redirect = trim(req.getRedirectUrl());

        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(billingId) ||
                !StringUtils.hasText(planCode) || !StringUtils.hasText(currency)) {
            throw new IllegalArgumentException("tenantId, billingId, planCode and currency are required");
        }

        // 2) Resolve pricebook (currency -> pricebookId)
        Pricebook pb = pricebookRepo.findByCurrencyIgnoreCase(currency)
                .orElseThrow(() -> new IllegalStateException("No pricebook configured for currency=" + currency));
        final String pricebookId = pb.getPricebookId();

        // 3) Upsert BillingEntity (auto-create minimal if absent)
        BillingEntity be = billingRepo.findByBillingIdAndTenantId(billingId, tenantId).orElse(null);
        if (be == null) {
            final String beName = StringUtils.hasText(req.getCompanyName()) ? req.getCompanyName().trim()
                    : (StringUtils.hasText(req.getDisplayName()) ? req.getDisplayName().trim()
                    : ("Tenant " + tenantId));

            final String billStreet = trim(req.getBillStreet());
            final String billCity = trim(req.getBillCity());
            final String billZip = trim(req.getBillZip());
            final String billCountry = trim(req.getBillCountry());
            final String derivedAddr = String.join(", ",
                    new ArrayList<>(Arrays.asList(billStreet, billCity, billZip, billCountry)).stream()
                            .filter(StringUtils::hasText).toList());

            be = BillingEntity.builder()
                    .tenantId(tenantId)
                    .billingId(billingId)
                    .name(beName)
                    .email(trim(req.getEmail()))
                    .mobile(trim(req.getMobile()))
                    .fname(trim(req.getFirstName()))
                    .lname(trim(req.getLastName()))
                    .billingAddress(derivedAddr)
                    .stateName(trim(req.getBillState()))
                    .stateCode(trim(req.getBillStateCode()))
                    .gstNumber(trim(req.getGstNo()))
                    .gstStateCode(trim(req.getGstStateCode()))
                    .isZohoLinked(Boolean.FALSE)
                    .hasCompleteBillingProfile(Boolean.FALSE)
                    .pricebookId(pricebookId)
                    .build();

            try {
                be = billingRepo.saveAndFlush(be);
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // race: another request created it; re-fetch
                be = billingRepo.findByBillingIdAndTenantId(billingId, tenantId)
                        .orElseThrow(() -> new IllegalStateException("BillingEntity upsert race failed; please retry"));
            }
        } else {
            // Optionally keep pricebookId aligned to chosen currency if empty
            if (!StringUtils.hasText(be.getPricebookId())) {
                be.setPricebookId(pricebookId);
                billingRepo.save(be);
            }
        }

        // 4) Ensure plan exists, ACTIVE, and has a price for this currency
        SubscriptionMasterPlan plan = planRepo.findByExternalPlanCode(planCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown planCode: " + planCode));
        if (!Boolean.TRUE.equals(plan.getIsActive())) {
            throw new IllegalStateException("Plan " + planCode + " is not active");
        }

        // 4.a) Guard — no ACTIVE sub for this tenant/billing **for this SAME plan**
        boolean hasActiveForThisPlan = billingSubRepo
                .existsByBillingEntity_TenantIdAndBillingEntity_BillingIdAndPlan_IdAndStatus(
                        tenantId, billingId, plan.getId(), SubscriptionStatus.ACTIVE);
        if (hasActiveForThisPlan) {
            throw new IllegalStateException(
                    "An ACTIVE subscription already exists for this tenant/billing on plan " + planCode
                            + ". Hosted checkout is not allowed.");
        }

        PlanPrice planPrice = priceRepo.findByPlanIdAndCurrency(plan.getId(), currency)
                .orElseThrow(() -> new IllegalStateException(
                        "No plan price for planCode=" + planCode + " currency=" + currency));

        // 5) Build provider request payload (generic map) – do NOT include org key here
        Map<String, Object> payload = new HashMap<>();
        payload.put("pricebook_id", pricebookId);
        payload.put("plan", Map.of("plan_code", planCode));
        if (StringUtils.hasText(redirect)) {
            payload.put("redirect_url", redirect);
        }

        // 5.a) India GST at ROOT
        final String gstNo = StringUtils.hasText(req.getGstNo()) ? req.getGstNo().trim() : null;
        final String posFromReq = StringUtils.hasText(req.getGstStateCode())
                ? req.getGstStateCode().trim().toUpperCase(Locale.ROOT)
                : null;
        final String posFallback = StringUtils.hasText(req.getBillStateCode())
                ? req.getBillStateCode().trim().toUpperCase(Locale.ROOT)
                : null;

        if (StringUtils.hasText(gstNo)) {
            payload.put("gst_treatment", "business_gst");
            payload.put("gst_no", gstNo);
        } else {
            payload.put("gst_treatment", "consumer");
        }

        String placeOfSupply = StringUtils.hasText(posFromReq) ? posFromReq : posFallback;
        if (StringUtils.hasText(placeOfSupply)) {
            payload.put("place_of_supply", placeOfSupply); // e.g., "MH"
        }

        // 5.b) Customer block – linked vs new
        boolean linked2 = Boolean.TRUE.equals(req.getIsZohoLinked());
        if (linked2 && StringUtils.hasText(req.getZohoCustomerId())) {
            payload.put("customer_id", req.getZohoCustomerId().trim());
        } else {
            Map<String, Object> cust = new HashMap<>();
            putIfText(cust, "display_name", req.getDisplayName());
            putIfText(cust, "first_name", req.getFirstName());
            putIfText(cust, "last_name", req.getLastName());
            putIfText(cust, "email", req.getEmail());
            putIfText(cust, "phone", req.getPhone());
            putIfText(cust, "mobile", req.getMobile());
            putIfText(cust, "company_name", req.getCompanyName());
            putIfText(cust, "website", req.getWebsite());

            Map<String, Object> bill = new HashMap<>();
            putIfText(bill, "attention", req.getBillAttention());
            putIfText(bill, "street", req.getBillStreet());
            putIfText(bill, "city", req.getBillCity());
            putIfText(bill, "state", req.getBillState());
            putIfText(bill, "state_code", req.getBillStateCode());
            putIfText(bill, "zip", req.getBillZip());
            putIfText(bill, "country", req.getBillCountry());
            putIfText(bill, "fax", req.getBillFax());

            Map<String, Object> ship = new HashMap<>();
            putIfText(ship, "attention", req.getShipAttention());
            putIfText(ship, "street", req.getShipStreet());
            putIfText(ship, "city", req.getShipCity());
            putIfText(ship, "state", req.getShipState());
            putIfText(ship, "state_code", req.getShipStateCode());
            putIfText(ship, "zip", req.getShipZip());
            putIfText(ship, "country", req.getShipCountry());
            putIfText(ship, "fax", req.getShipFax());

            if (!bill.isEmpty())
                cust.put("billing_address", bill);
            if (!ship.isEmpty())
                cust.put("shipping_address", ship);

            cust.put("pricebook_id", pricebookId);
            payload.put("customer", cust);
        }

        // 6) Persist HostedCheckout row (status=CREATED) for webhook correlation
        String orderId = UUID.randomUUID().toString();
        HostedCheckout hc = HostedCheckout.builder()
                .orderId(orderId)
                .gateway(gateway)
                .tenantId(tenantId)
                .billingId(billingId)
                .planCode(planCode)
                .currency(currency)
                .pricebookId(pricebookId)
                .status("CREATED")
                .redirectUrl(redirect)
                .requestPayloadJson(writeJsonSafe(payload))
                .build();
        hostedRepo.saveAndFlush(hc);

        // 7) Call selected gateway (Zoho adapter today)
        PaymentGateway gw = gatewayRegistry.resolve(gateway);
        PaymentGateway.HostedPageResult res = gw.createHostedPage(payload);

        // 8) Update HostedCheckout with provider details; mark PENDING
        hc.setProviderHostedpageId(res.hostedpageId());
        hc.setProviderDecryptedHostedpageId(res.decryptedHostedpageId());
        hc.setProviderStatus(res.status());
        hc.setHostedUrl(res.url());
        hc.setStatus("PENDING");
        hc.setResponsePayloadJson(res.rawResponseJson());
        if (res.expiringTime() != null) {
            hc.setExpiringTime(parseZohoTimeSafe(res.expiringTime())); // tolerant parser
        }
        hostedRepo.save(hc);

        // 9) Return to client
        return GetHostedPageResponse.builder()
                .orderId(orderId)
                .gateway(gateway)
                .hostedpageId(res.hostedpageId())
                .url(res.url())
                .status(res.status())
                .expiringTime(res.expiringTime())
                .build();
    }

    // ---------------------------------------------------------------------
    // /checkPaymentStatus (also provisions on SUCCESS; idempotent)
    // ---------------------------------------------------------------------
    @Transactional
    public CheckPaymentStatusResponse checkPaymentStatus(CheckPaymentStatusRequest req) {
        final String orderId = req.getOrderId().trim();
        final String gateway = StringUtils.hasText(req.getGateway()) ? req.getGateway().trim() : "ZOHOBILLING";

        HostedCheckout hc = hostedRepo.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown orderId"));

        if (StringUtils.hasText(hc.getGateway()) && !hc.getGateway().equalsIgnoreCase(gateway)) {
            throw new IllegalArgumentException("Gateway mismatch for this orderId");
        }

        // Try to pre-resolve plan/subscription for richer responses in all paths
        BillingEntitySubscription boundSub = billingSubRepo.findByActivationOrderId(orderId).orElse(null);
        PlanDtos.PlanView planView = null;
        if (boundSub != null && boundSub.getPlan() != null) {
            planView = planService.toDto(boundSub.getPlan());
        } else if (StringUtils.hasText(hc.getPlanCode())) {
            planRepo.findByExternalPlanCodeAndIsActiveTrue(hc.getPlanCode())
                    .ifPresent(p -> {
                        // applied below after optional provisioning to avoid shadowing a just-created sub
                    });
            var opt = planRepo.findByExternalPlanCodeAndIsActiveTrue(hc.getPlanCode());
            if (opt.isPresent()) {
                planView = planService.toDto(opt.get());
            }
        }

        // Fast path: terminal local state → no provider call
        PaymentStatus local = mapHostedCheckoutToPaymentStatus(hc.getStatus());
        if (local == PaymentStatus.SUCCESS || local == PaymentStatus.FAILED || local == PaymentStatus.EXPIRED) {
            // If SUCCESS and not yet provisioned (e.g., webhook delayed), provisioning is safe (idempotent)
            if (local == PaymentStatus.SUCCESS) {
                safeProvision(hc);
                // refresh sub after provisioning
                boundSub = billingSubRepo.findByActivationOrderId(orderId).orElse(boundSub);
                if (boundSub != null && boundSub.getPlan() != null) {
                    planView = planService.toDto(boundSub.getPlan());
                }
            }
            return CheckPaymentStatusResponse.builder()
                    .orderId(hc.getOrderId())
                    .gateway(hc.getGateway())
                    .status(local)
                    .providerStatusRaw(hc.getProviderStatus())
                    .hostedUrl(hc.getHostedUrl())
                    .expiringTime(hc.getExpiringTime() != null ? hc.getExpiringTime().toString() : null)
                    .message("Resolved from local record")
                    .plan(planView)
                    .subscriptionId(boundSub != null ? boundSub.getSubscriptionId() : null)
                    .subscriptionStatus(boundSub != null && boundSub.getStatus() != null ? boundSub.getStatus().name() : null)
                    .startDate(boundSub != null && boundSub.getStartDate() != null ? boundSub.getStartDate().toString() : null)
                    .endDate(boundSub != null && boundSub.getEndDate() != null ? boundSub.getEndDate().toString() : null)
                    .purchaseDate(boundSub != null && boundSub.getPurchaseDate() != null ? boundSub.getPurchaseDate().toString() : null)
                    .paidPlan(boundSub != null ? boundSub.getIsPaidPlan() : null)
                    .zohoSubscriptionId(boundSub != null ? boundSub.getZohoSubscriptionId() : null)
                    .build();
        }

        // No provider id yet → still pending/unknown (race or creation error)
        if (!StringUtils.hasText(hc.getProviderHostedpageId())) {
            return CheckPaymentStatusResponse.builder()
                    .orderId(hc.getOrderId())
                    .gateway(hc.getGateway())
                    .status(PaymentStatus.UNKNOWN)
                    .providerStatusRaw(hc.getProviderStatus())
                    .hostedUrl(hc.getHostedUrl())
                    .expiringTime(hc.getExpiringTime() != null ? hc.getExpiringTime().toString() : null)
                    .message("Hosted page not yet created")
                    .plan(planView)
                    .build();
        }

        // Live check from provider
        PaymentGateway gw = gatewayRegistry.resolve(gateway);
        PaymentGateway.HostedPageStatusResult result = gw.getHostedPageStatus(hc.getProviderHostedpageId());

        PaymentStatus normalized = mapProviderStatus(result.status());

        // Persist snapshot for later quick responses / audit
        hc.setProviderStatus(result.status());
        if (StringUtils.hasText(result.url()))
            hc.setHostedUrl(result.url());
        if (StringUtils.hasText(result.expiringTime())) {
            hc.setExpiringTime(parseZohoTimeSafe(result.expiringTime()));
        }
        hc.setStatus(switch (normalized) {
            case SUCCESS -> "COMPLETED";
            case FAILED -> "FAILED";
            case EXPIRED -> "EXPIRED";
            case PENDING -> "PENDING";
            default -> "PENDING";
        });
        hc.setResponsePayloadJson(result.rawResponseJson());
        hostedRepo.save(hc);

        // If SUCCESS → provision (idempotent)
        if (normalized == PaymentStatus.SUCCESS) {
            safeProvision(hc);
            boundSub = billingSubRepo.findByActivationOrderId(orderId).orElse(boundSub);
            if (boundSub != null && boundSub.getPlan() != null) {
                planView = planService.toDto(boundSub.getPlan());
            }
        }

        return CheckPaymentStatusResponse.builder()
                .orderId(hc.getOrderId())
                .gateway(hc.getGateway())
                .status(normalized)
                .providerStatusRaw(result.status())
                .hostedUrl(result.url())
                .expiringTime(result.expiringTime())
                .message("Fetched from provider")
                .plan(planView)
                .subscriptionId(boundSub != null ? boundSub.getSubscriptionId() : null)
                .subscriptionStatus(boundSub != null && boundSub.getStatus() != null ? boundSub.getStatus().name() : null)
                .startDate(boundSub != null && boundSub.getStartDate() != null ? boundSub.getStartDate().toString() : null)
                .endDate(boundSub != null && boundSub.getEndDate() != null ? boundSub.getEndDate().toString() : null)
                .purchaseDate(boundSub != null && boundSub.getPurchaseDate() != null ? boundSub.getPurchaseDate().toString() : null)
                .paidPlan(boundSub != null ? boundSub.getIsPaidPlan() : null)
                .zohoSubscriptionId(boundSub != null ? boundSub.getZohoSubscriptionId() : null)
                .build();
    }

    /**
     * Local-only payment status check.
     * - Does NOT call provider.
     * - Uses our HostedCheckout row only.
     * - If local status == SUCCESS, triggers idempotent provisioning.
     */
    @Transactional
    public CheckPaymentStatusResponse checkPaymentStatusLocalOnly(CheckPaymentStatusRequest req) {
        final String orderId = req.getOrderId().trim();
        final String gateway = StringUtils.hasText(req.getGateway()) ? req.getGateway().trim() : "ZOHOBILLING";

        HostedCheckout hc = hostedRepo.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown orderId"));

        // Optional enforcement: ensure request gateway matches persisted gateway
        if (StringUtils.hasText(hc.getGateway()) && !hc.getGateway().equalsIgnoreCase(gateway)) {
            throw new IllegalArgumentException("Gateway mismatch for this orderId");
        }

        // Prepare plan details from either bound subscription or requested planCode
        PlanDtos.PlanView planView = null;
        BillingEntitySubscription boundSub = billingSubRepo.findByActivationOrderId(orderId).orElse(null);
        if (boundSub != null && boundSub.getPlan() != null) {
            planView = planService.toDto(boundSub.getPlan());
        } else if (StringUtils.hasText(hc.getPlanCode())) {
            var opt = planRepo.findByExternalPlanCodeAndIsActiveTrue(hc.getPlanCode());
            if (opt.isPresent()) {
                planView = planService.toDto(opt.get());
            }
        }

        // Map LOCAL status only (no live provider call)
        PaymentStatus local = mapHostedCheckoutToPaymentStatus(hc.getStatus());

        // If terminal, return immediately; also provision on SUCCESS (idempotent)
        if (local == PaymentStatus.SUCCESS || local == PaymentStatus.FAILED || local == PaymentStatus.EXPIRED) {
            if (local == PaymentStatus.SUCCESS) {
                safeProvision(hc);
                boundSub = billingSubRepo.findByActivationOrderId(orderId).orElse(boundSub);
                if (boundSub != null && boundSub.getPlan() != null) {
                    planView = planService.toDto(boundSub.getPlan());
                }
            }
            return CheckPaymentStatusResponse.builder()
                    .orderId(hc.getOrderId())
                    .gateway(hc.getGateway())
                    .status(local)
                    .providerStatusRaw(hc.getProviderStatus())
                    .hostedUrl(hc.getHostedUrl())
                    .expiringTime(hc.getExpiringTime() != null ? hc.getExpiringTime().toString() : null)
                    .message("Resolved from local record (no live provider check)")
                    .plan(planView)
                    .subscriptionId(boundSub != null ? boundSub.getSubscriptionId() : null)
                    .subscriptionStatus(boundSub != null && boundSub.getStatus() != null ? boundSub.getStatus().name() : null)
                    .startDate(boundSub != null && boundSub.getStartDate() != null ? boundSub.getStartDate().toString() : null)
                    .endDate(boundSub != null && boundSub.getEndDate() != null ? boundSub.getEndDate().toString() : null)
                    .purchaseDate(boundSub != null && boundSub.getPurchaseDate() != null ? boundSub.getPurchaseDate().toString() : null)
                    .paidPlan(boundSub != null ? boundSub.getIsPaidPlan() : null)
                    .zohoSubscriptionId(boundSub != null ? boundSub.getZohoSubscriptionId() : null)
                    .build();
        }

        // Not terminal yet → report as PENDING/UNKNOWN based on local info
        PaymentStatus pendingish = (local == PaymentStatus.UNKNOWN) ? PaymentStatus.UNKNOWN : PaymentStatus.PENDING;

        return CheckPaymentStatusResponse.builder()
                .orderId(hc.getOrderId())
                .gateway(hc.getGateway())
                .status(pendingish)
                .providerStatusRaw(hc.getProviderStatus())
                .hostedUrl(hc.getHostedUrl())
                .expiringTime(hc.getExpiringTime() != null ? hc.getExpiringTime().toString() : null)
                .message("Pending payment or awaiting provider confirmation")
                .plan(planView)
                .build();
    }

    // ---------------------------------------------------------------------
    // WEBHOOK: provider → our system (source of truth)
    // ---------------------------------------------------------------------
    @Transactional
    public WebhookResult processPaymentWebhook(MultiValueMap<String, String> headers, String rawBody) {
        final String provider = detectProvider(headers, rawBody); // considers payment fields too

        // Optional HMAC validation for Zoho
        if (StringUtils.hasText(zohoWebhookSecret)) {
            if (!validateZohoSignature(headers, rawBody, zohoWebhookSecret)) {
                log.warn("Webhook signature invalid. Rejecting.");
                return WebhookResult.builder()
                        .accepted(false)
                        .message("invalid signature")
                        .provider(provider)
                        .build();
            }
        } else {
            log.warn("Webhook secret not configured; accepting without signature validation.");
        }

        Map<String, Object> root = parseJsonSafely(rawBody);
        if (root == null) {
            return WebhookResult.builder().accepted(false).message("invalid json").provider(provider).build();
        }

        // 1) Old way (reference_id/orderId on hostedpage payloads)
        String orderId = extractOrderId(root);

        // 2) Preferred for Zoho “payment” payload → correlate by hosted_page_id
        HostedCheckout hc = null;
        if (!StringUtils.hasText(orderId) && isZohoPaymentPayload(root)) {
            String hostedpageId = extractHostedpageIdFromPayment(root);
            if (StringUtils.hasText(hostedpageId)) {
                // FIRST try decrypted-hostedpage-id (your hosted_page_id)
                hc = hostedRepo.findFirstByProviderDecryptedHostedpageId(hostedpageId).orElse(null);
                // FALLBACK to providerHostedpageId (if older rows captured only encrypted id)
                if (hc == null) {
                    hc = hostedRepo.findFirstByProviderHostedpageId(hostedpageId).orElse(null);
                }
                if (hc != null) {
                    orderId = hc.getOrderId();
                }
            }
        }

        if (!StringUtils.hasText(orderId)) {
            log.warn("Webhook missing correlator (orderId/hosted_page_id). Payload: {}", redact(rawBody));
            return WebhookResult.builder()
                    .accepted(false)
                    .message("missing orderId")
                    .provider(provider)
                    .build();
        }

        if (hc == null) {
            hc = hostedRepo.findByOrderId(orderId).orElse(null);
        }
        if (hc == null) {
            log.warn("HostedCheckout not found for orderId={}. Payload: {}", orderId, redact(rawBody));
            return WebhookResult.builder()
                    .accepted(false)
                    .message("hosted checkout not found")
                    .provider(provider)
                    .orderId(orderId)
                    .build();
        }

        // Idempotency: if terminal, no-op (but append payload for audit)
        PaymentStatus current = mapHostedCheckoutToPaymentStatus(hc.getStatus());
        if (current == PaymentStatus.SUCCESS || current == PaymentStatus.FAILED || current == PaymentStatus.EXPIRED) {
            appendProviderPayload(hc, rawBody);
            hostedRepo.save(hc);
            // If SUCCESS and provisioning was missed earlier, call safely (idempotent)
            if (current == PaymentStatus.SUCCESS) {
                safeProvision(hc);
            }
            return WebhookResult.builder()
                    .accepted(true)
                    .message("already terminal")
                    .provider(provider)
                    .orderId(hc.getOrderId())
                    .normalizedStatus(current)
                    .build();
        }

        // Normalize provider status (works for hostedpage and payment payloads)
        String providerStatus = extractProviderStatus(root);
        PaymentStatus normalized = mapProviderStatus(providerStatus);
        if (normalized == PaymentStatus.UNKNOWN) {
            normalized = PaymentStatus.PENDING;
        }

        // --- Capture / compare hosted_page_id (prefer payment payload) ---
        String hostedpageIdFromPayment = isZohoPaymentPayload(root) ? extractHostedpageIdFromPayment(root) : null;
        String hostedpageIdGeneric = extractHostedpageId(root);
        String hostedpageIdObserved = StringUtils.hasText(hostedpageIdFromPayment) ? hostedpageIdFromPayment
                : hostedpageIdGeneric;

        if (StringUtils.hasText(hostedpageIdObserved)) {
            if (!StringUtils.hasText(hc.getProviderDecryptedHostedpageId())) {
                hc.setProviderDecryptedHostedpageId(hostedpageIdObserved);
            } else if (!hostedpageIdObserved.equals(hc.getProviderDecryptedHostedpageId())) {
                log.warn("Decrypted hosted_page_id mismatch for orderId={} stored={} observed={}",
                        hc.getOrderId(), hc.getProviderDecryptedHostedpageId(), hostedpageIdObserved);
            }
            if (!StringUtils.hasText(hc.getProviderHostedpageId())) {
                hc.setProviderHostedpageId(hostedpageIdObserved);
            }
        }

        // Update + audit
        hc.setProviderStatus(providerStatus);
        hc.setStatus(switch (normalized) {
            case SUCCESS -> "COMPLETED";
            case FAILED -> "FAILED";
            case EXPIRED -> "EXPIRED";
            case PENDING -> "PENDING";
            default -> "PENDING";
        });

        // Best-effort: store Zoho subscription id from payment payload
        if (isZohoPaymentPayload(root)) {
            String zohoSubId = extractZohoSubscriptionIdFromPayment(root);
            setZohoSubscriptionIdIfPresent(hc, zohoSubId);
        }

        appendProviderPayload(hc, rawBody);
        hostedRepo.save(hc);

        log.info("Webhook updated orderId={} → status={} (providerStatus={})", hc.getOrderId(), normalized, providerStatus);

        // Provision after SUCCESS (idempotent, same-plan-only replacement inside service)
        if (normalized == PaymentStatus.SUCCESS) {
            safeProvision(hc);
        }

        return WebhookResult.builder()
                .accepted(true)
                .message("ok")
                .provider(detectProvider(headers, rawBody))
                .orderId(hc.getOrderId())
                .normalizedStatus(normalized)
                .build();
    }

    // -------- Webhook DTO (for controller response) --------
    @lombok.Builder
    public static record WebhookResult(
            boolean accepted,
            String message,
            String orderId,
            String provider,
            PaymentStatus normalizedStatus) {
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private void safeProvision(HostedCheckout hc) {
        try {
            subscriptionProvisioningService.activateFromSuccessfulCheckout(hc);
        } catch (Exception e) {
            log.error("Provisioning failed for orderId={} : {}", hc.getOrderId(), e.getMessage(), e);
        }
    }

    private String trim(String s) {
        return (s == null) ? null : s.trim();
    }

    private void putIfText(Map<String, Object> map, String k, String v) {
        if (StringUtils.hasText(v))
            map.put(k, v.trim());
    }

    private String writeJsonSafe(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Zoho returns expiring_time like "2025-10-21T03:16:33+0530" (no colon in offset).
     * This parser tolerates both "+05:30" and "+0530".
     */
    private OffsetDateTime parseZohoTimeSafe(String s) {
        try {
            if (s == null)
                return null;
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
            return OffsetDateTime.parse(s, f);
        } catch (Exception e) {
            log.debug("Could not parse expiring_time [{}]: {}", s, e.getMessage());
            return null;
        }
    }

    private PaymentStatus mapHostedCheckoutToPaymentStatus(String local) {
        if (local == null)
            return PaymentStatus.UNKNOWN;
        return switch (local.toUpperCase(Locale.ROOT)) {
            case "CREATED", "PENDING" -> PaymentStatus.PENDING;
            case "COMPLETED", "PAID" -> PaymentStatus.SUCCESS;
            case "FAILED" -> PaymentStatus.FAILED;
            case "EXPIRED" -> PaymentStatus.EXPIRED;
            default -> PaymentStatus.UNKNOWN;
        };
    }

    /** Provider hostedpage.status / payment.status → normalized */
    private PaymentStatus mapProviderStatus(String provider) {
        if (provider == null)
            return PaymentStatus.UNKNOWN;
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "success", "paid", "completed", "used" -> PaymentStatus.SUCCESS;
            case "expired" -> PaymentStatus.EXPIRED;
            case "failed" -> PaymentStatus.FAILED;
            case "fresh", "created", "initiated", "inprogress", "pending" -> PaymentStatus.PENDING;
            default -> PaymentStatus.UNKNOWN;
        };
    }

    // ---- Webhook helpers ----

    private boolean validateZohoSignature(MultiValueMap<String, String> headers, String body, String secret) {
        String provided = headerFirst(headers, "X-Zoho-Webhook-Signature");
        if (!StringUtils.hasText(provided))
            provided = headerFirst(headers, "x-zoho-webhook-signature");
        if (!StringUtils.hasText(provided))
            return false;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computed = bytesToHex(digest);
            boolean ok = provided.equalsIgnoreCase(computed);
            if (!ok) {
                log.warn("Zoho signature mismatch. provided={}, computed={}", provided, computed);
            }
            return ok;
        } catch (Exception e) {
            log.error("Error validating Zoho signature", e);
            return false;
        }
    }

    private String detectProvider(MultiValueMap<String, String> headers, String rawBody) {
        String fromHeader = headerFirst(headers, "X-Provider");
        Map<String, Object> root = parseJsonSafely(rawBody);
        if (root != null && isZohoPaymentPayload(root)) {
            String fromPayment = extractProviderFromPayment(root);
            if (StringUtils.hasText(fromPayment))
                return fromPayment;
        }
        return StringUtils.hasText(fromHeader) ? fromHeader : "ZOHOBILLING";
    }

    private String headerFirst(MultiValueMap<String, String> headers, String key) {
        if (headers == null || key == null)
            return null;
        var list = headers.get(key);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSafely(String raw) {
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse webhook JSON", e);
            return null;
        }
    }

    private String extractHostedpageId(Map<String, Object> root) {
        // payment payload first
        String id = extractHostedpageIdFromPayment(root);
        if (!StringUtils.hasText(id))
            id = tryExtract(root, "data.hostedpage.hostedpage_id");
        if (!StringUtils.hasText(id))
            id = tryExtract(root, "data.hostedpage.id");
        if (!StringUtils.hasText(id))
            id = tryExtract(root, "hostedpage_id");
        if (!StringUtils.hasText(id))
            id = tryExtract(root, "hostedpage.id");
        return id;
    }

    private String extractProviderStatus(Map<String, Object> root) {
        // payment payload first
        String s = extractProviderStatusFromPayment(root);
        if (!StringUtils.hasText(s))
            s = tryExtract(root, "data.hostedpage.status");
        if (!StringUtils.hasText(s))
            s = tryExtract(root, "hostedpage.status");
        if (!StringUtils.hasText(s))
            s = tryExtract(root, "status");
        return s;
    }

    private String extractOrderId(Map<String, Object> root) {
        // Prefer reference_id you include while creating the hosted page, or any order id you pass along
        String s = tryExtract(root, "data.hostedpage.reference_id");
        if (!StringUtils.hasText(s))
            s = tryExtract(root, "hostedpage.reference_id");
        if (!StringUtils.hasText(s))
            s = tryExtract(root, "orderId");
        if (!StringUtils.hasText(s))
            s = tryExtract(root, "data.order_id");
        return s;
    }

    @SuppressWarnings("unchecked")
    private String tryExtract(Object node, String path) {
        if (!(node instanceof Map))
            return null;
        String[] parts = path.split("\\.");
        Object cur = node;
        for (String p : parts) {
            if (!(cur instanceof Map))
                return null;
            cur = ((Map<String, Object>) cur).get(p);
            if (cur == null)
                return null;
        }
        return (cur instanceof String) ? (String) cur : String.valueOf(cur);
    }

    private void appendProviderPayload(HostedCheckout hc, String raw) {
        String prev = hc.getResponsePayloadJson();
        if (!StringUtils.hasText(prev)) {
            hc.setResponsePayloadJson(raw);
            return;
        }
        String merged = prev + "\n\n---\n\n" + raw;
        if (merged.length() > 200_000) {
            merged = merged.substring(merged.length() - 200_000);
        }
        hc.setResponsePayloadJson(merged);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String redact(String in) {
        if (in == null)
            return "";
        return in.length() > 4000 ? in.substring(0, 4000) + "...(truncated)" : in;
    }

    // -------------------- Payment-payload helpers --------------------

    @SuppressWarnings("unchecked")
    private boolean isZohoPaymentPayload(Map<String, Object> root) {
        return root.get("payment") instanceof Map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPayment(Map<String, Object> root) {
        Object p = root.get("payment");
        return (p instanceof Map) ? (Map<String, Object>) p : Map.of();
    }

    @SuppressWarnings("unchecked")
    private String extractHostedpageIdFromPayment(Map<String, Object> root) {
        Map<String, Object> p = getPayment(root);
        Object invoices = p.get("invoices");
        if (!(invoices instanceof List<?> list) || list.isEmpty())
            return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> m))
            return null;
        Object v = ((Map<String, Object>) m).get("hosted_page_id");
        return (v == null) ? null : String.valueOf(v);
    }

    private String extractProviderStatusFromPayment(Map<String, Object> root) {
        Map<String, Object> p = getPayment(root);
        String s = asText(p.get("payment_status")); // "paid"
        if (!StringUtils.hasText(s))
            s = asText(p.get("status")); // often "success"
        return s;
    }

    private String extractProviderFromPayment(Map<String, Object> root) {
        Map<String, Object> p = getPayment(root);
        String v = asText(tryGet(p, "autotransaction", "payment_gateway")); // "test_gateway"
        if (!StringUtils.hasText(v))
            v = asText(p.get("payment_mode"));
        return v;
    }

    @SuppressWarnings("unchecked")
    private String extractZohoSubscriptionIdFromPayment(Map<String, Object> root) {
        Map<String, Object> p = getPayment(root);
        Object invoices = p.get("invoices");
        if (!(invoices instanceof List<?> list) || list.isEmpty())
            return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> m))
            return null;
        Object subs = ((Map<String, Object>) m).get("subscription_ids");
        if (subs instanceof List<?> l && !l.isEmpty()) {
            Object id0 = l.get(0);
            return (id0 == null) ? null : String.valueOf(id0);
        }
        return null;
    }

    private String asText(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private Object tryGet(Map<String, Object> map, String key, String nestedKey) {
        Object inner = map.get(key);
        if (!(inner instanceof Map))
            return null;
        return ((Map<String, Object>) inner).get(nestedKey);
    }

    /**
     * Best-effort setter: if HostedCheckout has setZohoSubscriptionId(String),
     * call it reflectively; else no-op.
     */
    private void setZohoSubscriptionIdIfPresent(HostedCheckout hc, String id) {
        if (!StringUtils.hasText(id) || hc == null)
            return;
        try {
            Method m = hc.getClass().getMethod("setZohoSubscriptionId", String.class);
            m.invoke(hc, id);
        } catch (NoSuchMethodException nsme) {
            // field not present; safe to ignore
        } catch (Exception e) {
            log.debug("Could not set zohoSubscriptionId reflectively: {}", e.getMessage());
        }
    }
}
