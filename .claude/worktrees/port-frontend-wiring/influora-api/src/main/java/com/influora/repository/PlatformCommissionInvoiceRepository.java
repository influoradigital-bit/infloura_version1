package com.influora.repository;

import com.influora.domain.entity.PlatformCommissionInvoice;
import com.influora.domain.enums.CommissionInvoiceLeg;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Doc#3 (platform commission invoice, split brand/creator legs) — read + idempotency-gate lookups. */
public interface PlatformCommissionInvoiceRepository
        extends JpaRepository<PlatformCommissionInvoice, String> {

    /**
     * Publish-time idempotency gate for the BRAND leg (3a) — {@code
     * CommissionInvoiceService#createBrandLegAtPublish} checks this first. One BRAND-leg invoice
     * per campaign (D14-E cardinality: 3a is 1-per-campaign @publish, not 1:1 with 3b).
     */
    Optional<PlatformCommissionInvoice> findByCampaignIdAndLeg(String campaignId, CommissionInvoiceLeg leg);

    /**
     * Release-time idempotency gate for the CREATOR leg (3b) — {@code
     * CommissionInvoiceService#createCreatorLegAtRelease} checks this first. One CREATOR-leg
     * invoice per released escrow hold.
     */
    Optional<PlatformCommissionInvoice> findByEscrowHoldIdAndLeg(String escrowHoldId, CommissionInvoiceLeg leg);

    List<PlatformCommissionInvoice> findByCounterpartyWorkspaceIdOrderByIssuedAtDesc(String workspaceId);

    List<PlatformCommissionInvoice> findByCounterpartyUserIdOrderByIssuedAtDesc(String userId);

    Optional<PlatformCommissionInvoice> findByIdAndCounterpartyWorkspaceId(String id, String workspaceId);

    Optional<PlatformCommissionInvoice> findByIdAndCounterpartyUserId(String id, String userId);
}
