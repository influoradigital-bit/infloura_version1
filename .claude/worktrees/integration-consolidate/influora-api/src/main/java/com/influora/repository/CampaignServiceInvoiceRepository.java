package com.influora.repository;

import com.influora.domain.entity.CampaignServiceInvoice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Doc#2 (creator service invoice, Creator -> Brand) — read + idempotency-gate lookups. */
public interface CampaignServiceInvoiceRepository extends JpaRepository<CampaignServiceInvoice, String> {

    List<CampaignServiceInvoice> findByCreatorUserIdOrderByIssuedAtDesc(String creatorUserId);

    List<CampaignServiceInvoice> findByBrandWorkspaceIdOrderByIssuedAtDesc(String brandWorkspaceId);

    /**
     * Release-time idempotency gate — {@code CampaignServiceInvoiceService#createAtRelease} checks
     * this FIRST, before minting a statutory invoice number, so a retried release (or a call from
     * more than one of the three {@code EscrowService} release sites against the same hold, which
     * should never legitimately happen but must still be safe) never double-issues.
     */
    Optional<CampaignServiceInvoice> findByEscrowHoldId(String escrowHoldId);

    Optional<CampaignServiceInvoice> findByInvoiceNumber(String invoiceNumber);

    Optional<CampaignServiceInvoice> findByIdAndCreatorUserId(String id, String creatorUserId);

    Optional<CampaignServiceInvoice> findByIdAndBrandWorkspaceId(String id, String brandWorkspaceId);
}
