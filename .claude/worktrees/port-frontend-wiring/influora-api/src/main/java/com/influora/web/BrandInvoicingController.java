package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.CampaignServiceInvoice;
import com.influora.domain.entity.PlatformCommissionInvoice;
import com.influora.domain.entity.Workspace;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.CampaignServiceInvoiceService;
import com.influora.service.CommissionInvoiceService;
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
 * D14 — brand-facing reads for Doc#2 ({@code campaign_service_invoices}, the creator's invoice
 * the brand was billed — Creator -> Brand) and Doc#3a ({@code platform_commission_invoices}, leg
 * BRAND — Influora's commission invoice to the brand). Every read is brand-workspace-scoped via
 * {@link BrandContextService#requireBrandWorkspace} (TECH-STACK.md rule #2). Mounted at {@code
 * /billing} alongside {@code BillingController}'s existing subscription-invoice endpoints.
 */
@RestController
@RequestMapping("/billing")
public class BrandInvoicingController {

    private final BrandContextService brandContextService;
    private final CampaignServiceInvoiceService campaignServiceInvoiceService;
    private final CommissionInvoiceService commissionInvoiceService;

    public BrandInvoicingController(
            BrandContextService brandContextService,
            CampaignServiceInvoiceService campaignServiceInvoiceService,
            CommissionInvoiceService commissionInvoiceService) {
        this.brandContextService = brandContextService;
        this.campaignServiceInvoiceService = campaignServiceInvoiceService;
        this.commissionInvoiceService = commissionInvoiceService;
    }

    /** Doc#2 — creator service invoices billed to this workspace, most recent first. */
    @GetMapping("/campaign-invoices")
    public ResponseEntity<ApiResponse<List<CampaignServiceInvoiceResponse>>> getCampaignInvoices(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        List<CampaignServiceInvoiceResponse> invoices =
                campaignServiceInvoiceService.getInvoicesForBrand(workspace.getId()).stream()
                        .map(this::toCampaignInvoiceResponse)
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(invoices));
    }

    @GetMapping("/campaign-invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> getCampaignInvoicePdf(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String invoiceId) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        byte[] pdf = campaignServiceInvoiceService.getInvoicePdfForBrand(invoiceId, workspace.getId());
        return pdfResponse(pdf, invoiceId);
    }

    /** Doc#3a — Influora's commission invoice to this workspace, most recent first. */
    @GetMapping("/commission-invoices")
    public ResponseEntity<ApiResponse<List<PlatformCommissionInvoiceResponse>>> getCommissionInvoices(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        List<PlatformCommissionInvoiceResponse> invoices =
                commissionInvoiceService.getInvoicesForBrand(workspace.getId()).stream()
                        .map(this::toCommissionInvoiceResponse)
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(invoices));
    }

    @GetMapping("/commission-invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> getCommissionInvoicePdf(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String invoiceId) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        byte[] pdf = commissionInvoiceService.getInvoicePdfForBrand(invoiceId, workspace.getId());
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
                "/billing/campaign-invoices/" + invoice.getId() + "/pdf");
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
                "/billing/commission-invoices/" + invoice.getId() + "/pdf");
    }
}
