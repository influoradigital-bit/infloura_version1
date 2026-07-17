package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PlatformCommissionInvoice;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CommissionInvoiceLeg;
import com.influora.domain.enums.HsnSacAppliesTo;
import com.influora.domain.enums.InvoiceNumberSeriesType;
import com.influora.domain.enums.MarketplaceInvoiceStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PlatformCommissionInvoiceRepository;
import com.influora.repository.WorkspaceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D14 Doc#3 (platform commission invoice, split brand/creator legs per D14-E).
 *
 * <ul>
 *   <li>3a (BRAND leg) — wired into {@code BrandCampaignFeeService#chargeOnPublish} AFTER its
 *       {@code PLATFORM_FEE} ledger posting succeeds. One per campaign.
 *   <li>3b (CREATOR leg) — wired into {@code PlatformFeeService#deductAtRelease} AFTER its {@code
 *       PLATFORM_FEE} ledger posting succeeds. One per released escrow hold.
 * </ul>
 *
 * <p><b>Idempotency (Rohan build-flag #2):</b> both creation methods are only ever called after
 * the caller's own ledger posting has already succeeded, and additionally re-check the repository
 * for an existing row before minting a statutory number — never on "the endpoint fired."
 */
@Service
public class CommissionInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(CommissionInvoiceService.class);

    private static final String PDF_KEY_PREFIX = "commission-invoices/";
    private static final BigDecimal GST_RATE = new BigDecimal("0.18");

    private final PlatformCommissionInvoiceRepository invoiceRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final HsnSacCodeService hsnSacCodeService;
    private final CommissionInvoicePdfService pdfService;
    private final R2StorageService r2StorageService;

    public CommissionInvoiceService(
            PlatformCommissionInvoiceRepository invoiceRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            CreatorProfileRepository creatorProfileRepository,
            EscrowHoldRepository escrowHoldRepository,
            InvoiceNumberService invoiceNumberService,
            HsnSacCodeService hsnSacCodeService,
            CommissionInvoicePdfService pdfService,
            R2StorageService r2StorageService) {
        this.invoiceRepository = invoiceRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.hsnSacCodeService = hsnSacCodeService;
        this.pdfService = pdfService;
        this.r2StorageService = r2StorageService;
    }

    /**
     * Doc#3a — campaign-scoped, created inside {@code BrandCampaignFeeService.chargeOnPublish}
     * after its {@code PLATFORM_FEE} posting. Caller must only invoke this when the fee posting
     * actually happened ({@code feePosting != null} — a legally-waived 0bps fee has no posting and
     * no invoice).
     */
    @Transactional
    public PlatformCommissionInvoice createBrandLegAtPublish(
            Campaign campaign,
            String workspaceId,
            int feeBpsApplied,
            BigDecimal feeAmount,
            WalletLedgerService.LedgerPostingResult feePosting) {
        var existing = invoiceRepository.findByCampaignIdAndLeg(campaign.getId(), CommissionInvoiceLeg.BRAND);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (feePosting == null) {
            throw new ApiException(
                    "COMMISSION_INVOICE_NO_POSTING",
                    "Cannot issue a brand commission invoice without a completed PLATFORM_FEE posting",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        Workspace brandWorkspace =
                workspaceRepository
                        .findById(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.CONFLICT));

        String invoiceNumber =
                invoiceNumberService.generateNext(InvoiceNumberSeriesType.COMMISSION_BRAND, null);
        BigDecimal gstAmount = feeAmount.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);

        PlatformCommissionInvoice invoice =
                PlatformCommissionInvoice.builder()
                        .id(Ulids.newUlid())
                        .invoiceNumber(invoiceNumber)
                        .leg(CommissionInvoiceLeg.BRAND)
                        .campaignId(campaign.getId())
                        .counterpartyWorkspaceId(workspaceId)
                        .feeBpsApplied(feeBpsApplied)
                        .commissionAmount(feeAmount)
                        .gstAmount(gstAmount)
                        .hsnSacCode(hsnSacCodeService.resolveCode(HsnSacAppliesTo.PLATFORM_COMMISSION))
                        .ledgerTxnId(feePosting.debitLeg().getId())
                        .status(MarketplaceInvoiceStatus.PAID)
                        .issuedAt(Instant.now())
                        .build();
        invoiceRepository.save(invoice);

        log.info(
                "Issued Doc#3a brand commission invoice {} for campaign {}", invoiceNumber, campaign.getId());

        renderAndStore(invoice, campaign, brandWorkspace, null);
        return invoice;
    }

    /**
     * Doc#3b — escrow-hold-scoped, created inside {@code PlatformFeeService.deductAtRelease} after
     * its {@code PLATFORM_FEE} posting. Caller must only invoke this when the fee posting actually
     * happened ({@code feePosting != null}).
     */
    @Transactional
    public PlatformCommissionInvoice createCreatorLegAtRelease(
            String escrowHoldId,
            String creatorUserId,
            int feeBpsApplied,
            BigDecimal platformFee,
            WalletLedgerService.LedgerPostingResult feePosting) {
        var existing = invoiceRepository.findByEscrowHoldIdAndLeg(escrowHoldId, CommissionInvoiceLeg.CREATOR);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (feePosting == null) {
            throw new ApiException(
                    "COMMISSION_INVOICE_NO_POSTING",
                    "Cannot issue a creator commission invoice without a completed PLATFORM_FEE posting",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        EscrowHold hold =
                escrowHoldRepository
                        .findById(escrowHoldId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.CONFLICT));
        Campaign campaign =
                campaignRepository
                        .findById(hold.getCampaignId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.CONFLICT));
        CreatorProfile creator =
                creatorProfileRepository
                        .findByUserId(creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "Creator profile not found",
                                                HttpStatus.CONFLICT));

        String invoiceNumber =
                invoiceNumberService.generateNext(InvoiceNumberSeriesType.COMMISSION_CREATOR, null);
        BigDecimal gstAmount = platformFee.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);

        PlatformCommissionInvoice invoice =
                PlatformCommissionInvoice.builder()
                        .id(Ulids.newUlid())
                        .invoiceNumber(invoiceNumber)
                        .leg(CommissionInvoiceLeg.CREATOR)
                        .campaignId(campaign.getId())
                        .escrowHoldId(escrowHoldId)
                        .counterpartyUserId(creatorUserId)
                        .feeBpsApplied(feeBpsApplied)
                        .commissionAmount(platformFee)
                        .gstAmount(gstAmount)
                        .hsnSacCode(hsnSacCodeService.resolveCode(HsnSacAppliesTo.PLATFORM_COMMISSION))
                        .ledgerTxnId(feePosting.debitLeg().getId())
                        .status(MarketplaceInvoiceStatus.PAID)
                        .issuedAt(Instant.now())
                        .build();
        invoiceRepository.save(invoice);

        log.info(
                "Issued Doc#3b creator commission invoice {} for escrow hold {}", invoiceNumber, escrowHoldId);

        renderAndStore(invoice, campaign, null, creator);
        return invoice;
    }

    private void renderAndStore(
            PlatformCommissionInvoice invoice, Campaign campaign, Workspace brandWorkspace, CreatorProfile creator) {
        try {
            byte[] pdfBytes = pdfService.render(invoice, campaign, brandWorkspace, creator);
            if (r2StorageService.isAvailable()) {
                String objectKey = PDF_KEY_PREFIX + invoice.getId() + ".pdf";
                r2StorageService.putBytes(objectKey, pdfBytes, "application/pdf");
                invoice.setPdfR2Key(objectKey);
                invoiceRepository.save(invoice);
            } else {
                log.warn("R2 not configured — Doc#3 invoice PDF for {} generated but not stored", invoice.getId());
            }
        } catch (Exception e) {
            log.error(
                    "Doc#3 invoice PDF generation/storage failed for invoice {} — invoice row was still recorded",
                    invoice.getId(),
                    e);
        }
    }

    @Transactional(readOnly = true)
    public byte[] getInvoicePdfForBrand(String invoiceId, String brandWorkspaceId) {
        PlatformCommissionInvoice invoice =
                invoiceRepository
                        .findByIdAndCounterpartyWorkspaceId(invoiceId, brandWorkspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVOICE_NOT_FOUND", "Invoice not found", HttpStatus.NOT_FOUND));
        return renderForInvoice(invoice);
    }

    @Transactional(readOnly = true)
    public byte[] getInvoicePdfForCreator(String invoiceId, String creatorUserId) {
        PlatformCommissionInvoice invoice =
                invoiceRepository
                        .findByIdAndCounterpartyUserId(invoiceId, creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVOICE_NOT_FOUND", "Invoice not found", HttpStatus.NOT_FOUND));
        return renderForInvoice(invoice);
    }

    private byte[] renderForInvoice(PlatformCommissionInvoice invoice) {
        Campaign campaign =
                campaignRepository
                        .findById(invoice.getCampaignId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));
        Workspace brandWorkspace =
                invoice.getLeg() == CommissionInvoiceLeg.BRAND
                        ? workspaceRepository
                                .findById(invoice.getCounterpartyWorkspaceId())
                                .orElseThrow(
                                        () ->
                                                new ApiException(
                                                        "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND))
                        : null;
        CreatorProfile creator =
                invoice.getLeg() == CommissionInvoiceLeg.CREATOR
                        ? creatorProfileRepository
                                .findByUserId(invoice.getCounterpartyUserId())
                                .orElseThrow(
                                        () ->
                                                new ApiException(
                                                        "CREATOR_PROFILE_NOT_FOUND",
                                                        "Creator profile not found",
                                                        HttpStatus.NOT_FOUND))
                        : null;
        return pdfService.render(invoice, campaign, brandWorkspace, creator);
    }

    public List<PlatformCommissionInvoice> getInvoicesForBrand(String brandWorkspaceId) {
        return invoiceRepository.findByCounterpartyWorkspaceIdOrderByIssuedAtDesc(brandWorkspaceId);
    }

    public List<PlatformCommissionInvoice> getInvoicesForCreator(String creatorUserId) {
        return invoiceRepository.findByCounterpartyUserIdOrderByIssuedAtDesc(creatorUserId);
    }

    public String getInvoiceDownloadUrl(PlatformCommissionInvoice invoice) {
        if (invoice.getPdfR2Key() == null || invoice.getPdfR2Key().isBlank() || !r2StorageService.isAvailable()) {
            return null;
        }
        return r2StorageService.presignGet(invoice.getPdfR2Key()).uploadUrl();
    }
}
