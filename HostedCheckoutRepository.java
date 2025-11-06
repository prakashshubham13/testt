package com.welhire.welhire_subscription_service.repository;

import com.welhire.welhire_subscription_service.entity.HostedCheckout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HostedCheckoutRepository extends JpaRepository<HostedCheckout, Long> {
    Optional<HostedCheckout> findByOrderId(String orderId);
    Optional<HostedCheckout> findByProviderHostedpageId(String hostedpageId);
    Optional<HostedCheckout> findFirstByProviderHostedpageId(String providerHostedpageId);
    Optional<HostedCheckout> findFirstByProviderDecryptedHostedpageId(String providerDecryptedHostedpageId);
    List<HostedCheckout> findAllByTenantIdAndBillingIdOrderByCreatedAtDesc(String tenantId, String billingId);

    Page<HostedCheckout> findByTenantIdAndBillingIdAndStatusIn(
            String tenantId,
            String billingId,
            Collection<String> statuses,
            Pageable pageable
    );
}
