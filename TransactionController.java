package com.welhire.welhire_subscription_service.controller;

import com.welhire.welhire_subscription_service.service.TransactionService;
import com.welhire.welhire_subscription_service.service.TransactionService.TransactionView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/billing/transaction")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    /**
     * GET /getData?tenantId=...&billingId=...
     * Returns SUCCESS transactions (newest first) for the given tenant & billing.
     */
    @GetMapping("/getData")
    public ResponseEntity<List<TransactionView>> getData(
            @RequestParam("tenantId") String tenantId,
            @RequestParam("billingId") String billingId
    ) {
        List<TransactionView> out = transactionService.getTransactions(tenantId, billingId);
        return ResponseEntity.ok(out);
    }
}
