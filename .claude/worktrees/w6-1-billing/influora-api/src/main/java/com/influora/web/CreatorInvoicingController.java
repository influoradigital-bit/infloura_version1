package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.CampaignServiceInvoice;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformCommissionInvoice;
import com.influora.security.AuthPrincipal;
import com.influora.service.CampaignServiceInvoiceService;
import com.influora.service.CommissionInvoiceService;
import com.influora.service.CreatorContextService;
import com.influora.web.dto.invoicing.InvoicingDtos.CampaignServiceInvoiceResponse;
import com.influora.web.dto.invoicing.InvoicingDtos.PlatformCommissionInvoiceResponse;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D14 — creator-facing reads for Doc#2 ({@code campaign_service_invoices}, the creator's own
 * earnings invoice — Creator -> Brand) and Doc#3b ({@code platform_commission_invoices}, leg
 * CREATOR — Influora's commission invoice TO the creator). Every read is creator-scoped via
 * {@link CreatorContextService} (TECH-STACK.md rule #2 — resolve the row, then check ownership).
 * Mounted at {@code /creator} (full path {@code /api/v1/creator}), ownership-checked PDF download
 * mirrors {@code BillingController#getInvoicePdf} / {@code InvoiceService#getInvoicePdf}.
 */
@RestController
@RequestMapping("/creator")
public class CreatorInvoicingController {

    private final CreatorContextService creatorContextService;
    private final CampaignServiceInvoiceService campaignServiceInvoiceService;
    private final CommissionInvoiceService commissionInvoiceService;

    public CreatorInvoicingController(
            CreatorContextService creatorContextService,
            CampaignServiceInvoiceService campaignServiceInvoiceService,
            CommissionInvoiceService commissionInvoiceService) {
        this.creatorContextService = creatorContextService;
        this.campaignServiceInvoiceService = campaignServiceInvoiceService;
        this.commissionInvoiceService = commissionInvoiceService;
    }

    /** Doc#2 — the creator's own earnings invoices, most recent first. */
    @GetMapping("/campaign-invoices")
    public ResponseEntity<ApiResponse<List<CampaignServiceInvoiceResponse>>> getCampaignInvoices(
            @AuthenticationPrincipal AuthPrincipal principal) {
        CreatorProfile creator = creatorContextService.requireCreatorProfile(principal);
        List<CampaignServiceInvoiceResponse> invoices =
                campaignServiceInvoiceService.getInvoicesForCreator(creator.getUserId()).stream()
                        .map(this::toCampaignInvoiceResponse)
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(invoices));
    }

    @GetMapping("/campaign-invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> getCampaignInvoicePdf(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String invoiceId) {
        CreatorProfile creator = creatorContextService.requireCreatorProfile(principal);
        byte[] pdf = campaignServiceInvoiceService.getInvoicePdfForCreator(invoiceId, creator.getUserId());
        return pdfResponse(pdf, invoiceId);
    }

    /** Doc#3b — Influora's commission invoice to the creator, most recent first. */
    @GetMapping("/commission-invoices")
    public ResponseEntity<ApiResponse<List<PlatformCommissionInvoiceResponse>>> getCommissionInvoices(
            @AuthenticationPrincipal AuthPrincipal principal) {
        CreatorProfile creator = creatorContextService.requireCreatorProfile(principal);
        List<PlatformCommissionInvoiceResponse> invoices =
                commissionInvoiceService.getInvoicesForCreator(creator.getUserId()).stream()
                        .map(this::toCommissionInvoiceResponse)
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(invoices));
    }

    @GetMapping("/commission-invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> getCommissionInvoicePdf(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String invoiceId) {
        CreatorProfile creator = creatorContextService.requireCreatorProfile(principal);
        byte[] pdf = commissionInvoiceService.getInvoicePdfForCreator(invoiceId, creator.getUserId());
        return pdfResponse(pdf, invoiceId);
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] pdf, String invoiceId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"invoice-" + invoiceId + ".pdf\"")
                .body(pdf);
    }

    private CampaignServiceInvoiceResponse toCampaignInvoiceResponse(CampaignServiceInvoice invoice) {
        return new CampaignServiceInvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getCollaborationId(),
                invoice.getCampaignId(),
                invoice.getCreatorUserId(),
                invoice.getBrandWorkspaceId(),
                invoice.getGrossAmount(),
                invoice.getCurrency(),
                invoice.getCreatorGstin(),
                invoice.getTcsAmount(),
                invoice.getHsnSacCode(),
                invoice.getStatus().name(),
                invoice.getIssuedAt(),
                "/creator/campaign-invoices/" + invoice.getId() + "/pdf");
    }

    private PlatformCommissionInvoiceResponse toCommissionInvoiceResponse(PlatformCommissionInvoice invoice) {
        return new PlatformCommissionInvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getLeg().name(),
                invoice.getCampaignId(),
                invoice.getCounterpartyWorkspaceId(),
                invoice.getCounterpartyUserId(),
                invoice.getFeeBpsApplied(),
                invoice.getCommissionAmount(),
                invoice.getGstAmount(),
                invoice.getHsnSacCode(),
                invoice.getStatus().name(),
                invoice.getIssuedAt(),
                "/creator/commission-invoices/" + invoice.getId() + "/pdf");
    }
}
