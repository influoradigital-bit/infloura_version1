package com.influora.web.dto.invoicing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only D14 marketplace invoicing DTOs — Doc#2 ({@code campaign_service_invoices}) and Doc#3
 * ({@code platform_commission_invoices}). Never expose raw JPA entities over the wire
 * (TECH-STACK.md DTO discipline, same as {@code BillingDtos}).
 */
public final class InvoicingDtos {

    private InvoicingDtos() {}

    /** Doc#2 (creator service invoice, Creator -> Brand). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampaignServiceInvoiceResponse(
            String id,
            String invoiceNumber,
            String collaborationId,
            String campaignId,
            String creatorUserId,
            String brandWorkspaceId,
            BigDecimal grossAmount,
            String currency,
            String creatorGstin,
            BigDecimal tcsAmount,
            String hsnSacCode,
            String status,
            Instant issuedAt,
            String pdfUrl) {}

    /** Doc#3 (platform commission invoice, split brand/creator legs). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlatformCommissionInvoiceResponse(
            String id,
            String invoiceNumber,
            String leg,
            String campaignId,
            String counterpartyWorkspaceId,
            String counterpartyUserId,
            int feeBpsApplied,
            BigDecimal commissionAmount,
            BigDecimal gstAmount,
            String hsnSacCode,
            String status,
            Instant issuedAt,
            String pdfUrl) {}
}
