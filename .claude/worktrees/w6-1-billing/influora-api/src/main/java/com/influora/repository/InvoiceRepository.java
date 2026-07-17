package com.influora.repository;

import com.influora.domain.entity.Invoice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Subscription invoices — billing history + PDF archive. Task 17 subscription-billing functional core, Phase 1. */
public interface InvoiceRepository extends JpaRepository<Invoice, String> {

    List<Invoice> findBySubscriptionIdOrderByIssuedAtDesc(String subscriptionId);

    /** Workspace-scoped list for {@code GET /billing/invoices} — denormalized {@code workspace_id} column. */
    List<Invoice> findByWorkspaceIdOrderByIssuedAtDesc(String workspaceId);

    Optional<Invoice> findByRazorpayInvoiceId(String razorpayInvoiceId);
}
