package com.welhire.welhire_subscription_service.controller;

import com.welhire.welhire_subscription_service.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing/transaction")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    /**
     * GET /getData?tenantId=...&billingId=...&page=0&size=10
     * Returns SUCCESS and FAILED payments for the tenant+billing, newest first, paginated.
     */
    @GetMapping("/getData")
    public ResponseEntity<TransactionService.PageResponse<TransactionService.TransactionView>> getData(
            @RequestParam("tenantId") String tenantId,
            @RequestParam("billingId") String billingId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(billingId)) {
            return ResponseEntity.ok(new TransactionService.PageResponse<>(0,0,0,0, java.util.List.of()));
        }
        var resp = transactionService.getTransactions(tenantId, billingId, page, size);
        return ResponseEntity.ok(resp);
    }
}
